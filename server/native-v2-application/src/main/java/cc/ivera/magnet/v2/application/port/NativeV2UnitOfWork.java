package cc.ivera.magnet.v2.application.port;

/** 事务边界：实现层负责提交/回滚，应用层只编排业务步骤。 */
public interface NativeV2UnitOfWork {

    void run(Runnable work);
}
