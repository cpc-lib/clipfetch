package cc.ivera.ed2k.infrastructure;

import cc.ivera.ed2k.domain.Ed2kIdentity;
import cc.ivera.ed2k.domain.Ed2kParsedLink;
import cc.ivera.ed2k.domain.Ed2kResourceState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class AmuleApiClient implements Ed2kEngineClient {

    private final AmuleProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private volatile SessionToken cachedToken;
    private volatile boolean preferencesTuned = false;
    private volatile long lastAutoConnectEpochMs = 0L;

    private record SessionToken(String token, long expiresAtEpochSeconds) {
        boolean isExpiredSoon() {
            long now = Instant.now().getEpochSecond();
            return expiresAtEpochSeconds > 0 && now + 15 >= expiresAtEpochSeconds;
        }
    }

    private record HttpCallResult(int statusCode, String body, Long retryAfterMs, IOException ioException) {
        boolean isNetworkError() {
            return ioException != null;
        }
    }

    public AmuleApiClient(AmuleProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.max(500, properties.getHttpConnectTimeoutMs())))
                .build();
    }

    @Override
    public EngineHealth checkHealth() {
        if (!properties.isConfigured()) {
            return new EngineHealth(false, false, "", "电驴引擎未启用或未配置 AMULE_API_BASE_URL / AMULE_API_PASSWORD");
        }
        try {
            HttpCallResult healthRes = sendRaw("GET", "/api/v1/health", null, null);
            if (healthRes.isNetworkError()) {
                return new EngineHealth(true, false, "", "无法连接 aMule 服务，请确认容器已启动");
            }
            if (healthRes.statusCode() != 200) {
                return new EngineHealth(true, false, "",
                        "aMule 健康检查返回 HTTP " + healthRes.statusCode());
            }
            JsonNode healthNode = objectMapper.readTree(healthRes.body());
            boolean ecConnected = healthNode.path("ec_connected").asBoolean(false);
            boolean snapshotReady = healthNode.path("snapshot_ready").asBoolean(false);

            String version = "";
            HttpCallResult versionRes = sendRaw("GET", "/api/v1/version", null, null);
            if (!versionRes.isNetworkError() && versionRes.statusCode() == 200) {
                JsonNode verNode = objectMapper.readTree(versionRes.body());
                String daemonVer = verNode.path("daemon_version").asText("");
                String apiVer = verNode.path("amuleapi_version").asText("");
                version = !daemonVer.isBlank() ? daemonVer : apiVer;
            }

            if (!ecConnected) {
                return new EngineHealth(true, false, version, "amuleapi 已启动，但尚未连接到 amuled EC 端口");
            }
            if (!snapshotReady) {
                return new EngineHealth(true, false, version, "aMule 正在初始化快照，请稍候");
            }
            if (properties.isTunePreferencesOnStartup() && !preferencesTuned) {
                tuneEnginePreferencesBestEffort();
            }
            return enrichHealthWithNetworkStatus(version);
        } catch (Exception ex) {
            return new EngineHealth(true, false, "", "aMule 健康检查异常：" + ex.getMessage());
        }
    }

    private EngineHealth enrichHealthWithNetworkStatus(String version) {
        try {
            HttpCallResult statusRes = sendAuthenticated("GET", "/api/v1/status", null);
            if (statusRes.isNetworkError() || statusRes.statusCode() != 200 || statusRes.body() == null || statusRes.body().isBlank()) {
                return new EngineHealth(true, true, version, "aMule 引擎已连接就绪");
            }
            JsonNode root = objectMapper.readTree(statusRes.body());
            JsonNode ed2kNode = root.path("ed2k");
            JsonNode kadNode = root.path("kad");
            if (ed2kNode.isMissingNode() && kadNode.isMissingNode()) {
                return new EngineHealth(true, true, version, "aMule 引擎已连接就绪");
            }

            String ed2kState = ed2kNode.path("state").asText("");
            boolean ed2kConnected = "connected".equalsIgnoreCase(ed2kState);
            boolean highId = ed2kConnected && ed2kNode.path("high_id").asBoolean(false);
            String kadState = kadNode.path("state").asText("");
            boolean kadConnected = "connected".equalsIgnoreCase(kadState);
            int downloadSourceCount = Math.max(0, root.path("queue").path("download_source_count").asInt(0));

            if (properties.isAutoConnectNetworks() && (!ed2kConnected || !kadConnected)) {
                boolean ed2kConnecting = "connecting".equalsIgnoreCase(ed2kState);
                boolean kadConnecting = "connecting".equalsIgnoreCase(kadState);
                long nowMs = System.currentTimeMillis();
                if ((!ed2kConnecting || !kadConnecting) && nowMs - lastAutoConnectEpochMs >= 30_000L) {
                    lastAutoConnectEpochMs = nowMs;
                    connectNetworksBestEffort("both");
                }
            }

            StringBuilder msg = new StringBuilder("aMule 引擎已连接就绪");
            if (ed2kConnected) {
                msg.append(highId ? " · eD2k HighID" : " · eD2k LowID（映射 4662/TCP 与 4665/UDP 可提速）");
            } else if ("connecting".equalsIgnoreCase(ed2kState)) {
                msg.append(" · eD2k 连接中");
            } else {
                msg.append(" · eD2k 未连接");
            }
            if (kadConnected) {
                msg.append(" · Kad 已连接");
            } else if ("connecting".equalsIgnoreCase(kadState)) {
                msg.append(" · Kad 连接中");
            } else {
                msg.append(" · Kad 未连接");
            }
            return new EngineHealth(
                    true,
                    true,
                    version,
                    msg.toString(),
                    ed2kConnected,
                    highId,
                    kadConnected,
                    downloadSourceCount
            );
        } catch (Exception ex) {
            log.debug("Failed to enrich aMule status: {}", ex.getMessage());
            return new EngineHealth(true, true, version, "aMule 引擎已连接就绪");
        }
    }

    @Override
    public Optional<EngineDownloadSnapshot> findByIdentity(Ed2kIdentity identity) {
        if (!properties.isConfigured()) {
            return Optional.empty();
        }
        String hashPath = "/api/v1/downloads/" + identity.md4Hash();
        HttpCallResult dlRes = sendAuthenticated("GET", hashPath, null);
        if (!dlRes.isNetworkError() && dlRes.statusCode() == 200) {
            Optional<EngineDownloadSnapshot> parsed = parseDownloadItem(dlRes.body(), identity);
            if (parsed.isPresent()) {
                return parsed;
            }
        }

        // 若下载队列中不存在（如已完成并转入 shared 列表），查询 /api/v1/shared/{hash}
        String sharedPath = "/api/v1/shared/" + identity.md4Hash();
        HttpCallResult sharedRes = sendAuthenticated("GET", sharedPath, null);
        if (!sharedRes.isNetworkError() && sharedRes.statusCode() == 200) {
            return parseSharedItem(sharedRes.body(), identity);
        }
        return Optional.empty();
    }

    @Override
    public Map<Ed2kIdentity, EngineDownloadSnapshot> listSnapshotsByIdentities(Collection<Ed2kIdentity> identities) {
        Map<Ed2kIdentity, EngineDownloadSnapshot> result = new LinkedHashMap<>();
        if (identities == null || identities.isEmpty() || !properties.isConfigured()) {
            return result;
        }
        if (identities.size() > 1) {
            try {
                HttpCallResult bulkRes = sendAuthenticated("GET", "/api/v1/downloads?status=all&limit=1000", null);
                if (!bulkRes.isNetworkError() && bulkRes.statusCode() == 200 && bulkRes.body() != null && !bulkRes.body().isBlank()) {
                    JsonNode root = objectMapper.readTree(bulkRes.body());
                    JsonNode downloadsNode = root.path("downloads");
                    if (downloadsNode.isArray()) {
                        Map<String, JsonNode> byHash = new LinkedHashMap<>();
                        for (JsonNode item : downloadsNode) {
                            String h = item.path("hash").asText("").toLowerCase(Locale.ROOT);
                            if (!h.isBlank()) {
                                byHash.put(h, item);
                            }
                        }
                        for (Ed2kIdentity identity : identities) {
                            JsonNode matched = byHash.get(identity.md4Hash());
                            if (matched != null) {
                                parseDownloadNode(matched, identity).ifPresent(snap -> result.put(identity, snap));
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                log.debug("Bulk downloads snapshot query degraded to per-item query: {}", ex.getMessage());
            }
        }
        for (Ed2kIdentity identity : identities) {
            if (!result.containsKey(identity)) {
                findByIdentity(identity).ifPresent(snapshot -> result.put(identity, snapshot));
            }
        }
        return result;
    }

    @Override
    public CommandOutcome addDownload(Ed2kParsedLink link) {
        if (!properties.isConfigured()) {
            return CommandOutcome.retryable("AMULE_NOT_CONFIGURED", "电驴引擎未启用或未配置", null);
        }
        try {
            String payload = objectMapper.writeValueAsString(Map.of("links", List.of(link.canonicalLink())));
            HttpCallResult res = sendAuthenticated("POST", "/api/v1/downloads", payload);
            if (res.isNetworkError()) {
                if (res.ioException() instanceof HttpTimeoutException) {
                    return CommandOutcome.unknown("AMULE_TIMEOUT", "向 aMule 提交下载超时，等待状态核对");
                }
                return CommandOutcome.retryable("AMULE_NETWORK_ERROR", "无法连接 aMule 服务：" + res.ioException().getMessage(), null);
            }
            int code = res.statusCode();
            if (code == 200 || code == 201 || code == 202) {
                boostDownloadBestEffort(link.identity(), false);
                return CommandOutcome.applied(link.identity().md4Hash());
            }
            if (code == 207) {
                JsonNode root = objectMapper.readTree(res.body());
                JsonNode first = root.path("results").isArray() && !root.path("results").isEmpty()
                        ? root.path("results").get(0)
                        : null;
                if (first != null && first.path("ok").asBoolean(false)) {
                    boostDownloadBestEffort(link.identity(), false);
                    return CommandOutcome.applied(link.identity().md4Hash());
                }
                String errCode = first != null ? first.path("error").path("code").asText("AMULE_REJECTED") : "AMULE_REJECTED";
                String errMsg = first != null ? first.path("error").path("message").asText("aMule 拒绝添加该链接") : "aMule 拒绝添加该链接";
                return CommandOutcome.permanent(errCode, errMsg);
            }
            return mapNonSuccessStatus(res, "添加 eD2k 下载失败");
        } catch (Exception ex) {
            return CommandOutcome.unknown("AMULE_ADD_UNKNOWN", "添加 eD2k 下载时发生异常：" + ex.getMessage());
        }
    }

    @Override
    public CommandOutcome pauseDownload(Ed2kIdentity identity) {
        return patchDownloadFields(identity, Map.of("action", "pause"), "暂停 eD2k 下载失败");
    }

    @Override
    public CommandOutcome resumeDownload(Ed2kIdentity identity) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("action", "resume");
        fields.put("priority", properties.normalizedDownloadPriority());
        fields.put("a4af_auto", properties.isEnableA4afAuto());
        return patchDownloadFields(identity, fields, "恢复 eD2k 下载失败");
    }

    @Override
    public CommandOutcome boostDownload(Ed2kIdentity identity, boolean swapA4af) {
        if (!properties.isConfigured()) {
            return CommandOutcome.retryable("AMULE_NOT_CONFIGURED", "电驴引擎未启用或未配置", null);
        }
        Map<String, Object> patchFields = new LinkedHashMap<>();
        patchFields.put("priority", properties.normalizedDownloadPriority());
        patchFields.put("a4af_auto", properties.isEnableA4afAuto());
        CommandOutcome patchOutcome = patchDownloadFields(identity, patchFields, "优化 eD2k 下载优先级失败");
        if (swapA4af) {
            try {
                String a4afPayload = objectMapper.writeValueAsString(Map.of("action", "swap_this"));
                sendAuthenticated("POST", "/api/v1/downloads/" + identity.md4Hash() + "/a4af", a4afPayload);
            } catch (Exception ex) {
                log.debug("A4AF swap_this skipped for {}: {}", identity.md4Hash(), ex.getMessage());
            }
        }
        return patchOutcome;
    }

    @Override
    public AccelerationOutcome accelerateEngine() {
        if (!properties.isConfigured()) {
            return new AccelerationOutcome(false, false, false, "电驴引擎未启用或未配置");
        }
        boolean tuned = tuneEnginePreferencesBestEffort();
        boolean connected = connectNetworksBestEffort("both");
        String msg = (tuned || connected)
                ? "已下发高速传输参数（高优先级 / 协议混淆 / 尾盘并发 / 扩大寻源池）并触发 eD2k + Kad 双网重连寻源"
                : "aMule 暂不可达，将在连接恢复后自动应用加速参数";
        return new AccelerationOutcome(tuned, connected, false, msg);
    }

    @Override
    public void reloadSharedFiles() {
        if (!properties.isConfigured()) {
            return;
        }
        try {
            sendAuthenticated("POST", "/api/v1/shared_reload", null);
        } catch (Exception ex) {
            log.debug("Failed to trigger aMule shared_reload: {}", ex.getMessage());
        }
    }

    private void boostDownloadBestEffort(Ed2kIdentity identity, boolean swapA4af) {
        try {
            boostDownload(identity, swapA4af);
        } catch (Exception ex) {
            log.debug("Best-effort download boost skipped for {}: {}", identity.md4Hash(), ex.getMessage());
        }
    }

    private boolean tuneEnginePreferencesBestEffort() {
        if (!properties.isConfigured()) {
            return false;
        }
        try {
            Map<String, Object> connection = new LinkedHashMap<>();
            connection.put("max_download_kibibytes_per_second", properties.normalizedMaxDownloadKbps());
            connection.put("max_upload_kibibytes_per_second", properties.normalizedMaxUploadKbps());
            connection.put("upload_slot_min_kibibytes_per_second", properties.normalizedUploadSlotMinKbps());
            connection.put("max_connection_count", Math.max(50, properties.getMaxConnections()));
            connection.put("max_sources_per_file_count", Math.max(50, properties.getMaxSourcesPerFile()));
            connection.put("autoconnect", true);
            connection.put("reconnect_on_connection_loss", true);
            connection.put("ed2k_enabled", true);
            connection.put("kad_enabled", true);
            connection.put("extended_udp_port_enabled", true);
            connection.put("upnp_enabled", properties.isUpnpEnabled());

            Map<String, Object> files = new LinkedHashMap<>();
            files.put("ich_enabled", true);
            files.put("save_sources_for_rare_files", true);
            files.put("endgame_mode_enabled", properties.isEndgameEnabled());

            Map<String, Object> security = new LinkedHashMap<>();
            security.put("protocol_obfuscation_enabled", properties.isObfuscationEnabled());
            security.put("obfuscation_requested", properties.isObfuscationEnabled());

            Map<String, Object> servers = new LinkedHashMap<>();
            servers.put("smart_lowid_check_enabled", true);
            servers.put("update_list_from_server", true);
            servers.put("update_list_from_client", true);
            servers.put("accelerated_publication_enabled", true);

            Map<String, Object> advanced = new LinkedHashMap<>();
            advanced.put("max_new_connections_per_5_seconds", Math.max(10, Math.min(65535, properties.getMaxNewConnectionsPer5s())));
            advanced.put("kad_max_concurrent_source_search_count", properties.normalizedKadMaxSourceSearches());
            advanced.put("file_buffer_bytes", properties.normalizedEngineFileBufferBytes());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("connection", connection);
            body.put("files", files);
            body.put("security", security);
            body.put("servers", servers);
            body.put("advanced", advanced);

            String json = objectMapper.writeValueAsString(body);
            HttpCallResult res = sendAuthenticated("PATCH", "/api/v1/preferences", json);
            if (!res.isNetworkError() && (res.statusCode() == 200 || res.statusCode() == 202 || res.statusCode() == 204)) {
                this.preferencesTuned = true;
                return true;
            }
        } catch (Exception ex) {
            log.debug("Failed to tune aMule preferences: {}", ex.getMessage());
        }
        return false;
    }

    private boolean connectNetworksBestEffort(String networkSelector) {
        try {
            String selector = (networkSelector == null || networkSelector.isBlank()) ? "both" : networkSelector;
            String json = objectMapper.writeValueAsString(Map.of("network", selector));
            HttpCallResult res = sendAuthenticated("POST", "/api/v1/networks/connect", json);
            return !res.isNetworkError() && (res.statusCode() == 200 || res.statusCode() == 202 || res.statusCode() == 204);
        } catch (Exception ex) {
            log.debug("Failed to trigger network connect: {}", ex.getMessage());
            return false;
        }
    }

    private CommandOutcome patchDownloadFields(Ed2kIdentity identity, Map<String, Object> fields, String defaultErrPrefix) {
        if (!properties.isConfigured()) {
            return CommandOutcome.retryable("AMULE_NOT_CONFIGURED", "电驴引擎未启用或未配置", null);
        }
        try {
            String payload = objectMapper.writeValueAsString(fields);
            HttpCallResult res = sendAuthenticated("PATCH", "/api/v1/downloads/" + identity.md4Hash(), payload);
            if (res.isNetworkError()) {
                if (res.ioException() instanceof HttpTimeoutException) {
                    return CommandOutcome.unknown("AMULE_TIMEOUT", defaultErrPrefix + "超时，等待状态核对");
                }
                return CommandOutcome.retryable("AMULE_NETWORK_ERROR", "无法连接 aMule 服务：" + res.ioException().getMessage(), null);
            }
            if (res.statusCode() == 200 || res.statusCode() == 202 || res.statusCode() == 204) {
                return CommandOutcome.applied(identity.md4Hash());
            }
            return mapNonSuccessStatus(res, defaultErrPrefix);
        } catch (Exception ex) {
            return CommandOutcome.unknown("AMULE_PATCH_UNKNOWN", defaultErrPrefix + "：" + ex.getMessage());
        }
    }

    @Override
    public Optional<CompletedFileCandidate> inspectCompleted(Ed2kIdentity identity) {
        if (!properties.isConfigured()) {
            return Optional.empty();
        }
        try {
            HttpCallResult sharedRes = sendAuthenticated("GET", "/api/v1/shared/" + identity.md4Hash(), null);
            if (!sharedRes.isNetworkError() && sharedRes.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(sharedRes.body());
                String hash = node.path("hash").asText("").toLowerCase(Locale.ROOT);
                long sizeBytes = node.path("size_bytes").asLong(-1L);
                boolean incomplete = node.path("incomplete").asBoolean(true);
                String name = node.path("name").asText("");
                if (identity.md4Hash().equals(hash)
                        && sizeBytes == identity.fileSize()
                        && !incomplete
                        && !name.isBlank()) {
                    return Optional.of(new CompletedFileCandidate(identity.md4Hash(), identity.fileSize(), name, true));
                }
            }

            HttpCallResult dlRes = sendAuthenticated("GET", "/api/v1/downloads/" + identity.md4Hash(), null);
            if (!dlRes.isNetworkError() && dlRes.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(dlRes.body());
                String hash = node.path("hash").asText("").toLowerCase(Locale.ROOT);
                long sizeBytes = node.path("size_bytes").asLong(-1L);
                String status = node.path("status").asText("");
                String name = node.path("name").asText("");
                if (identity.md4Hash().equals(hash)
                        && sizeBytes == identity.fileSize()
                        && "completed".equalsIgnoreCase(status)
                        && !name.isBlank()) {
                    return Optional.of(new CompletedFileCandidate(identity.md4Hash(), identity.fileSize(), name, true));
                }
            }
        } catch (Exception ex) {
            log.debug("inspectCompleted failed for {}: {}", identity.md4Hash(), ex.getMessage());
        }
        return Optional.empty();
    }

    private Optional<EngineDownloadSnapshot> parseDownloadItem(String jsonBody, Ed2kIdentity expectedIdentity) {
        try {
            JsonNode node = objectMapper.readTree(jsonBody);
            return parseDownloadNode(node, expectedIdentity);
        } catch (Exception ex) {
            log.debug("Failed to parse download item JSON: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private Optional<EngineDownloadSnapshot> parseDownloadNode(JsonNode node, Ed2kIdentity expectedIdentity) {
        if (node == null || node.isMissingNode()) {
            return Optional.empty();
        }
        String hash = node.path("hash").asText("").toLowerCase(Locale.ROOT);
        long sizeBytes = node.path("size_bytes").asLong(-1L);
        if (!expectedIdentity.md4Hash().equals(hash) || sizeBytes <= 0) {
            return Optional.empty();
        }
        String name = node.path("name").asText("");
        String rawStatus = node.path("status").asText("unknown").toLowerCase(Locale.ROOT);
        long completedBytes = Math.max(0L, node.path("completed_bytes").asLong(0L));
        long speedBps = Math.max(0L, node.path("speed_bytes_per_second").asLong(0L));
        int sourcesTotal = Math.max(0, node.path("sources").path("total").asInt(0));
        int sourcesTransferring = Math.max(0, node.path("sources").path("transferring").asInt(0));
        String directory = node.path("directory").asText("");

        Ed2kResourceState mappedState = mapDownloadStatus(rawStatus);
        boolean completedFlag = "completed".equals(rawStatus);
        String errorCode = null;
        String errorMessage = null;
        if (mappedState == Ed2kResourceState.ERROR) {
            errorCode = "insufficient_disk".equals(rawStatus) ? "AMULE_INSUFFICIENT_DISK" : "AMULE_DOWNLOAD_ERROR";
            errorMessage = "insufficient_disk".equals(rawStatus) ? "aMule 磁盘空间不足" : "aMule 报告下载文件异常";
        }

        return Optional.of(new EngineDownloadSnapshot(
                hash, sizeBytes, name, rawStatus, mappedState,
                completedBytes, speedBps, sourcesTotal, sourcesTransferring,
                directory, false, completedFlag, errorCode, errorMessage
        ));
    }

    private Optional<EngineDownloadSnapshot> parseSharedItem(String jsonBody, Ed2kIdentity expectedIdentity) {
        try {
            JsonNode node = objectMapper.readTree(jsonBody);
            String hash = node.path("hash").asText("").toLowerCase(Locale.ROOT);
            long sizeBytes = node.path("size_bytes").asLong(-1L);
            boolean incomplete = node.path("incomplete").asBoolean(true);
            if (!expectedIdentity.md4Hash().equals(hash) || sizeBytes <= 0 || incomplete) {
                return Optional.empty();
            }
            String name = node.path("name").asText("");
            String directory = node.path("directory").asText("");
            return Optional.of(new EngineDownloadSnapshot(
                    hash, sizeBytes, name, "completed", Ed2kResourceState.VERIFYING,
                    sizeBytes, 0L, 0, 0,
                    directory, true, true, null, null
            ));
        } catch (Exception ex) {
            log.debug("Failed to parse shared item JSON: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public static Ed2kResourceState mapDownloadStatus(String rawStatus) {
        if (rawStatus == null) {
            return Ed2kResourceState.WAITING;
        }
        return switch (rawStatus.toLowerCase(Locale.ROOT)) {
            case "downloading" -> Ed2kResourceState.DOWNLOADING;
            case "waiting", "allocating", "unknown" -> Ed2kResourceState.WAITING;
            case "hashing", "completing", "completed" -> Ed2kResourceState.VERIFYING;
            case "paused", "stopped" -> Ed2kResourceState.PAUSED;
            case "erroneous", "insufficient_disk" -> Ed2kResourceState.ERROR;
            default -> Ed2kResourceState.WAITING;
        };
    }

    private CommandOutcome mapNonSuccessStatus(HttpCallResult res, String defaultPrefix) {
        int code = res.statusCode();
        String apiErrorCode = extractErrorCode(res.body());
        String apiErrorMessage = extractErrorMessage(res.body(), defaultPrefix + " (HTTP " + code + ")");

        if (code == 401) {
            return CommandOutcome.permanent("AMULE_AUTH_FAILED", "aMule API 认证失败，请检查 AMULE_API_PASSWORD 配置");
        }
        if (code == 403) {
            return CommandOutcome.permanent("AMULE_FORBIDDEN", "aMule API 权限不足，需要管理员令牌");
        }
        if (code == 404) {
            return CommandOutcome.retryable("AMULE_NOT_FOUND", apiErrorMessage, null);
        }
        if (code == 429) {
            return CommandOutcome.retryable("AMULE_RATE_LIMITED", "aMule API 触发限流，稍后自动重试", res.retryAfterMs());
        }
        if (code == 503 || code == 502 || code == 504) {
            return CommandOutcome.retryable(
                    apiErrorCode != null ? apiErrorCode : "AMULE_UNAVAILABLE",
                    "aMule 守护进程暂不可用：" + apiErrorMessage,
                    res.retryAfterMs()
            );
        }
        if (code >= 400 && code < 500) {
            return CommandOutcome.permanent(
                    apiErrorCode != null ? apiErrorCode : "AMULE_BAD_REQUEST",
                    apiErrorMessage
            );
        }
        return CommandOutcome.retryable(
                apiErrorCode != null ? apiErrorCode : "AMULE_SERVER_ERROR",
                apiErrorMessage,
                res.retryAfterMs()
        );
    }

    private String extractErrorCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            String code = node.path("error").path("code").asText("");
            return code.isBlank() ? null : code;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractErrorMessage(String body, String fallback) {
        if (body == null || body.isBlank()) {
            return fallback;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            String msg = node.path("error").path("message").asText("");
            return msg.isBlank() ? fallback : msg;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private HttpCallResult sendAuthenticated(String method, String path, String jsonBody) {
        String token = obtainValidToken(false);
        if (token == null) {
            return new HttpCallResult(401, "{\"error\":{\"code\":\"invalid_credentials\",\"message\":\"无法获取 aMule 登录令牌\"}}", null, null);
        }
        HttpCallResult first = sendRaw(method, path, jsonBody, token);
        if (!first.isNetworkError() && first.statusCode() == 401) {
            String refreshed = obtainValidToken(true);
            if (refreshed != null) {
                return sendRaw(method, path, jsonBody, refreshed);
            }
        }
        return first;
    }

    private synchronized String obtainValidToken(boolean forceRefresh) {
        SessionToken current = this.cachedToken;
        if (!forceRefresh && current != null && !current.isExpiredSoon()) {
            return current.token();
        }
        this.cachedToken = null;
        try {
            String body = objectMapper.writeValueAsString(Map.of("password", properties.getApiPassword()));
            HttpCallResult loginRes = sendRaw("POST", "/api/v1/auth/login?include_token=true", body, null);
            if (loginRes.isNetworkError() || loginRes.statusCode() != 200) {
                return null;
            }
            JsonNode root = objectMapper.readTree(loginRes.body());
            String token = root.path("token").asText("");
            long expiresAt = root.path("expires_at").asLong(0L);
            if (token.isBlank()) {
                return null;
            }
            SessionToken next = new SessionToken(token, expiresAt);
            this.cachedToken = next;
            return next.token();
        } catch (Exception ex) {
            log.debug("aMule login failed: {}", ex.getMessage());
            return null;
        }
    }

    private HttpCallResult sendRaw(String method, String path, String jsonBody, String bearerToken) {
        String base = properties.getApiBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI uri = URI.create(base + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.max(500, properties.getHttpRequestTimeoutMs())))
                .header("Accept", "application/json");

        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        if (jsonBody != null) {
            builder.header("Content-Type", "application/json; charset=utf-8");
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        try {
            HttpResponse<String> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            Long retryAfterMs = parseRetryAfterMs(response);
            return new HttpCallResult(response.statusCode(), response.body(), retryAfterMs, null);
        } catch (IOException ex) {
            return new HttpCallResult(0, "", null, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new HttpCallResult(0, "", null, new IOException("Interrupted while calling aMule API", ex));
        }
    }

    private Long parseRetryAfterMs(HttpResponse<?> response) {
        Optional<String> header = response.headers().firstValue("Retry-After");
        if (header.isEmpty()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.get().trim());
            return Math.max(0L, seconds * 1000L);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
