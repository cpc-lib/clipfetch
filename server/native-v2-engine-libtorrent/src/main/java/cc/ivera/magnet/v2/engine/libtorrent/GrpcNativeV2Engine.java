package cc.ivera.magnet.v2.engine.libtorrent;

import cc.ivera.magnet.v2.engine.spi.AddTorrentCommand;
import cc.ivera.magnet.v2.engine.spi.EngineCommand;
import cc.ivera.magnet.v2.engine.spi.EngineEnvelope;
import cc.ivera.magnet.v2.engine.spi.NativeV2Engine;
import cc.ivera.magnet.v2.engine.spi.NativeV2EngineException;
import cc.ivera.magnet.v2.engine.spi.NativeV2Health;
import cc.ivera.magnet.v2.engine.spi.NativeV2Snapshot;
import cc.ivera.magnet.v2.engine.spi.ResolveMetadataCommand;
import cc.ivera.magnet.v2.engine.spi.ResumeSnapshot;
import cc.ivera.magnet.v2.engine.spi.UpdatePrioritiesCommand;
import cc.ivera.magnet.v2.engine.spi.V2FailureKind;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;

import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * libtorrent C++ Worker 的 gRPC 客户端适配（proto：native/proto/download_engine.proto，
 * 服务 cc.ivera.magnet.engine.v1.DownloadEngine）。
 * 仓库未配置 protobuf 代码生成，这里以极简的 proto 线格式编解码加字节数组 Marshaller
 * 直接调用 grpc-core；所有 RPC 应用配置的超时与入站消息上限。
 */
public final class GrpcNativeV2Engine implements NativeV2Engine {

    private static final String SERVICE = "cc.ivera.magnet.engine.v1.DownloadEngine";
    private static final int MAX_ERROR_TEXT = 256;

    private static final MethodDescriptor<byte[], byte[]> RESOLVE_METADATA = unary("ResolveMetadata");
    private static final MethodDescriptor<byte[], byte[]> ADD_TORRENT = unary("AddTorrent");
    private static final MethodDescriptor<byte[], byte[]> UPDATE_PRIORITIES = unary("UpdateFilePriorities");
    private static final MethodDescriptor<byte[], byte[]> PAUSE = unary("Pause");
    private static final MethodDescriptor<byte[], byte[]> RESUME = unary("Resume");
    private static final MethodDescriptor<byte[], byte[]> GET_SNAPSHOT = unary("GetSnapshot");
    private static final MethodDescriptor<byte[], byte[]> SAVE_RESUME = unary("SaveResumeData");
    private static final MethodDescriptor<byte[], byte[]> REMOVE_TORRENT = unary("RemoveTorrent");
    private static final MethodDescriptor<byte[], byte[]> HEALTH = unary("Health");

    private final NativeV2GrpcProperties properties;
    private final ManagedChannel channel;

    public GrpcNativeV2Engine(NativeV2GrpcProperties properties) {
        this.properties = properties;
        this.channel = buildChannel(properties);
    }

    private static ManagedChannel buildChannel(NativeV2GrpcProperties properties) {
        ManagedChannelBuilder<?> builder;
        if (properties.isTlsEnabled()) {
            try {
                builder = NettyChannelBuilder
                        .forAddress(properties.getHost(), properties.getPort())
                        .sslContext(GrpcSslContexts.forClient()
                                .trustManager(properties.getCaCertificatePath().toFile())
                                .keyManager(properties.getClientCertificatePath().toFile(),
                                        properties.getClientKeyPath().toFile())
                                .build());
            } catch (SSLException error) {
                throw new IllegalStateException("Native v2 Worker TLS 证书加载失败，请检查证书配置", error);
            }
        } else {
            builder = ManagedChannelBuilder
                    .forAddress(properties.getHost(), properties.getPort())
                    .usePlaintext();
        }
        return builder.maxInboundMessageSize(properties.getMaxMessageBytes()).build();
    }

    // ------------------------------------------------------------------ SPI

    @Override
    public NativeV2Health health() {
        try {
            ProtoReader reader = new ProtoReader(call(HEALTH, new byte[0]));
            boolean ok = false;
            String version = "";
            while (reader.next()) {
                switch (reader.field()) {
                    case 1 -> ok = reader.bool();
                    case 3 -> version = reader.string();
                    default -> reader.skip();
                }
            }
            return ok ? NativeV2Health.available(version)
                    : NativeV2Health.unavailable("Native v2 Worker 未就绪");
        } catch (RuntimeException error) {
            return NativeV2Health.unavailable("Native v2 Worker 暂不可用");
        }
    }

