package cc.ivera.magnet.v2.engine.spi;

/**
 * Native v2 下载引擎 SPI：Worker 无关的命令、快照与恢复接口。
 * 实现方负责应用配置的 RPC 超时与消息大小限制；
 * 失败统一抛出 {@link NativeV2EngineException}，重试与否由 {@link V2FailureKind} 决定。
 */
public interface NativeV2Engine extends AutoCloseable {

    /** 健康检查：Worker 离线或异常时返回 connected=false，不得抛出异常。 */
    NativeV2Health health();

    /** 仅解析元数据，返回解析完成的 .torrent 字节。 */
    byte[] resolveMetadata(ResolveMetadataCommand command);

    /** 挂载资源会话（可选 resume data 恢复）。 */
    void addTorrent(AddTorrentCommand command);

    /** 全量下发文件优先级。 */
    void updateFilePriorities(UpdatePrioritiesCommand command);

    void pause(EngineCommand command);

    void resume(EngineCommand command);

    /** 低开销快照（总进度刷新）。 */
    NativeV2Snapshot snapshot(EngineCommand command);

    /** 保存 resume data。 */
    ResumeSnapshot saveResume(EngineCommand command);

    /** 移除会话；removeData 为 true 时同时清理无引用数据。 */
    void remove(EngineCommand command, boolean removeData);

    /** 释放引擎持有的连接资源。 */
    @Override
    void close();
}
