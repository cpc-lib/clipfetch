package cc.ivera.magnet.v2.application.parse;

import cc.ivera.magnet.v2.application.port.NativeV2ParseJobRepository;
import cc.ivera.magnet.v2.application.port.NativeV2Quota;
import cc.ivera.magnet.v2.application.port.NativeV2ResourceRepository;
import cc.ivera.magnet.v2.application.port.NativeV2Storage;
import cc.ivera.magnet.v2.domain.common.Digests;
import cc.ivera.magnet.v2.domain.common.ErrorCodes;
import cc.ivera.magnet.v2.domain.common.Hex;
import cc.ivera.magnet.v2.domain.common.V2DomainException;
import cc.ivera.magnet.v2.domain.parse.InputType;
import cc.ivera.magnet.v2.domain.parse.MagnetV2ParseJob;
import cc.ivera.magnet.v2.domain.parse.ParseStatus;
import cc.ivera.magnet.v2.domain.torrent.MagnetParser;
import cc.ivera.magnet.v2.domain.torrent.ParsedMagnet;
import cc.ivera.magnet.v2.domain.torrent.ParsedTorrent;
import cc.ivera.magnet.v2.domain.torrent.TorrentMetaParser;
import cc.ivera.magnet.v2.engine.spi.NativeV2Engine;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 解析提交：只持久化规范化身份，原始 Magnet 不出请求线程。 */
public class NativeV2ParseService {

    private final NativeV2ParseJobRepository jobs;
    private final NativeV2ResourceRepository resources;
    private final NativeV2Storage storage;
    private final NativeV2Quota quota;
    private final NativeV2Engine engine;
    private final NativeV2ParseLimits limits;
    private final Clock clock;

    public NativeV2ParseService(NativeV2ParseJobRepository jobs,
                                NativeV2ResourceRepository resources,
                                NativeV2Storage storage,
                                NativeV2Quota quota,
                                NativeV2Engine engine,
                                NativeV2ParseLimits limits,
                                Clock clock) {
        this.jobs = jobs;
        this.resources = resources;
        this.storage = storage;
        this.quota = quota;
        this.engine = engine;
        this.limits = limits;
        this.clock = clock;
    }

    public MagnetV2ParseJob submitMagnet(long userId, String uri) {
        ParsedMagnet magnet = new MagnetParser().parse(uri);
        if (magnet.trackers().size() > limits.maxTrackers()) {
            throw new V2DomainException(ErrorCodes.INVALID_MAGNET, "Tracker 数量超过上限");
        }
        gate(userId);
        UUID parseId = UUID.randomUUID();
        byte[] inputHash = Digests.sha256(
                identity(magnet.btih(), magnet.btmh()).getBytes(StandardCharsets.UTF_8));
        return jobs.insert(newJob(parseId, userId, InputType.MAGNET, inputHash,
                magnet.btih(), magnet.btmh(), magnet.trackers(), null));
    }

    public MagnetV2ParseJob submitTorrent(long userId, String name, byte[] torrentBytes) {
        if (name == null || name.isBlank()) {
            throw new V2DomainException(ErrorCodes.INVALID_TORRENT, "文件名不能为空");
        }
        if (torrentBytes == null || torrentBytes.length == 0) {
            throw new V2DomainException(ErrorCodes.INVALID_TORRENT, "torrent 内容为空");
        }
        if (torrentBytes.length > limits.maxTorrentBytes()) {
            throw new V2DomainException(ErrorCodes.INVALID_TORRENT, "torrent 超过字节上限");
        }
        ParsedTorrent parsed = new TorrentMetaParser().parse(torrentBytes);
        if (parsed.files().size() > limits.maxFiles()) {
            throw new V2DomainException(ErrorCodes.INVALID_TORRENT, "文件数超过上限");
        }
        if (parsed.totalBytes() > limits.maxTotalBytes()) {
            throw new V2DomainException(ErrorCodes.INVALID_TORRENT, "总大小超过上限");
        }
        if (parsed.trackers().size() > limits.maxTrackers()) {
            throw new V2DomainException(ErrorCodes.INVALID_TORRENT, "Tracker 数量超过上限");
        }
        gate(userId);
        UUID parseId = UUID.randomUUID();
        storage.writeParseInput(parseId, torrentBytes);
        try {
            return jobs.insert(newJob(parseId, userId, InputType.TORRENT, Digests.sha256(torrentBytes),
                    parsed.infoHashV1(), parsed.infoHashV2(), parsed.trackers(),
                    "parse-input/" + parseId + ".torrent"));
        } catch (RuntimeException error) {
            deleteQuietly(parseId);
            throw error;
        }
    }