    @Override
    public byte[] resolveMetadata(ResolveMetadataCommand command) {
        ProtoWriter writer = new ProtoWriter();
        writer.message(1, envelope(command.envelope()));
        writer.bytes(2, command.btih());
        writer.bytes(3, command.btmh());
        writer.strings(4, command.trackers());
        writer.bytes(5, command.torrentFile());
        ProtoReader reader = new ProtoReader(call(RESOLVE_METADATA, writer.toByteArray()));
        boolean accepted = false;
        String error = "";
        byte[] torrentFile = new byte[0];
        while (reader.next()) {
            switch (reader.field()) {
                case 1 -> accepted = reader.bool();
                case 2 -> error = reader.string();
                case 3 -> torrentFile = reader.bytes();
                default -> reader.skip();
            }
        }
        if (!accepted) {
            throw rejected("解析元数据", error);
        }
        return torrentFile;
    }

    @Override
    public void addTorrent(AddTorrentCommand command) {
        ProtoWriter writer = new ProtoWriter();
        writer.message(1, envelope(command.envelope()));
        writer.bytes(2, command.metadata());
        writer.bytes(3, command.resumeData());
        writer.string(4, command.storagePath());
        writer.bool(5, command.metadataOnly());
        writer.bytes(6, command.btih());
        writer.bytes(7, command.btmh());
        writer.strings(8, command.trackers());
        ensureAccepted("挂载下载会话", call(ADD_TORRENT, writer.toByteArray()));
    }

    @Override
    public void updateFilePriorities(UpdatePrioritiesCommand command) {
        ProtoWriter writer = new ProtoWriter();
        writer.message(1, envelope(command.envelope()));
        writer.packedInt32(2, command.filePriorities());
        ensureAccepted("下发文件优先级", call(UPDATE_PRIORITIES, writer.toByteArray()));
    }

    @Override
    public void pause(EngineCommand command) {
        ensureAccepted("暂停会话", call(PAUSE, commandOnly(command.envelope())));
    }

    @Override
    public void resume(EngineCommand command) {
        ensureAccepted("恢复会话", call(RESUME, commandOnly(command.envelope())));
    }

    @Override
    public NativeV2Snapshot snapshot(EngineCommand command) {
        ProtoReader reader = new ProtoReader(call(GET_SNAPSHOT, commandOnly(command.envelope())));
        String state = "";
        boolean metadataReady = false;
        long verifiedBytes = 0;
        long downloadRate = 0;
        long uploadRate = 0;
        int peerCount = 0;
        List<Long> fileVerifiedBytes = new ArrayList<>();
        while (reader.next()) {
            switch (reader.field()) {
                case 1 -> state = reader.string();
                case 2 -> metadataReady = reader.bool();
                case 3 -> verifiedBytes = reader.varint();
                case 4 -> downloadRate = reader.varint();
                case 5 -> uploadRate = reader.varint();
                case 6 -> peerCount = (int) reader.varint();
                case 7 -> {
                    if (reader.wire() == 2) {
                        ProtoReader packed = new ProtoReader(reader.bytes());
                        while (packed.hasRemaining()) {
                            fileVerifiedBytes.add(packed.varint());
                        }
                    } else {
                        fileVerifiedBytes.add(reader.varint());
                    }
                }
                default -> reader.skip();
            }
        }
        return new NativeV2Snapshot(state, metadataReady, verifiedBytes, downloadRate, uploadRate,
                peerCount, List.copyOf(fileVerifiedBytes));
    }

    @Override
    public ResumeSnapshot saveResume(EngineCommand command) {
        ProtoReader reader = new ProtoReader(call(SAVE_RESUME, commandOnly(command.envelope())));
        byte[] resumeData = new byte[0];
        long version = 0;
        boolean accepted = false;
        String error = "";
        while (reader.next()) {
            switch (reader.field()) {
                case 1 -> resumeData = reader.bytes();
                case 2 -> version = reader.varint();
                case 3 -> accepted = reader.bool();
                case 4 -> error = reader.string();
                default -> reader.skip();
            }
        }
        if (!accepted) {
            throw rejected("保存恢复数据", error);
        }
        return new ResumeSnapshot(resumeData, version);
    }

