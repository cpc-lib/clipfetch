package cc.ivera.magnet.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.client.QBittorrentClient;
import cc.ivera.magnet.domain.MagnetHistory;
import cc.ivera.magnet.domain.MagnetHistoryMapper;
import cc.ivera.shared.web.ApiResponse;
import cc.ivera.shared.web.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 磁力下载：解析磁力链接元数据（qBittorrent）、解析历史、下载任务管理。
 * 下载任务状态以 qBittorrent 为唯一事实来源，本表仅存解析历史。
 */
@Slf4j
@RestController
@RequestMapping("/api/magnet")
@RequiredArgsConstructor
public class MagnetController {

    private static final Pattern XT_BTIH = Pattern.compile("xt=urn:btih:([^&]+)", Pattern.CASE_INSENSITIVE);
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String STATUS_PARSING = "PARSING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";

    private final MagnetHistoryMapper magnetHistoryMapper;
    private final QBittorrentClient qbt;
    private final ObjectMapper objectMapper;

    /** 解析为异步长任务（元数据最长等待约 60 秒），独立线程池执行，随 JVM 退出 */
    private final ExecutorService parseExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "magnet-parse");
        t.setDaemon(true);
        return t;
    });

    // ==================== 解析 ====================

    /**
     * 提交磁力解析。同用户同 Info Hash：已成功直接返回缓存；解析中返回原任务；失败后重新提交会复用该行。
     */
    @PostMapping("/parse")
    public ApiResponse<MagnetHistory> parse(HttpServletRequest request, @RequestBody ParseReq req) {
        Long userId = currentUserId(request);
        String magnet = req.getMagnetUri() == null ? "" : req.getMagnetUri().trim();
        if (!magnet.startsWith("magnet:?")) {
            throw new BusinessException("请输入合法的磁力链接（magnet:?xt=...）");
        }
        String hash = extractInfoHash(magnet);
        if (hash == null) {
            throw new BusinessException("无法从磁力链接中识别 Info Hash（需包含 urn:btih）");
        }
        MagnetHistory existing = magnetHistoryMapper.selectOne(new QueryWrapper<MagnetHistory>()
                .eq("user_id", userId).eq("info_hash", hash));
        MagnetHistory row;
        if (existing != null) {
            if (STATUS_SUCCEEDED.equals(existing.getStatus()) || STATUS_PARSING.equals(existing.getStatus())) {
                return ApiResponse.ok(existing);
            }
            // 失败记录复用原行重新解析
            existing.setStatus(STATUS_PARSING);
            existing.setError(null);
            existing.setUpdatedAt(LocalDateTime.now());
            magnetHistoryMapper.updateById(existing);
            row = existing;
        } else {
            row = MagnetHistory.builder()
                    .userId(userId)
                    .infoHash(hash)
                    .magnetUri(magnet)
                    .status(STATUS_PARSING)
                    .deleted(0L)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            magnetHistoryMapper.insert(row);
        }
        Long parseId = row.getId();
        parseExecutor.submit(() -> doParse(parseId, magnet, hash));
        return ApiResponse.ok(row);
    }

    /** 查询解析状态及完整文件列表（前端每 2 秒轮询直至 SUCCEEDED/FAILED） */
    @GetMapping("/parse/{id}")
    public ApiResponse<MagnetHistory> parseDetail(HttpServletRequest request, @PathVariable Long id) {
        return ApiResponse.ok(ownedRecord(request, id));
    }

    /**
     * 异步解析：添加种子（启动态）→ 等元数据 → 获取文件列表 → 暂停 → 保存历史。
     */
    private void doParse(Long parseId, String magnet, String hash) {
        try {
            if (!qbt.torrentExists(hash)) {
                // addTorrent 现在是启动态，直接添加即可获取元数据
                qbt.addTorrent(magnet);
            }
            // 确保种子处于启动态以获取元数据（已存在但暂停的情况）
            if (!qbt.hasMetadata(hash)) {
                qbt.startTorrent(hash);
                qbt.waitMetadata(hash);
            }
            // 取到元数据后立即暂停，避免开始下载
            qbt.stopTorrent(hash);
            JsonNode info = qbt.torrentInfo(hash);
            List<JsonNode> files = qbt.torrentFiles(hash);
            // 确保暂停（异常路径兜底）
            qbt.stopTorrent(hash);

            List<Map<String, Object>> entries = new ArrayList<>();
            long totalSize = 0;
            for (JsonNode f : files) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("index", f.path("index").asInt());
                e.put("path", f.path("name").asText());
                long size = f.path("size").asLong();
                e.put("size", size);
                totalSize += size;
                entries.add(e);
            }
            MagnetHistory row = magnetHistoryMapper.selectById(parseId);
            if (row != null) {
                row.setName(info.path("name").asText(null));
                row.setTotalSize(totalSize);
                row.setFilesJson(objectMapper.writeValueAsString(entries));
                row.setStatus(STATUS_SUCCEEDED);
                row.setError(null);
                row.setUpdatedAt(LocalDateTime.now());
                magnetHistoryMapper.updateById(row);
            }
        } catch (BusinessException e) {
            fail(parseId, hash, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(parseId, hash, "解析被中断");
        } catch (Exception e) {
            log.warn("磁力解析失败 hash={}: {}", hash, e.getMessage());
            fail(parseId, hash, "解析失败：" + e.getMessage());
        }
    }

    private void fail(Long parseId, String hash, String message) {
        // 解析失败时尽量暂停已添加的种子，避免后台持续下载
        try {
            qbt.stopTorrent(hash);
        } catch (Exception ignored) {
        }
        MagnetHistory row = magnetHistoryMapper.selectById(parseId);
        if (row != null) {
            row.setStatus(STATUS_FAILED);
            row.setError(message == null ? "解析失败" : message);
            row.setUpdatedAt(LocalDateTime.now());
            magnetHistoryMapper.updateById(row);
        }
    }

    // ==================== 解析历史 ====================

    /** 分页查询解析历史（按 user_id 隔离，时间倒序；keyword 模糊匹配种子名称） */
    @GetMapping("/history")
    public ApiResponse<Page<MagnetHistory>> history(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        Long userId = currentUserId(request);
        QueryWrapper<MagnetHistory> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        if (keyword != null && !keyword.isBlank()) {
            qw.like("name", keyword.trim());
        }
        qw.orderByDesc("created_at");
        size = Math.min(Math.max(size, 1), 30);
        return ApiResponse.ok(magnetHistoryMapper.selectPage(new Page<>(page, size), qw));
    }

    /** 逻辑删除指定解析历史（deleted=id，不影响实际下载任务） */
    @DeleteMapping("/history/{id}")
    public ApiResponse<Void> deleteHistory(HttpServletRequest request, @PathVariable Long id) {
        Long userId = currentUserId(request);
        if (magnetHistoryMapper.logicalDeleteById(id, userId) == 0) {
            throw new BusinessException("记录不存在");
        }
        return ApiResponse.ok(null);
    }

    // ==================== 下载管理 ====================

    /**
     * 创建下载：按解析记录勾选的文件设置优先级并开始任务。
     * 每个下载任务保存到独立 UUID 子目录（/downloads/{uuid}），避免同名文件冲突。
     * 种子可能仍存在于 qBittorrent（解析后暂停），存在则直接复用，避免重复添加。
     */
    @PostMapping("/downloads")
    public ApiResponse<Map<String, String>> createDownload(HttpServletRequest request, @RequestBody DownloadReq req) {
        Long userId = currentUserId(request);
        MagnetHistory record = magnetHistoryMapper.selectOne(new QueryWrapper<MagnetHistory>()
                .eq("id", req.getParseId()).eq("user_id", userId));
        if (record == null) {
            throw new BusinessException("解析记录不存在");
        }
        if (!STATUS_SUCCEEDED.equals(record.getStatus()) || record.getFilesJson() == null) {
            throw new BusinessException("该记录尚未解析成功，无法创建下载");
        }
        List<Integer> selected = req.getSelectedFileIndexes();
        if (selected == null || selected.isEmpty()) {
            throw new BusinessException("请至少选择一个文件");
        }
        List<Integer> all = new ArrayList<>();
        for (JsonNode f : parseFiles(record.getFilesJson())) {
            all.add(f.path("index").asInt());
        }
        for (Integer idx : selected) {
            if (idx == null || !all.contains(idx)) {
                throw new BusinessException("文件索引不合法：" + idx);
            }
        }
        String hash = record.getInfoHash();
        // 生成 UUID 子目录，格式如 /downloads/550e8400-e29b-41d4-a716-446655440000
        String taskUuid = UUID.randomUUID().toString();
        String containerSavePath = qbt.getSavePath() + "/" + taskUuid;
        if (!qbt.torrentExists(hash)) {
            // 种子不存在：以启动态添加，设置 UUID 保存路径，等元数据后设置优先级再启动
            qbt.addTorrent(record.getMagnetUri(), containerSavePath);
            if (!qbt.hasMetadata(hash)) {
                try {
                    qbt.waitMetadata(hash);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException("下载任务被中断");
                }
            }
            // 新添加的种子：设置文件优先级、启动、禁用做种
            qbt.setFilePriority(hash, all, 0);
            qbt.setFilePriority(hash, selected, 1);
            qbt.startTorrent(hash);
            qbt.disableSeeding(hash);
        } else {
            // 种子已存在：复用现有种子，不改保存路径（避免影响他人做种），只设置优先级并启动
            if (!qbt.hasMetadata(hash)) {
                qbt.startTorrent(hash);
                try {
                    qbt.waitMetadata(hash);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException("下载任务被中断");
                }
            }
            qbt.setFilePriority(hash, all, 0);
            qbt.setFilePriority(hash, selected, 1);
            qbt.startTorrent(hash);
            // 已存在的种子可能是别人在做种，不修改做种限制
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("taskUuid", taskUuid);
        data.put("savePath", containerSavePath);
        return ApiResponse.ok(data);
    }

    /** 当前下载任务及实时进度（qBittorrent 实际状态，lazy sync） */
    @GetMapping("/downloads")
    public ApiResponse<List<DownloadItem>> downloads(HttpServletRequest request) {
        currentUserId(request);
        List<DownloadItem> items = new ArrayList<>();
        for (JsonNode t : qbt.listTorrents()) {
            double progress = t.path("progress").asDouble(0) * 100;
            items.add(new DownloadItem(
                    t.path("hash").asText(),
                    t.path("name").asText(),
                    t.path("state").asText(),
                    Math.round(progress * 10) / 10.0,
                    t.path("downloaded").asLong(0),
                    t.path("size").asLong(0),
                    t.path("dlspeed").asLong(0),
                    t.path("eta").asLong(0),
                    t.path("save_path").asText("")
            ));
        }
        return ApiResponse.ok(items);
    }

    @PostMapping("/downloads/{hash}/start")
    public ApiResponse<Void> startDownload(HttpServletRequest request, @PathVariable String hash) {
        currentUserId(request);
        qbt.startTorrent(hash);
        return ApiResponse.ok(null);
    }

    @PostMapping("/downloads/{hash}/pause")
    public ApiResponse<Void> pauseDownload(HttpServletRequest request, @PathVariable String hash) {
        currentUserId(request);
        qbt.stopTorrent(hash);
        return ApiResponse.ok(null);
    }

    /** 删除任务：默认保留已下载文件，deleteFiles=true 时同时删除文件 */
    @DeleteMapping("/downloads/{hash}")
    public ApiResponse<Void> deleteDownload(
            HttpServletRequest request,
            @PathVariable String hash,
            @RequestParam(defaultValue = "false") boolean deleteFiles) {
        currentUserId(request);
        qbt.deleteTorrent(hash, deleteFiles);
        return ApiResponse.ok(null);
    }

    // ==================== 浏览器下载 ====================

    /**
     * 列出指定下载任务已完成的文件（供浏览器下载）。
     * 从 qBittorrent 获取文件列表，筛选出 progress=1（已完成）的文件。
     */
    @GetMapping("/downloads/{hash}/files")
    public ApiResponse<List<Map<String, Object>>> listCompletedFiles(
            HttpServletRequest request,
            @PathVariable String hash) {
        currentUserId(request);
        List<JsonNode> files = qbt.torrentFiles(hash);
        List<Map<String, Object>> completed = new ArrayList<>();
        for (JsonNode f : files) {
            double progress = f.path("progress").asDouble(0);
            if (progress >= 1.0) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", f.path("index").asInt());
                item.put("path", f.path("name").asText());
                item.put("size", f.path("size").asLong());
                completed.add(item);
            }
        }
        return ApiResponse.ok(completed);
    }

    /**
     * 浏览器下载已完成的文件。
     * 从 qBittorrent 容器路径映射到宿主机路径，读取文件流式返回。
     */
    @GetMapping("/downloads/{hash}/file")
    public ResponseEntity<Resource> downloadFile(
            HttpServletRequest request,
            @PathVariable String hash,
            @RequestParam String filePath) throws IOException {
        currentUserId(request);
        // 获取种子的保存路径（容器内路径，如 /downloads/uuid）
        JsonNode info = qbt.torrentInfo(hash);
        String containerSavePath = info.path("save_path").asText();
        // 映射到宿主机路径：D:/BaiduNetdiskDownload/uuid
        String hostSavePath = mapToHostPath(containerSavePath);
        // 构建完整文件路径：D:/BaiduNetdiskDownload/uuid/xxx/yyy.mkv
        Path fullPath = Path.of(hostSavePath, filePath);
        File file = fullPath.toFile();
        if (!file.exists() || !file.isFile()) {
            throw new BusinessException("文件不存在或尚未下载完成");
        }
        // 安全检查：确保请求的文件在保存目录内，防止路径遍历攻击
        String canonicalSavePath = new File(hostSavePath).getCanonicalPath();
        String canonicalFilePath = file.getCanonicalPath();
        if (!canonicalFilePath.startsWith(canonicalSavePath)) {
            throw new BusinessException("非法文件路径");
        }
        Resource resource = new FileSystemResource(file);
        String filename = file.getName();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFilename + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.length()))
                .body(resource);
    }

    /**
     * 将 qBittorrent 容器内路径映射到宿主机路径。
     * 例如：/downloads/uuid → D:/BaiduNetdiskDownload/uuid
     */
    private String mapToHostPath(String containerPath) {
        String hostDir = qbt.getDownloadsHostDir();
        if (hostDir == null || hostDir.isBlank()) {
            throw new BusinessException("未配置 QBT_DOWNLOADS_HOST_DIR，无法提供浏览器下载");
        }
        String basePath = qbt.getSavePath(); // 如 /downloads
        if (containerPath.startsWith(basePath)) {
            String relative = containerPath.substring(basePath.length());
            return hostDir + relative;
        }
        return hostDir + "/" + containerPath;
    }

    /** 引擎健康检查：返回 Web API 版本号与配置的保存路径 */
    @GetMapping("/engine/health")
    public ApiResponse<Map<String, String>> engineHealth(HttpServletRequest request) {
        currentUserId(request);
        Map<String, String> data = new LinkedHashMap<>();
        data.put("version", qbt.version());
        data.put("savePath", qbt.getSavePath());
        return ApiResponse.ok(data);
    }

    // ==================== 内部工具 ====================

    private MagnetHistory ownedRecord(HttpServletRequest request, Long id) {
        Long userId = currentUserId(request);
        MagnetHistory record = magnetHistoryMapper.selectOne(new QueryWrapper<MagnetHistory>()
                .eq("id", id).eq("user_id", userId));
        if (record == null) {
            throw new BusinessException("记录不存在");
        }
        return record;
    }

    /** 提取磁力链接的 btih Info Hash：40 位十六进制直接用，32 位 Base32 解码为十六进制 */
    private String extractInfoHash(String magnet) {
        Matcher m = XT_BTIH.matcher(magnet);
        if (!m.find()) {
            return null;
        }
        String v = m.group(1).trim();
        if (v.length() == 40 && v.matches("[0-9A-Fa-f]{40}")) {
            return v.toLowerCase();
        }
        if (v.length() == 32 && v.matches("[A-Za-z2-7]{32}")) {
            return base32ToHex(v);
        }
        return null;
    }

    private String base32ToHex(String s) {
        int buffer = 0;
        int bits = 0;
        StringBuilder hex = new StringBuilder();
        for (char c : s.toUpperCase().toCharArray()) {
            int val = BASE32.indexOf(c);
            if (val < 0) {
                return null;
            }
            buffer = (buffer << 5) | val;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                hex.append(String.format("%02x", (buffer >> bits) & 0xFF));
            }
        }
        return hex.length() == 40 ? hex.toString() : null;
    }

    private List<JsonNode> parseFiles(String filesJson) {
        try {
            List<JsonNode> list = new ArrayList<>();
            objectMapper.readTree(filesJson).forEach(list::add);
            return list;
        } catch (Exception e) {
            throw new BusinessException("文件清单解析失败，请重新解析");
        }
    }

    private Long currentUserId(HttpServletRequest request) {
        User user = (User) request.getAttribute(AuthInterceptor.ATTR_USER);
        return user.getId();
    }

    @Data
    public static class ParseReq {
        private String magnetUri;
    }

    @Data
    public static class DownloadReq {
        private Long parseId;
        private List<Integer> selectedFileIndexes;
    }

    /** 下载进度条目（进度 0~100，速度 B/s，剩余秒数，eta=-1 表示未知） */
    public record DownloadItem(
            String infoHash,
            String name,
            String state,
            double progressPercent,
            long downloadedBytes,
            long totalBytes,
            long downloadSpeedBytesPerSec,
            long etaSeconds,
            String savePath) {
    }
}
