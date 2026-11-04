package cc.ivera.magnet.v2.engine.spi;

/** 恢复快照：恢复提示，不是授权真相或已验证数据的永久凭证。 */
public record ResumeSnapshot(byte[] resumeData, long version) {
}