    @Override
    public void remove(EngineCommand command, boolean removeData) {
        ProtoWriter writer = new ProtoWriter();
        writer.message(1, envelope(command.envelope()));
        writer.bool(2, removeData);
        ensureAccepted("移除会话", call(REMOVE_TORRENT, writer.toByteArray()));
    }

    @Override
    public void close() {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 编解码与错误映射

    private byte[] call(MethodDescriptor<byte[], byte[]> method, byte[] request) {
        try {
            return ClientCalls.blockingUnaryCall(channel, method,
                    CallOptions.DEFAULT.withDeadlineAfter(properties.getRpcTimeoutMs(), TimeUnit.MILLISECONDS),
                    request);
        } catch (StatusRuntimeException error) {
            throw mapStatus(error);
        }
    }

    private static byte[] commandOnly(EngineEnvelope envelope) {
        ProtoWriter writer = new ProtoWriter();
        writer.message(1, envelope(envelope));
        return writer.toByteArray();
    }

    private static void ensureAccepted(String operation, byte[] response) {
        ProtoReader reader = new ProtoReader(response);
        boolean accepted = false;
        String error = "";
        while (reader.next()) {
            switch (reader.field()) {
                case 1 -> accepted = reader.bool();
                case 2 -> error = reader.string();
                default -> reader.skip();
            }
        }
        if (!accepted) {
            throw rejected(operation, error);
        }
    }

    private static NativeV2EngineException mapStatus(StatusRuntimeException error) {
        Status.Code code = error.getStatus().getCode();
        return switch (code) {
            case UNAVAILABLE, DEADLINE_EXCEEDED, RESOURCE_EXHAUSTED, ABORTED, CANCELLED ->
                    new NativeV2EngineException(V2FailureKind.TRANSIENT, true,
                            "Native v2 Worker 暂不可用，请稍后重试", error);
            case INVALID_ARGUMENT, OUT_OF_RANGE ->
                    new NativeV2EngineException(V2FailureKind.INVALID_ARGUMENT, false,
                            "Native v2 Worker 拒绝了请求参数", error);
            default ->
                    new NativeV2EngineException(V2FailureKind.INTERNAL, false,
                            "Native v2 Worker 调用失败", error);
        };
    }

    /**
     * 当前 proto 响应尚无结构化 error_code，只能按清洗后的关键词归类；
     * 待 proto 增加 error_code 后应改为纯码值映射，不再看文本。
     */
    private static NativeV2EngineException rejected(String operation, String error) {
        String text = error == null ? "" : error.toLowerCase(Locale.ROOT);
        V2FailureKind kind;
        boolean retryable;
        if (text.contains("busy") || text.contains("conflict") || text.contains("epoch")
                || text.contains("lease") || text.contains("unknown resource")) {
            kind = V2FailureKind.CONFLICT;
            retryable = false;
        } else if (text.contains("storage") || text.contains("disk")) {
            kind = V2FailureKind.STORAGE;
            retryable = false;
        } else if (text.contains("path")) {
            kind = V2FailureKind.UNSAFE_PATH;
            retryable = false;
        } else if (text.contains("hash")) {
            kind = V2FailureKind.HASH_MISMATCH;
            retryable = false;
        } else if (text.contains("temporar") || text.contains("timeout")) {
            kind = V2FailureKind.TRANSIENT;
            retryable = true;
        } else {
            kind = V2FailureKind.INTERNAL;
            retryable = false;
        }
        return new NativeV2EngineException(kind, retryable,
                "Native v2 Worker " + operation + "被拒绝：" + sanitize(error));
    }

    private static String sanitize(String error) {
        String value = error == null ? "" : error.strip();
        return value.length() <= MAX_ERROR_TEXT ? value : value.substring(0, MAX_ERROR_TEXT);
    }

    private static byte[] envelope(EngineEnvelope envelope) {
        ProtoWriter writer = new ProtoWriter();
        writer.string(1, envelope.commandId());
        writer.string(2, envelope.resourceId().toString());
        writer.int64(3, envelope.ownerEpoch());
        writer.message(4, timestamp(envelope.deadline()));
        writer.message(5, timestamp(envelope.leaseUntil()));
        return writer.toByteArray();
    }

    private static byte[] timestamp(Instant instant) {
        ProtoWriter writer = new ProtoWriter();
        writer.int64(1, instant.getEpochSecond());
        writer.int32(2, instant.getNano());
        return writer.toByteArray();
    }

    private static MethodDescriptor<byte[], byte[]> unary(String method) {
        return MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(SERVICE + "/" + method)
                .setRequestMarshaller(ByteArrayMarshaller.INSTANCE)
                .setResponseMarshaller(ByteArrayMarshaller.INSTANCE)
                .build();
    }

    /** 原样透传字节数组的 Marshaller，避免依赖生成的 protobuf 类。 */
    private enum ByteArrayMarshaller implements MethodDescriptor.Marshaller<byte[]> {
        INSTANCE;

        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                return stream.readAllBytes();
            } catch (IOException error) {
                throw new IllegalStateException("gRPC 消息读取失败", error);
            }
        }
    }

    /** 极简 proto3 编码器：仅支持 varint 与 length-delimited 两类线格式。 */
    private static final class ProtoWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void string(int field, String value) {
            if (value != null && !value.isEmpty()) {
                bytes(field, value.getBytes(StandardCharsets.UTF_8));
            }
        }

        void strings(int field, List<String> values) {
            if (values != null) {
                for (String value : values) {
                    string(field, value);
                }
            }
        }

        void bytes(int field, byte[] value) {
            if (value != null && value.length > 0) {
                tag(field, 2);
                varint(value.length);
                out.write(value, 0, value.length);
            }
        }

        void message(int field, byte[] encoded) {
            if (encoded != null) {
                tag(field, 2);
                varint(encoded.length);
                out.write(encoded, 0, encoded.length);
            }
        }

        void bool(int field, boolean value) {
            if (value) {
                tag(field, 0);
                varint(1);
            }
        }

        void int32(int field, int value) {
            if (value != 0) {
                tag(field, 0);
                varint(value);
            }
        }

        void int64(int field, long value) {
            if (value != 0) {
                tag(field, 0);
                varint(value);
            }
        }

        void packedInt32(int field, List<Integer> values) {
            if (values == null || values.isEmpty()) {
                return;
            }
            ProtoWriter packed = new ProtoWriter();
            for (int value : values) {
                packed.varint(value);
            }
            bytes(field, packed.toByteArray());
        }

        private void tag(int field, int wireType) {
            varint(((long) field << 3) | wireType);
        }

        private void varint(long value) {
            while (true) {
                if ((value & ~0x7FL) == 0) {
                    out.write((int) value);
                    return;
                }
                out.write(((int) value & 0x7F) | 0x80);
                value >>>= 7;
            }
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    /** 极简 proto3 解码器：重复数值字段同时兼容 packed 与 unpacked 编码。 */
    private static final class ProtoReader {
        private final byte[] data;
        private int pos;
        private int field;
        private int wire;

        ProtoReader(byte[] data) {
            this.data = data;
        }

        boolean next() {
            if (pos >= data.length) {
                return false;
            }
            long tag = varint();
            field = (int) (tag >>> 3);
            wire = (int) (tag & 7);
            return true;
        }

        int field() {
            return field;
        }

        int wire() {
            return wire;
        }

        boolean hasRemaining() {
            return pos < data.length;
        }

        long varint() {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= data.length) {
                    throw new IllegalStateException("Worker 响应截断");
                }
                int b = data[pos++] & 0xFF;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
            }
            throw new IllegalStateException("Worker 响应 varint 非法");
        }

        byte[] bytes() {
            long length = varint();
            if (length < 0 || length > data.length - pos) {
                throw new IllegalStateException("Worker 响应长度非法");
            }
            byte[] value = new byte[(int) length];
            System.arraycopy(data, pos, value, 0, (int) length);
            pos += (int) length;
            return value;
        }

        String string() {
            return new String(bytes(), StandardCharsets.UTF_8);
        }

        boolean bool() {
            return varint() != 0;
        }

        void skip() {
            switch (wire) {
                case 0 -> varint();
                case 1 -> pos += 8;
                case 2 -> {
                    long length = varint();
                    pos += (int) length;
                }
                case 5 -> pos += 4;
                default -> throw new IllegalStateException("Worker 响应包含未知字段类型");
            }
        }
    }
}
