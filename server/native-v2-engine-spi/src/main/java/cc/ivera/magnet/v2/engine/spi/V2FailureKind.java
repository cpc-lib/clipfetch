package cc.ivera.magnet.v2.engine.spi;

/** Worker 失败的稳定分类，重试与否由分类决定，不解析英文错误文本。 */
public enum V2FailureKind {
    /** 请求参数非法，永久失败 */
    INVALID_ARGUMENT,
    /** 元数据哈希与提交身份不一致，永久失败 */
    HASH_MISMATCH,
    /** 存储路径不安全，永久失败 */
    UNSAFE_PATH,
    /** 资源会话冲突（如 Worker 重启后会话丢失、epoch/lease 不符），需要重新挂载 */
    CONFLICT,
    /** Worker 侧存储不可用，需要人工处理 */
    STORAGE,
    /** 临时不可用（网络、超时、过载），可有界重试 */
    TRANSIENT,
    /** 未分类的内部错误，永久失败 */
    INTERNAL
}
