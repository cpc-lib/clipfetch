package cc.ivera.magnet.v2.application.parse;

import cc.ivera.magnet.v2.application.port.NativeV2ParseJobRepository;
import cc.ivera.magnet.v2.application.port.NativeV2ResourceRepository;
import cc.ivera.magnet.v2.application.port.NativeV2Storage;
import cc.ivera.magnet.v2.application.port.NativeV2UnitOfWork;
import cc.ivera.magnet.v2.domain.common.ErrorCodes;
import cc.ivera.magnet.v2.domain.common.Hex;
import cc.ivera.magnet.v2.domain.common.V2DomainException;
import cc.ivera.magnet.v2.domain.parse.InputType;
import cc.ivera.magnet.v2.domain.parse.MagnetV2ParseJob;
import cc.ivera.magnet.v2.domain.parse.ParseStatus;
import cc.ivera.magnet.v2.domain.resource.MagnetV2Resource;
import cc.ivera.magnet.v2.domain.resource.MagnetV2ResourceFile;
import cc.ivera.magnet.v2.domain.resource.MagnetV2ResourceHashAlias;
import cc.ivera.magnet.v2.domain.torrent.ParsedTorrent;
import cc.ivera.magnet.v2.domain.torrent.ParsedTorrentFile;
import cc.ivera.magnet.v2.domain.torrent.TorrentMetaParser;
import cc.ivera.magnet.v2.engine.spi.EngineCommand;
import cc.ivera.magnet.v2.engine.spi.EngineEnvelope;
import cc.ivera.magnet.v2.engine.spi.NativeV2Engine;
import cc.ivera.magnet.v2.engine.spi.NativeV2EngineException;
import cc.ivera.magnet.v2.engine.spi.ResolveMetadataCommand;
import cc.ivera.magnet.v2.engine.spi.V2FailureKind;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 单次解析推进：领取、复用或调用 Worker、Java 重解析、幂等绑定。 */
public class NativeV2ParseDispatcher {

    static final String METADATA_STATUS_READY = "READY";
    private static final String HASH_TYPE_BTIH = "BTIH";
    private static final String HASH_TYPE_BTMH = "BTMH";
    private static final int MAX_ERROR_MESSAGE_LENGTH = 512;

    private final NativeV2ParseJobRepository jobs;
    private final NativeV2ResourceRepository resources;
    private final NativeV2Storage storage;
    private final NativeV2Engine engine;
    private final NativeV2UnitOfWork unitOfWork;
    private final NativeV2ParseLimits limits;
    private final TorrentMetaParser torrentParser = new TorrentMetaParser();
    private final Duration metadataTimeout;
    private final Clock clock;

    public NativeV2ParseDispatcher(NativeV2ParseJobRepository jobs,
                                   NativeV2ResourceRepository resources,
                                   NativeV2Storage storage,
                                   NativeV2Engine engine,
                                   NativeV2UnitOfWork unitOfWork,
                                   NativeV2ParseLimits limits,
                                   Duration metadataTimeout,
                                   Clock clock) {
        if (metadataTimeout == null || metadataTimeout.isNegative() || metadataTimeout.isZero()) {
            throw new IllegalArgumentException("metadataTimeout 必须为正数");
        }
        this.jobs = jobs;
        this.resources = resources;
        this.storage = storage;
        this.engine = engine;
        this.unitOfWork = unitOfWork;
        this.limits = limits;
        this.metadataTimeout = metadataTimeout;
        this.clock = clock;
    }

    /** @return 是否领取并处理了该解析任务 */
    public boolean dispatchOne(String parseUuid) {
        Optional<MagnetV2ParseJob> found = jobs.findByUuid(parseUuid);
        if (found.isEmpty() || found.get().getStatus() != ParseStatus.PENDING) {
            return false;
        }
        MagnetV2ParseJob job = found.get();
        long claimRevision = job.getRevision() + 1;
        if (jobs.claimPending(job.getId(), job.getRevision()) != 1) {
            return false;
        }
        try {
            resolve(job, claimRevision);
        } catch (NativeV2EngineException error) {
            fail(job, claimRevision, engineErrorCode(error), error.getMessage());
            cleanupInput(job, !error.retryable());
        } catch (V2DomainException error) {
            fail(job, claimRevision, error.code(), error.getMessage());
            cleanupInput(job, permanent(error.code()));
        } catch (RuntimeException error) {
            fail(job, claimRevision, ErrorCodes.RESOLVE_FAILED, "元数据解析失败，请稍后重试");
            cleanupInput(job, false);
        }
        return true;
    }

