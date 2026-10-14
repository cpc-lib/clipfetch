package cc.ivera.magnet.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.client.QBittorrentClient;
import cc.ivera.magnet.client.RemoteTorrentFileGateway;
import cc.ivera.magnet.application.DownloadTicketStore;
import cc.ivera.magnet.domain.MagnetHistory;
import cc.ivera.magnet.domain.MagnetHistoryMapper;
import cc.ivera.magnet.domain.MagnetDownloadTask;
import cc.ivera.magnet.domain.MagnetDownloadTaskMapper;
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
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 磁力下载：解析磁力链接元数据（qBittorrent）、解析历史、下载任务管理。
 * 解析历史与下载任务归属分别持久化；进度由 qBittorrent 提供。
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
    /** 上传 .torrent 文件大小上限（与 qBittorrent 恢复缓存上限一致） */
    private static final long MAX_TORRENT_BYTES = 8L * 1024 * 1024;

    private final MagnetHistoryMapper magnetHistoryMapper;
    private final MagnetDownloadTaskMapper downloadTaskMapper;
    private final QBittorrentClient qbt;
    private final ObjectMapper objectMapper;
    private final DownloadTicketStore ticketStore;
    private final RemoteTorrentFileGateway remoteFileGateway;
    private final ConcurrentHashMap<String, Object> torrentLocks = new ConcurrentHashMap<>();

    private Object torrentLock(String hash) {
        return torrentLocks.computeIfAbsent(hash.toLowerCase(Locale.ROOT), ignored -> new Object());
    }

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
            if (STATUS_PARSING.equals(existing.getStatus())) {
                return ApiResponse.ok(existing);
            }
            if (STATUS_SUCCEEDED.equals(existing.getStatus()) && !req.isForce()) {
                if (existing.getTorrentFileB64() != null && !existing.getTorrentFileB64().isBlank()) {
                    return ApiResponse.ok(existing);
                }
                // 旧数据没有持久化 .torrent；若客户端仍有元数据，直接补充缓存。
                if (qbt.hasMetadata(hash)) {
                    existing.setTorrentFileB64(Base64.getEncoder().encodeToString(qbt.exportTorrent(hash)));
                    existing.setUpdatedAt(LocalDateTime.now());
                    magnetHistoryMapper.updateById(existing);
                    return ApiResponse.ok(existing);
                }
            }
            // 已过期的成功记录不能永久命中缓存；重新获取完整元数据。
            existing.setStatus(STATUS_PARSING);
            existing.setTorrentFileB64(null);
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
        parseExecutor.submit(() -> doParse(parseId, magnet, hash, null));
        return ApiResponse.ok(row);
    }

    /**
     * 上传 .torrent 文件解析（与磁力链接等价的提交方式）。
     * 直接由文件字节算出 Info Hash，并把完整元数据存入解析历史，无需依赖 Peer 下载元数据。
     */
    @PostMapping(value = "/parse-torrent", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MagnetHistory> parseTorrent(HttpServletRequest request,
                                                   @RequestParam("file") MultipartFile file) {
        Long userId = currentUserId(request);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要解析的种子文件（.torrent）");
        }
        if (file.getSize() > MAX_TORRENT_BYTES) {
            throw new BusinessException("种子文件过大（超过 8MB）");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException("读取种子文件失败：" + e.getMessage());
        }
        String hash = torrentInfoHashHex(bytes);
        String b64 = Base64.getEncoder().encodeToString(bytes);

        MagnetHistory existing = magnetHistoryMapper.selectOne(new QueryWrapper<MagnetHistory>()
                .eq("user_id", userId).eq("info_hash", hash));
        MagnetHistory row;
        if (existing != null) {
            if (STATUS_PARSING.equals(existing.getStatus())) {
                return ApiResponse.ok(existing);
            }
            if (STATUS_SUCCEEDED.equals(existing.getStatus())) {
                // 同一 Info Hash 内容完全一致，直接复用；旧记录缺失缓存时用本次字节补齐
                if (existing.getTorrentFileB64() == null || existing.getTorrentFileB64().isBlank()) {
                    existing.setTorrentFileB64(b64);
                    existing.setUpdatedAt(LocalDateTime.now());
                    magnetHistoryMapper.updateById(existing);
                }
                return ApiResponse.ok(existing);
            }
            existing.setStatus(STATUS_PARSING);
            existing.setError(null);
            existing.setTorrentFileB64(b64);
            existing.setUpdatedAt(LocalDateTime.now());
            magnetHistoryMapper.updateById(existing);
            row = existing;
        } else {
            row = MagnetHistory.builder()
                    .userId(userId)
                    .infoHash(hash)
                    .magnetUri("")
                    .status(STATUS_PARSING)
                    .deleted(0L)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            magnetHistoryMapper.insert(row);
        }
        Long parseId = row.getId();
        parseExecutor.submit(() -> doParse(parseId, "", hash, bytes));
        return ApiResponse.ok(row);
    }

    /** 查询解析状态及完整文件列表（前端每 2 秒轮询直至 SUCCEEDED/FAILED） */
    @GetMapping("/parse/{id}")
    public ApiResponse<MagnetHistory> parseDetail(HttpServletRequest request, @PathVariable Long id) {
        return ApiResponse.ok(ownedRecord(request, id));
    }

    /**
     * 异步解析：添加种子（磁力链接启动态 / .torrent 文件暂停态导入）→ 等元数据 →
     * 获取文件列表 → 暂停 → 保存历史。
     */
    private void doParse(Long parseId, String magnet, String hash, byte[] torrentBytes) {
        synchronized (torrentLock(hash)) {
            boolean mayPause = false;
            try {
                boolean exists = qbt.torrentExists(hash);
                if (!exists) {
                    if (torrentBytes != null) {
                        // 由 .torrent 文件导入：元数据完整，无需连接 Peer
                        if (qbt.getSavePath() == null || qbt.getSavePath().isBlank()) {
                            throw new BusinessException("请先配置 QBT 保存根目录（qbt-savepath）");
                        }
                        qbt.addTorrentFile(torrentBytes, qbt.getSavePath());
                    } else {
                        qbt.addTorrent(magnet);
                    }
                    mayPause = true;
                } else if (!qbt.hasMetadata(hash)) {
                    // 不得启动其他用户已存在的任务或外部 qBittorrent 种子。
                    if (taskByHash(hash) != null || !qbt.isClipfetchParseTorrent(hash)) {
                        throw new BusinessException("已有种子尚未取得元数据，不能操作非解析种子");
                    }
                    qbt.startTorrent(hash);
                    mayPause = true;
                }
                if (!qbt.hasMetadata(hash)) {
                    if (!mayPause) throw new BusinessException("该种子尚未获取元数据");
                    qbt.waitMetadata(hash);
                }
                JsonNode info = qbt.torrentInfo(hash);
                List<JsonNode> files = qbt.torrentFiles(hash);
                if (files.isEmpty()) throw new BusinessException("元数据尚未包含文件列表");

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
                    // 仅文件清单不足以恢复种子，必须存储完整的 .torrent。
                    row.setTorrentFileB64(Base64.getEncoder().encodeToString(qbt.exportTorrent(hash)));
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
            } finally {
                // 只暂停本次确实启动的解析种子，绝不能暂停已有下载任务。
                if (mayPause && taskByHash(hash) == null) {
                    try { qbt.stopTorrent(hash); } catch (Exception e) {
                        log.warn("暂停解析种子失败 hash={}: {}", hash, e.getMessage());
                    }
                }
            }
        }
    }

    private void fail(Long parseId, String hash, String message) {
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
     * 只允许复用带 clipfetch-parse 标记、且尚未被其他业务任务占用的种子。
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
        if (qbt.getSavePath() == null || qbt.getSavePath().isBlank()) {
            throw new BusinessException("请先配置 QBT 保存根目录（qbt-savepath）");
        }
        if (!selected.stream().allMatch(Objects::nonNull) || new HashSet<>(selected).size() != selected.size()) {
            throw new BusinessException("文件索引重复或包含空值");
        }
        synchronized (torrentLock(hash)) {
            MagnetDownloadTask existing = taskByHash(hash);
            if (existing != null) {
                if (!userId.equals(existing.getUserId())) {
                    throw new BusinessException("该磁力已被其他下载任务占用，无法独立设置文件或目录");
                }
                if (!qbt.torrentExists(hash)) {
                    throw new BusinessException("任务记录存在，但 qBittorrent 种子已缺失，请先处理异常任务");
                }
                checkedTaskInfo(existing);
                if (!selectedIndexes(existing).equals(new HashSet<>(selected))) {
                    throw new BusinessException("该磁力已创建下载任务，文件选择不能在创建接口中修改");
                }
                Map<String, String> data = new LinkedHashMap<>();
                data.put("taskUuid", existing.getTaskUuid());
                data.put("savePath", existing.getSavePath());
                return ApiResponse.ok(data);
            }
            String taskUuid = UUID.randomUUID().toString();
            String containerSavePath = qbt.getSavePath().replaceAll("/+$", "") + "/" + taskUuid;
            boolean torrentExists = qbt.torrentExists(hash);
            if (torrentExists && !qbt.isClipfetchParseTorrent(hash)) {
                throw new BusinessException("qBittorrent 已有同 Hash 种子且不属于 ClipFetch 解析任务，禁止接管");
            }
            if (!torrentExists || !qbt.hasMetadata(hash)) {
                // 已有文件名不代表客户端拥有种子元数据。没有完整备份时不得等待 60 秒假装能恢复。
                if (record.getTorrentFileB64() == null || record.getTorrentFileB64().isBlank()) {
                    throw new BusinessException("需要重新解析：数据库只有旧文件清单，qBittorrent 已失去元数据。请点击“重新解析”，等待可用 Peer 后再下载");
                }
                byte[] torrentBytes;
                try {
                    torrentBytes = Base64.getDecoder().decode(record.getTorrentFileB64());
                } catch (IllegalArgumentException e) {
                    throw new BusinessException("种子缓存已损坏，请重新解析");
                }
                if (torrentExists) {
                    JsonNode stale = qbt.torrentInfo(hash);
                    if (stale.path("downloaded").asLong(0) > 0 || stale.path("progress").asDouble(0) > 0) {
                        throw new BusinessException("原种子存在部分下载数据，拒绝自动替换，请人工确认");
                    }
                    // 只删除已识别的本应用解析种子记录，不删除任何已下载文件。
                    qbt.deleteTorrent(hash, false);
                }
                qbt.addTorrentFile(torrentBytes, containerSavePath);
                qbt.waitImportedMetadata(hash);
            }
            qbt.stopTorrent(hash);
            List<JsonNode> liveFiles = qbt.torrentFiles(hash);
            Map<Integer, JsonNode> liveById = new HashMap<>();
            for (JsonNode f : liveFiles) liveById.put(f.path("index").asInt(), f);
            for (JsonNode f : parseFiles(record.getFilesJson())) {
                JsonNode live = liveById.get(f.path("index").asInt());
                if (live == null || !live.path("name").asText().equals(f.path("path").asText())
                        || live.path("size").asLong() != f.path("size").asLong()) {
                    throw new BusinessException("当前文件清单与解析历史不一致，请重新解析");
                }
            }
            if (liveById.size() != all.size()) throw new BusinessException("文件数量已变化，请重新解析");

            // 对应用解析种子重新设置真实目录，确认移动完成后再启动。
            qbt.setSavePath(hash, containerSavePath);
            qbt.waitForSavePath(hash, containerSavePath);
            List<Integer> skipped = new ArrayList<>(all);
            skipped.removeAll(selected);
            qbt.setFilePriority(hash, skipped, 0);
            qbt.setFilePriority(hash, selected, 1);
            qbt.disableSeeding(hash);
            qbt.markManaged(hash);

            MagnetDownloadTask task = new MagnetDownloadTask();
            task.setTaskUuid(taskUuid);
            task.setUserId(userId);
            task.setParseId(record.getId());
            task.setInfoHash(hash);
            task.setSavePath(containerSavePath);
            try {
                task.setSelectedFilesJson(objectMapper.writeValueAsString(selected));
            } catch (Exception e) {
                throw new BusinessException("序列化已选文件索引失败");
            }
            task.setCreatedAt(LocalDateTime.now());
            // 先落库再启动；启动失败时仍保留一个可恢复的暂停任务。
            downloadTaskMapper.insert(task);
            qbt.startTorrent(hash);
            Map<String, String> data = new LinkedHashMap<>();
            data.put("taskUuid", taskUuid);
            data.put("savePath", qbt.torrentInfo(hash).path("save_path").asText());
            return ApiResponse.ok(data);
        }
    }

    /** 只显示该用户持有的种子，避免泄漏 qBittorrent 实例中其他用户的任务。 */
    @GetMapping("/downloads")
    public ApiResponse<List<DownloadItem>> downloads(HttpServletRequest request) {
        Long userId = currentUserId(request);
        List<MagnetDownloadTask> owned = downloadTaskMapper.selectList(
                new QueryWrapper<MagnetDownloadTask>().eq("user_id", userId).orderByDesc("created_at"));
        Map<String, JsonNode> live = new HashMap<>();
        for (JsonNode t : qbt.listTorrents()) live.put(t.path("hash").asText().toLowerCase(Locale.ROOT), t);
        List<DownloadItem> items = new ArrayList<>();
        for (MagnetDownloadTask task : owned) {
            JsonNode t = live.get(task.getInfoHash().toLowerCase(Locale.ROOT));
            if (t == null || !sameSavePath(t.path("save_path").asText(""), task.getSavePath())) continue;
            double progress = t.path("progress").asDouble(0) * 100;
            items.add(new DownloadItem(
                    task.getInfoHash(),
                    t.path("name").asText(),
                    t.path("state").asText(),
                    Math.round(progress * 10) / 10.0,
                    t.path("downloaded").asLong(0),
                    t.path("size").asLong(0),
                    t.path("dlspeed").asLong(0),
                    t.path("eta").asLong(0),
                    task.getSavePath()
            ));
        }
        return ApiResponse.ok(items);
    }

    @PostMapping("/downloads/{hash}/start")
    public ApiResponse<Void> startDownload(HttpServletRequest request, @PathVariable String hash) {
        Long userId = currentUserId(request);
        synchronized (torrentLock(hash)) {
            checkedTaskInfo(requireOwnedTask(userId, hash));
            qbt.startTorrent(hash);
        }
        return ApiResponse.ok(null);
    }

    @PostMapping("/downloads/{hash}/pause")
    public ApiResponse<Void> pauseDownload(HttpServletRequest request, @PathVariable String hash) {
        Long userId = currentUserId(request);
        synchronized (torrentLock(hash)) {
            checkedTaskInfo(requireOwnedTask(userId, hash));
            qbt.stopTorrent(hash);
        }
        return ApiResponse.ok(null);
    }

    /** 只允许任务所有者删除，先确认种子的真实保存目录没被改变。 */
    @DeleteMapping("/downloads/{hash}")
    public ApiResponse<Void> deleteDownload(
            HttpServletRequest request,
            @PathVariable String hash,
            @RequestParam(defaultValue = "false") boolean deleteFiles) {
        Long userId = currentUserId(request);
        synchronized (torrentLock(hash)) {
            MagnetDownloadTask task = requireOwnedTask(userId, hash);
            if (qbt.torrentExists(hash)) {
                checkedTaskInfo(task);
                qbt.deleteTorrent(hash, deleteFiles);
            }
            downloadTaskMapper.deleteById(task.getTaskUuid());
            ticketStore.revokeTorrent(hash);
        }
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
        MagnetDownloadTask task = requireOwnedTask(currentUserId(request), hash);
        checkedTaskInfo(task);
        List<JsonNode> files = qbt.torrentFiles(hash);
        Set<Integer> selected = selectedIndexes(task);
        List<Map<String, Object>> completed = new ArrayList<>();
        for (JsonNode f : files) {
            double progress = f.path("progress").asDouble(0);
            if (progress >= 1.0 && selected.contains(f.path("index").asInt())) {
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
     * First issue a short-lived browser download ticket using normal Bearer auth.
     * Browser navigation never receives the JWT and downloads directly to disk.
     */
    @PostMapping("/downloads/{hash}/file-ticket")
    public ApiResponse<Map<String, String>> createFileTicket(
            HttpServletRequest request, @PathVariable String hash, @RequestBody FileTicketReq req) {
        MagnetDownloadTask task = requireOwnedTask(currentUserId(request), hash);
        checkedTaskInfo(task);
        if (req == null || req.getFileIndex() == null || !selectedIndexes(task).contains(req.getFileIndex())) {
            throw new BusinessException("文件不属于当前下载任务");
        }
        completedFile(task, req.getFileIndex());
        remoteFileGateway.assertConfigured();
        String token = ticketStore.create(task.getUserId(), task.getInfoHash(), req.getFileIndex());
        return ApiResponse.ok(Map.of("url", "/api/magnet/downloads/stream/" + token));
    }

    /**
     * Native-browser GET, intentionally outside the JWT interceptor.
     * Strong random ticket + user/task/file binding is the only credential;
     * every request revalidates task ownership, save_path and completion.
     * Range is forwarded to the remote HTTPS file gateway.
     */
    @GetMapping("/downloads/stream/{ticket}")
    public void streamBrowserDownload(
            @PathVariable String ticket,
            @RequestHeader(value = "Range", required = false) String range,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        DownloadTicketStore.Ticket capability = ticketStore.require(ticket);
        MagnetDownloadTask task = requireOwnedTask(capability.userId(), capability.hash());
        JsonNode info = checkedTaskInfo(task);
        JsonNode file = completedFile(task, capability.fileIndex());
        String relative = remoteFileGateway.relativePath(
                qbt.getSavePath(), info.path("save_path").asText(), file.path("name").asText());
        String fileName = file.path("name").asText().replace('\\', '/');
        fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
        remoteFileGateway.stream(relative, range, fileName, response);
    }

    private JsonNode completedFile(MagnetDownloadTask task, int index) {
        if (!selectedIndexes(task).contains(index)) throw new BusinessException("无权访问该文件");
        for (JsonNode file : qbt.torrentFiles(task.getInfoHash())) {
            if (file.path("index").asInt(-1) == index && file.path("progress").asDouble(0) >= 1.0) {
                return file;
            }
        }
        throw new BusinessException("文件不存在或尚未下载完成");
    }

    @Data
    public static class FileTicketReq {
        private Integer fileIndex;
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

    // ==================== 下载归属校验 ====================

    private MagnetDownloadTask taskByHash(String hash) {
        if (hash == null || !hash.matches("(?i)[0-9a-f]{40}")) {
            throw new BusinessException("非法 Info Hash");
        }
        return downloadTaskMapper.selectOne(new QueryWrapper<MagnetDownloadTask>()
                .eq("info_hash", hash.toLowerCase(Locale.ROOT)));
    }

    private MagnetDownloadTask requireOwnedTask(Long userId, String hash) {
        MagnetDownloadTask task = taskByHash(hash);
        if (task == null || !userId.equals(task.getUserId())) {
            throw new BusinessException("下载任务不存在或无权访问");
        }
        return task;
    }

    private boolean sameSavePath(String actual, String expected) {
        if (actual == null || expected == null) return false;
        return actual.replace('\\', '/').replaceAll("/+$", "")
                .equalsIgnoreCase(expected.replace('\\', '/').replaceAll("/+$", ""));
    }

    private JsonNode checkedTaskInfo(MagnetDownloadTask task) {
        JsonNode info = qbt.torrentInfo(task.getInfoHash());
        if (!sameSavePath(info.path("save_path").asText(""), task.getSavePath())) {
            throw new BusinessException("实际保存路径与任务记录不一致，已拒绝操作");
        }
        return info;
    }

    private Set<Integer> selectedIndexes(MagnetDownloadTask task) {
        try {
            Set<Integer> ids = new HashSet<>();
            for (JsonNode value : objectMapper.readTree(task.getSelectedFilesJson())) ids.add(value.asInt());
            return ids;
        } catch (Exception e) {
            throw new BusinessException("下载任务文件清单损坏");
        }
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

    /**
     * 从 .torrent 文件字节计算 Info Hash：对 bencode 中 info 字典的原始字节做 SHA-1。
     * 只扫描顶层字典，不依赖第三方 bencode 库。
     */
    private String torrentInfoHashHex(byte[] data) {
        int[] pos = {0};
        if (data.length == 0 || data[0] != 'd') {
            throw new BusinessException("不是合法的种子文件（缺少 bencode 字典头）");
        }
        pos[0]++;
        while (pos[0] < data.length && data[pos[0]] != 'e') {
            String key = readBencodeString(data, pos);
            if ("info".equals(key)) {
                int start = pos[0];
                skipBencodeValue(data, pos);
                return sha1Hex(Arrays.copyOfRange(data, start, pos[0]));
            }
            skipBencodeValue(data, pos);
        }
        throw new BusinessException("种子文件缺少 info 字典");
    }

    private String readBencodeString(byte[] data, int[] pos) {
        int len = 0;
        boolean hasDigit = false;
        while (pos[0] < data.length && data[pos[0]] >= '0' && data[pos[0]] <= '9') {
            hasDigit = true;
            len = len * 10 + (data[pos[0]] - '0');
            pos[0]++;
        }
        if (!hasDigit || pos[0] >= data.length || data[pos[0]] != ':' || pos[0] + 1 + len > data.length) {
            throw new BusinessException("种子文件格式错误（bencode 字符串非法）");
        }
        pos[0]++;
        String value = new String(data, pos[0], len, StandardCharsets.UTF_8);
        pos[0] += len;
        return value;
    }

    /** 跳过一个 bencode 值（整数 / 字符串 / 列表 / 字典），仅移动游标不做解析 */
    private void skipBencodeValue(byte[] data, int[] pos) {
        if (pos[0] >= data.length) {
            throw new BusinessException("种子文件格式错误（bencode 意外结束）");
        }
        char c = (char) data[pos[0]];
        if (c == 'i') {
            pos[0]++;
            while (pos[0] < data.length && data[pos[0]] != 'e') pos[0]++;
            if (pos[0] >= data.length) throw new BusinessException("种子文件格式错误（整数未闭合）");
            pos[0]++;
        } else if (c == 'l' || c == 'd') {
            pos[0]++;
            while (pos[0] < data.length && data[pos[0]] != 'e') {
                if (c == 'd') readBencodeString(data, pos);
                skipBencodeValue(data, pos);
            }
            if (pos[0] >= data.length) throw new BusinessException("种子文件格式错误（容器未闭合）");
            pos[0]++;
        } else {
            readBencodeString(data, pos);
        }
    }

    private String sha1Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
            StringBuilder hex = new StringBuilder(40);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new BusinessException("计算种子 Info Hash 失败：" + e.getMessage());
        }
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
        private boolean force;
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
