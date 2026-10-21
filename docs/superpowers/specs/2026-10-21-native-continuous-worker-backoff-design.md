# Native 连续 Worker 失败重试与停滞退避优化设计

## 1. 目标

减少 Native 连续 Worker 在单轮 socket 超时后重复执行同一轮网络工作的时间浪费，并统一失败重试与无进展等待的剩余预算边界，同时保持任务取消、版本 CAS、最大重试次数和停滞终止语义不变。

本设计只处理 `NativeWorkerJobManager.runContinuous`。不改变 Peer 选择、Peer 冷却、Piece 请求、网络协议、持久化接口或连续 Worker 的配置项。

## 2. 当前问题与证据

`runContinuous` 当前在每轮 `invoker.run` 抛出 `IOException` 后最多执行三次同轮尝试，失败间隔为 200ms、400ms。Peer/Block socket 超时已经在 Piece 协调器内部消耗过本轮网络预算并释放失败 Peer；连续 Worker 再把同一任务、同一 revision 的完整轮次立即重跑，容易重复发现、扫描和连接。

成功返回但 `verifiedBytes` 没有增长时，Worker 通过 `stalled` 计数并休眠 `400ms * stalled`。失败等待和停滞等待分别计算剩余时间，存在重复策略和乘法溢出风险；两条路径也没有共享清晰的“本轮应等待多久”边界。

当前没有可重复的未完成 Native 下载任务，因此这些判断是代码级调度优化，不足以支持下载速度百分比结论。

## 3. 方案

### 3.1 socket 超时不重复同轮重试

在 `NativeWorkerJobManager` 增加包可见纯策略：

```java
static boolean shouldRetryContinuousFailure(IOException failure)
```

规则：

1. 现有 `NativeNetworkBudget.deadlineExceeded(failure)` 分支继续优先处理，保持全局网络 deadline 到期后返回 partial 的行为。
2. 其余 `SocketTimeoutException` 视为本轮停滞，不再在同一 `revision` 上重复调用 `invoker.run`；使用当前 durable task 状态生成无下载结果的 `RoundReport`，回到外层连续轮次。
3. 其他 `IOException` 继续使用现有每轮最多三次尝试和任务状态/revision 检查。

这样，单轮 socket 超时会进入已有的停滞计数、下一轮 discovery/Peer 选择和最大停滞保护，而不是在同一轮重复消耗相同网络路径。任务已暂停、revision 已改变或线程被中断时，现有异常退出优先级不变。

### 3.2 统一预算内退避策略

增加包可见纯策略：

```java
static long boundedContinuousBackoffMillis(
        int retryFailures,int stalledRounds,long remainingNanos)
```

具体规则：

- `retryFailures <= 0` 不贡献失败退避；否则以现有 200ms 为基数按 2 的幂增长。
- `stalledRounds <= 0` 不贡献停滞退避；否则以现有 400ms 为基数按 2 的幂增长。
- 取两种退避的较大值，作为当前状态的单次等待窗口；使用饱和乘法避免溢出。
- `remainingNanos <= 0` 返回 0；否则返回值不超过 `remainingNanos` 的整毫秒部分。

重试路径传入当前轮失败次数，停滞路径传入当前连续停滞轮数。现有前两次普通失败仍得到 200ms/400ms，第一次停滞仍得到 400ms；变化只在策略复用、溢出安全和 socket 超时不重复同轮重试处体现。

## 4. 并发、状态与错误边界

- 不新增线程、锁、定时任务、队列、配置、数据库字段或 HTTP API。
- 不改变 `READY`/`PAUSED`/`COMPLETED` 状态判断、revision CAS、取消中断、全局 `NativeNetworkBudget` 和 `stopPartial`。
- 只对仍处于当前 `READY` revision 的非全局 socket 超时跳过同轮重试；状态变化仍抛出原异常，交由现有外层安全处理。
- synthetic `RoundReport` 只携带当前任务状态，不虚构 candidate Peer、下载结果或提交 Piece；外层仍按实际 durable `verifiedBytes` 更新停滞计数。
- 不报告未经可重复任务基准支持的下载速度提升。

## 5. 测试与验收

在 `NativeWorkerJobManagerTest` 覆盖：

1. 普通 `SocketTimeoutException` 不触发同轮重试，`NativeNetworkBudget.DEADLINE_EXCEEDED` 仍由现有 deadline 分支单独处理。
2. 普通 `IOException` 仍被标记为可重试。
3. 退避策略在无等待、200/400ms 重试、400/800ms 停滞、剩余预算截断、过期预算和大计数溢出情况下返回确定结果。
4. 通过真实 `submitContinuous` 路径验证：第一轮抛 socket 超时只调用一次 `RoundInvoker`，下一轮仍可在同一 READY revision 上执行并完成；不改变任务状态保护。

验证顺序：先运行该测试的 RED/GREEN，再运行 Native 相关测试、`mvn -q clean test`、`mvn -q clean compile -DskipTests`、`mvn -q test`、`git diff --check` 和无 BOM 扫描；最后重启 8082 后端并确认唯一监听者。不恢复或创建用户给定磁力任务。