    private void resolve(MagnetV2ParseJob job, long revision) {
        Optional<MagnetV2Resource> winner = findWinner(job.getBtih(), job.getBtmh());
        if (winner.isPresent()) {
            bindReady(job, revision, winner.get().getId());
            return;
        }
        byte[] torrentBytes = job.getInputType() == InputType.TORRENT
                ? readParseInput(job)
                : resolveFromWorker(job, revision);
        ParsedTorrent parsed = torrentParser.parse(torrentBytes);
        verifyHashes(job, parsed);
        verifyLimits(parsed);
        bindNew(job, revision, parsed, torrentBytes);
    }

    private byte[] readParseInput(MagnetV2ParseJob job) {
        if (job.getInputStorageKey() == null) {
            throw new V2DomainException(ErrorCodes.INPUT_LOST, "解析输入已丢失，请重新提交");
        }
        return storage.readParseInput(UUID.fromString(job.getParseUuid()));
    }

    private byte[] resolveFromWorker(MagnetV2ParseJob job, long revision) {
        EngineEnvelope envelope = envelope(job, revision, ":RESOLVE");
        try {
            return engine.resolveMetadata(new ResolveMetadataCommand(envelope,
                    job.getBtih(), job.getBtmh(), TrackersJson.read(job.getTrackersJson()), new byte[0]));
        } finally {
            removeQuietly(envelope(job, revision, ":REMOVE"));
        }
    }

    private void removeQuietly(EngineEnvelope envelope) {
        try {
            engine.remove(new EngineCommand(envelope), true);
        } catch (RuntimeException ignored) {
            // 临时 metadata-only Session 的清理失败不影响解析结果。
        }
    }

    private EngineEnvelope envelope(MagnetV2ParseJob job, long revision, String suffix) {
        Instant deadline = clock.instant().plus(metadataTimeout);
        return new EngineEnvelope(UUID.fromString(job.getParseUuid()),
                job.getParseUuid() + ":" + revision + suffix, 1, deadline, deadline);
    }

    private void verifyHashes(MagnetV2ParseJob job, ParsedTorrent parsed) {
        boolean btihMatches = job.getBtih() == null
                || Arrays.equals(job.getBtih(), parsed.infoHashV1());
        boolean btmhMatches = job.getBtmh() == null
                || Arrays.equals(job.getBtmh(), parsed.infoHashV2());
        if (!btihMatches || !btmhMatches) {
            throw new V2DomainException(ErrorCodes.HASH_MISMATCH, "Worker 返回的元数据哈希与提交身份不一致");
        }
    }

    private void verifyLimits(ParsedTorrent parsed) {
        if (parsed.files().size() > limits.maxFiles()) {
            throw new V2DomainException(ErrorCodes.INVALID_TORRENT, "文件数超过上限");
        }
        if (parsed.totalBytes() > limits.maxTotalBytes()) {
            throw new V2DomainException(ErrorCodes.INVALID_TORRENT, "总大小超过上限");
        }
    }

    private void bindReady(MagnetV2ParseJob job, long revision, long resourceId) {
        if (jobs.markReady(job.getId(), job.getUserId(), revision, resourceId) == 1) {
            cleanupInput(job, true);
        }
    }

    private void bindNew(MagnetV2ParseJob job, long revision, ParsedTorrent parsed, byte[] torrentBytes) {
        UUID resourceUuid = UUID.randomUUID();
        storage.writeMetadata(resourceUuid, torrentBytes);
        MagnetV2Resource resource = newResource(resourceUuid, parsed);
        try {
            unitOfWork.run(() -> {
                Optional<MagnetV2Resource> race = findWinner(parsed.infoHashV1(), parsed.infoHashV2());
                if (race.isPresent()) {
                    bindReady(job, revision, race.get().getId());
                    return;
                }
                MagnetV2Resource saved = resources.insert(resource);
                resources.insertAliases(aliases(saved.getId(), parsed));
                resources.insertFiles(files(saved.getId(), parsed));
                if (jobs.markReady(job.getId(), job.getUserId(), revision, saved.getId()) != 1) {
                    throw new V2DomainException(ErrorCodes.CONFLICT, "解析任务状态已变化");
                }
            });
        } catch (V2DomainException error) {
            if (error.code().equals(ErrorCodes.CONFLICT)) {
                Optional<MagnetV2Resource> winner = findWinner(parsed.infoHashV1(), parsed.infoHashV2());
                if (winner.isPresent()) {
                    unitOfWork.run(() -> bindReady(job, revision, winner.get().getId()));
                    return;
                }
            }
            throw error;
        }
        cleanupInput(job, true);
    }

