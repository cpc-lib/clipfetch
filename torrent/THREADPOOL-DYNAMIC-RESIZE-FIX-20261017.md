# ClipFetch Native Torrent — 动态 Peer 刷新引发线程池参数异常修复

日期：2026-10-17
项目：`D:\release\clipfetch`

## 用户提供的异常

```text
java.lang.IllegalArgumentException: null
    at java.util.concurrent.ThreadPoolExecutor.setMaximumPoolSize(ThreadPoolExecutor.java:1686)
    at cc.ivera.magnet.engine.piece.PieceDownloadCoordinator.parallel(...)
    at cc.ivera.magnet.engine.piece.NativeTaskWorkerRoundService.runOnce(...)
    at cc.ivera.magnet.engine.piece.NativeWorkerJobManager.runContinuous(...)
```

## 根因（已复现）

`TaskPeerActorPool.borrow(owner,requested)` 故意保留前几轮的核心线程与 TCP 连接亲和，池实际可能为 `corePoolSize=64`、`maximumPoolSize=64`。当前轮依据健康 Peer 数将**逻辑并发窗口**降至 8，动态发现新 Peer 后再将逻辑窗口由 8 升到 16。但旧 `PieceDownloadCoordinator.parallel` 直接调用：

```java
resizing.setMaximumPoolSize(updated); // 16 < retained corePoolSize(64)
resizing.setCorePoolSize(updated);
```

Java 的 `ThreadPoolExecutor` 必须始终满足 `maximumPoolSize >= corePoolSize`。因此第一行同步抛出 `IllegalArgumentException`，导致整个连续任务被 Worker 捕获为失败。这里不是 BitTorrent Peer 传输异常，也不是 Checkpoint 数据损坏。

## 修复

1. `TaskPeerActorPool.ensureCapacity(ThreadPoolExecutor,int)`：在同一个 executor monitor 下，采用**只扩不缩**的安全容量算法：
   - `targetCore=max(existingCore,requested)`；
   - `targetMaximum=max(existingMaximum,targetCore)`；
   - **先扩 maximum，再扩 core**；如果无需变化则不调用 setter。
   - 已停止的线程池报 `IllegalStateException`，非法范围立即拒绝。
2. `TaskPeerActorPool.borrow` 与 `PieceDownloadCoordinator.parallel` 动态 Peer 刷新都调用这一统一方法，不再由协调器直接设置核心/最大线程数。
3. 保留按本轮 `workers` 控制的实际活跃提交窗口，**不因为保留 64 个核心线程就盲目同时提交 64 个 Piece**。真实任务停止时由原有 actor scope 负责关闭线程池。

## 回归测试

- `TaskPeerActorPoolTest.rediscoveryGrowthNeverLowersMaximumBelowCoreFromPreviousRound`：上一轮 core/max=64；本轮借用 8；动态扩容到 16 后不会降低原 core/max；后续扩到 96、再请求 12 也不会发生参数异常，已运行线程仍可正常处理任务。
- `TaskPeerActorPoolTest.rediscoveryAndBorrowUseOneConsistentCapacityLock`：多个线程同时增容，最终核心/最大上限一致。
- `PeerActorRediscoveryRegressionTest.activeRoundRefreshExpandsLogicalSlotsWithoutShrinkingRetained64CorePool`：通过真实 `PieceDownloadCoordinator.runRound` 和 mock TCP Peers 触发动态刷新分支，验证从保留核心64、当前活跃1到刷新活跃2，线程池不再抛异常，并仍能提交 SHA1 验证的 Piece。
- 原有 `ParallelPieceDownloadCoordinatorTest` 覆盖独立的动态发现后继续下载。

```powershell
cd D:\release\clipfetch\backend
mvn -q '-Dtest=TaskPeerActorPoolTest,PeerActorRediscoveryRegressionTest,ParallelPieceDownloadCoordinatorTest' test
mvn -q clean test
git -C D:\release\clipfetch diff --check
```

本轮最终验证：`mvn -q clean test` 退出码 0，**364 项测试、0 failures、0 errors、5 skipped**，共 94 个测试套件；`git diff --check` 退出码 0。精确重现的集成测试经过一次模拟 Peer 测试夹具清理调整后通过，日志证明 `native_peer_refresh initial=1 discovered=2 added=2 active=2`，并持续提交有效 Piece。最后只读检查：原任务 `PAUSED`、已验证 2,164,785,152 bytes、revision 794，Spring Boot PID 55812 未由本轮重启。

## 影响与部署

- 不调整 BT 协议实现、Checkpoint 文件、用户进度数据或数据库 schema。
- 修复后的逻辑仅在下一次正确构建、正常重启 Spring Boot 之后生效；运行中的旧 JVM 不会自动加载新类。
- 对运行中的任务不得擅自重启；维护时先正常暂停、保留 Checkpoint，重启后走现有断点续传。
- 尚未执行公网带宽速度对比，本修复解决连续 Worker 异常退出，并不直接保证吞吐提升。