    public MagnetV2ParseJob findOwned(long userId, String parseUuid) {
        return jobs.findOwned(parseUuid, userId)
                .orElseThrow(() -> new V2DomainException(ErrorCodes.NOT_FOUND, "解析任务不存在"));
    }

    public List<MagnetV2ParseJob> listOwned(long userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return jobs.listOwned(userId, safePage * safeSize, safeSize);
    }

    public List<ResourceFileView> filesOf(long userId, String parseUuid) {
        MagnetV2ParseJob job = findOwned(userId, parseUuid);
        if (job.getStatus() != ParseStatus.READY || job.getResourceId() == null) {
            return List.of();
        }
        return resources.findFiles(job.getResourceId()).stream()
                .map(file -> new ResourceFileView(file.getFileIndex(), file.getRelativePath(),
                        file.getDeclaredBytes()))
                .toList();
    }

    public ParseDetail detail(long userId, String parseUuid) {
        MagnetV2ParseJob job = findOwned(userId, parseUuid);
        if (job.getStatus() != ParseStatus.READY || job.getResourceId() == null) {
            return new ParseDetail(job, null, 0L, List.of());
        }
        return resources.findById(job.getResourceId())
                .map(resource -> new ParseDetail(job, resource.getName(), resource.getTotalBytes(),
                        filesOf(userId, parseUuid)))
                .orElseGet(() -> new ParseDetail(job, null, 0L, List.of()));
    }

    public record ResourceFileView(int fileIndex, String relativePath, long bytes) {
    }

    public record ParseDetail(MagnetV2ParseJob job, String name, long totalBytes,
                              List<ResourceFileView> files) {
    }

    private void gate(long userId) {
        if (!engine.health().connected()) {
            throw new V2DomainException(ErrorCodes.WORKER_UNAVAILABLE, "Native v2 引擎暂不可用，请稍后重试");
        }
        quota.consumeParseRate(userId);
        try (NativeV2Quota.Lease ignored = quota.acquireParseLease(userId)) {
            // 并发门禁只在请求线程内持有，异步解析由调度器推进。
        }
    }

    private MagnetV2ParseJob newJob(UUID parseId, long userId, InputType inputType, byte[] inputHash,
                                    byte[] btih, byte[] btmh, List<String> trackers, String inputStorageKey) {
        LocalDateTime now = LocalDateTime.now(clock);
        return MagnetV2ParseJob.builder()
                .parseUuid(parseId.toString())
                .userId(userId)
                .inputType(inputType)
                .inputHash(inputHash)
                .btih(btih)
                .btmh(btmh)
                .trackersJson(TrackersJson.write(trackers))
                .inputStorageKey(inputStorageKey)
                .status(ParseStatus.PENDING)
                .revision(0L)
                .retryCount(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private static String identity(byte[] btih, byte[] btmh) {
        return (btih == null ? "" : Hex.encode(btih)) + "|" + (btmh == null ? "" : Hex.encode(btmh));
    }

    private void deleteQuietly(UUID parseId) {
        try {
            storage.deleteParseInput(parseId);
        } catch (V2DomainException ignored) {
            // 清理失败不改变提交失败的主错误。
        }
    }
}