    private MagnetV2Resource newResource(UUID resourceUuid, ParsedTorrent parsed) {
        LocalDateTime now = LocalDateTime.now(clock);
        return MagnetV2Resource.builder()
                .resourceUuid(resourceUuid.toString())
                .canonicalKey(canonicalKey(parsed))
                .btih(parsed.infoHashV1())
                .btmh(parsed.infoHashV2())
                .versionType(parsed.versionType())
                .name(parsed.name())
                .pieceLength(parsed.pieceLength())
                .totalBytes(parsed.totalBytes())
                .fileCount(parsed.files().size())
                .privateFlag(false)
                .metadataKey("metadata/" + resourceUuid + ".torrent")
                .metadataStatus(METADATA_STATUS_READY)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private List<MagnetV2ResourceHashAlias> aliases(long resourceId, ParsedTorrent parsed) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<MagnetV2ResourceHashAlias> aliases = new ArrayList<>(2);
        if (parsed.infoHashV1() != null) {
            aliases.add(hashAlias(resourceId, HASH_TYPE_BTIH, parsed.infoHashV1(), now));
        }
        if (parsed.infoHashV2() != null) {
            aliases.add(hashAlias(resourceId, HASH_TYPE_BTMH, parsed.infoHashV2(), now));
        }
        return aliases;
    }

    private static MagnetV2ResourceHashAlias hashAlias(long resourceId, String hashType,
                                                       byte[] hash, LocalDateTime now) {
        return MagnetV2ResourceHashAlias.builder()
                .resourceId(resourceId)
                .hashType(hashType)
                .hashBytes(hash)
                .createdAt(now)
                .build();
    }

    private List<MagnetV2ResourceFile> files(long resourceId, ParsedTorrent parsed) {
        LocalDateTime now = LocalDateTime.now(clock);
        return parsed.files().stream()
                .map(file -> resourceFile(resourceId, file, now))
                .toList();
    }

    private static MagnetV2ResourceFile resourceFile(long resourceId, ParsedTorrentFile file,
                                                     LocalDateTime now) {
        return MagnetV2ResourceFile.builder()
                .resourceId(resourceId)
                .fileIndex(file.index())
                .relativePath(file.relativePath())
                .declaredBytes(file.sizeBytes())
                .piecesRoot(file.v2PiecesRoot())
                .createdAt(now)
                .build();
    }

    private Optional<MagnetV2Resource> findWinner(byte[] btih, byte[] btmh) {
        if (btmh != null) {
            Optional<MagnetV2Resource> v2 = resources.findReadyByAlias(HASH_TYPE_BTMH, btmh);
            if (v2.isPresent()) {
                return v2;
            }
        }
        if (btih != null) {
            return resources.findReadyByAlias(HASH_TYPE_BTIH, btih);
        }
        return Optional.empty();
    }

    private static String canonicalKey(ParsedTorrent parsed) {
        if (parsed.infoHashV2() != null) {
            return "btmh:" + Hex.encode(parsed.infoHashV2());
        }
        return "btih:" + Hex.encode(parsed.infoHashV1());
    }

    private void fail(MagnetV2ParseJob job, long revision, String errorCode, String errorMessage) {
        String message = errorMessage == null ? errorCode : errorMessage;
        if (message.length() > MAX_ERROR_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
        }
        jobs.markFailed(job.getId(), job.getUserId(), revision, errorCode, message);
    }

    private static String engineErrorCode(NativeV2EngineException error) {
        return error.kind() == V2FailureKind.STORAGE
                ? ErrorCodes.STORAGE_UNAVAILABLE
                : ErrorCodes.RESOLVE_FAILED;
    }

    private static boolean permanent(String errorCode) {
        return ErrorCodes.HASH_MISMATCH.equals(errorCode)
                || ErrorCodes.PRIVATE_TORRENT.equals(errorCode)
                || ErrorCodes.INVALID_TORRENT.equals(errorCode)
                || ErrorCodes.INVALID_MAGNET.equals(errorCode)
                || ErrorCodes.INVALID_PATH.equals(errorCode)
                || ErrorCodes.INPUT_LOST.equals(errorCode);
    }

    private void cleanupInput(MagnetV2ParseJob job, boolean allowed) {
        if (!allowed || job.getInputStorageKey() == null) {
            return;
        }
        try {
            storage.deleteParseInput(UUID.fromString(job.getParseUuid()));
        } catch (V2DomainException ignored) {
            // 清理失败只影响存储占用，不改变解析状态。
        }
    }
}
