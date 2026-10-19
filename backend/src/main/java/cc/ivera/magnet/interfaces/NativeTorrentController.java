package cc.ivera.magnet.interfaces;

import cc.ivera.auth.domain.User;
import cc.ivera.auth.interfaces.AuthInterceptor;
import cc.ivera.magnet.engine.config.NativeTorrentProperties;
import cc.ivera.magnet.engine.magnet.MagnetUriParser;
import cc.ivera.magnet.engine.metainfo.TorrentV1MetainfoParser;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import cc.ivera.shared.web.ApiResponse;
import cc.ivera.shared.web.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Safe, authenticated entry points for the unfinished native engine.
 * Deliberately does not offer fake downloads or allow tasks while transport is unimplemented.
 */
@RestController
@RequestMapping("/api/magnet/native")
@RequiredArgsConstructor
public class NativeTorrentController {
    private final NativeTorrentProperties properties;
    private final MagnetUriParser magnetUriParser = new MagnetUriParser();

    @GetMapping("/engine/health")
    public ApiResponse<Map<String, Object>> health(HttpServletRequest request) {
        currentUserId(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configuredEnabled", properties.isEnabled());
        boolean taskApiReady=properties.isTaskControlEnabled()
                && properties.isTaskCreationEnabled();
        boolean boundedWorkerReady=properties.isEnabled()
                && properties.isWorkerRoundEnabled()
                && properties.isAsyncWorkerEnabled()
                && taskApiReady;
        result.put("ready", boundedWorkerReady);
        result.put("stage", "P4-bounded-native-worker");
        result.put("protocolAvailable", true);
        result.put("taskApiReady", taskApiReady);
        result.put("boundedWorkerReady", boundedWorkerReady);
        result.put("productionReady", false);
        result.put("supported", "Magnet v1, Tracker/DHT, BEP10/BEP9, BEP11 PeX, BEP14 LSD, bounded public Tracker probing, bounded verified Piece worker and checkpoint persistence (experimental)");
        result.put("pexProtocolAvailable", true);
        result.put("lsdProtocolAvailable", true);
        result.put("publicTrackerCatalogAvailable", true);
        result.put("discoveryAccelerationAutoEnabled", false);
        result.put("metadataLookupEnabled", properties.isMetadataLookupEnabled());
        result.put("taskControlEnabled", properties.isTaskControlEnabled());
        result.put("taskCreationEnabled", properties.isTaskCreationEnabled());
        result.put("downloadRootSelectionEnabled",
                properties.isEnabled() && properties.isTaskCreationEnabled());
        result.put("workerRoundEnabled", properties.isWorkerRoundEnabled());
        result.put("dhtFallbackEnabled", properties.isDhtFallbackEnabled());
        result.put("asyncWorkerEnabled", properties.isAsyncWorkerEnabled());
        result.put("continuousWorkerEnabled", properties.isContinuousWorkerEnabled());
        result.put("parallelPieceEnabled", properties.isParallelPieceEnabled());
        result.put("parallelPieceWorkers", properties.isParallelPieceEnabled()
                ? Math.min(properties.getParallelPieceWorkers(),
                        cc.ivera.magnet.engine.piece.PieceDownloadCoordinator.MAX_PARALLEL_PIECES) : 1);
        result.put("rarestFirstEnabled", properties.isRarestFirstEnabled());
        result.put("peerConnectionReuseEnabled", properties.isPeerConnectionReuseEnabled());
        result.put("singleInstanceOrphanRecoveryEnabled", properties.isSingleInstanceOrphanRecoveryEnabled());
        result.put("metadataJobsInMemoryOnly", true);
        result.put("downloadAvailable", boundedWorkerReady);
        return ApiResponse.ok(result);
    }

    @PostMapping("/magnet/inspect")
    public ApiResponse<MagnetResult> inspect(HttpServletRequest request, @Valid @RequestBody InspectRequest body) {
        currentUserId(request);
        try {
            MagnetUriParser.MagnetInfo parsed = magnetUriParser.parse(body.magnetUri());
            return ApiResponse.ok(new MagnetResult(parsed.infoHash(), parsed.displayName(), parsed.trackers().size()));
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            throw new BusinessException("无法识别磁力链接：" + e.getMessage());
        }
    }

    /**
     * Offline .torrent inspection with original-info SHA-1 verification.
     * Not a download task, and does not contact trackers or peers.
     */
    @PostMapping(value = "/torrent/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<TorrentInspection> inspectTorrent(
            HttpServletRequest request, @RequestParam("file") MultipartFile file) {
        currentUserId(request);
        long max = Math.min(8L * 1024 * 1024, properties.getMaxMetadataBytes());
        if (file == null || file.isEmpty() || max < 1 || file.getSize() > max) {
            throw new BusinessException("种子文件为空或超出元数据大小上限");
        }
        try {
            var info = new TorrentV1MetainfoParser().parse(file.getBytes());
            List<TorrentFileSummary> summaries = info.files().stream()
                    .map(f -> new TorrentFileSummary(f.index(), f.path(), f.length())).toList();
            return ApiResponse.ok(new TorrentInspection(info.infoHash(), info.name(),
                    info.totalBytes(), info.pieceLength(), info.pieceLayout().pieceCount(), summaries));
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            throw new BusinessException("种子文件校验失败：" + e.getMessage());
        } catch (java.io.IOException e) {
            throw new BusinessException("种子文件读取失败：" + e.getMessage());
        }
    }

    public record TorrentFileSummary(int index, String path, long size) {}
    public record TorrentInspection(String infoHash, String name, long totalBytes,
                                    int pieceLength, int pieceCount, List<TorrentFileSummary> files) {}

    private static Long currentUserId(HttpServletRequest request) {
        Object user = request.getAttribute(AuthInterceptor.ATTR_USER);
        if (!(user instanceof User u)) throw new BusinessException("请先登录");
        return u.getId();
    }

    public record InspectRequest(@NotBlank @Size(max = 16384) String magnetUri) {}
    public record MagnetResult(String infoHash, String displayName, int trackerCount) {}
}
