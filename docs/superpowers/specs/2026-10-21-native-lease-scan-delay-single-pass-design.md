# Native 租约选择与冷却边界单次扫描设计规格

## 目标

减少 Native multiplexed Actor 在无可租 Peer 时的重复 Piece 可用性扫描：当前 `awaitLeaseWithStatus()` 先调用租约选择，再调用 `nextLeaseDelayNanos()`，两者在同一 `PeerHealthTracker` 同步边界内重复检查空闲 Peer 是否能服务剩余 Piece。本设计将两项结果合并到一次扫描，同时保持租约选择、失败 Peer fallback、冷却等待和 `inFlight` 语义不变。

## 根因与证据

- 当前 `PeerMultiplexedRound` 通过 `PeerHealthTracker.awaitLeaseWithStatus()` 获取 Actor 的独占 Peer。
- `awaitLeaseWithStatus()` 的无候选分支先执行 `leaseExclusive(pieces, availability, failedThisRound)`；若返回 `null`，随后执行 `nextLeaseDelayNanos(pieces, availability)`。
- 两个调用都在 `PeerHealthTracker` 的同步监视器内运行；前者对候选 Peer 做 Piece availability 判断，后者对空闲且可服务 Peer 再做一次 `couldHave` 判断。
- 上一轮已将单次租约选择内部的 `couldHave` 与 `confirmed` 双遍历合并；本轮剩余成本是“租约选择”和“冷却边界计算”之间的跨方法重复扫描。
- 当前没有新的 `native_perf` 或 `native_multiplexed_diagnostics` 下载样本，也没有可重复的未完成 Native 任务，因此本规格只针对可验证的调度扫描行为，不承诺吞吐百分比。

## 方案

### 1. 组合扫描结果

在 `PeerHealthTracker` 增加包内结果记录，包含：

- `peer`：按照现有租约规则选出的 Peer，或 `null`；
- `nextLeaseDelayNanos`：空闲且可服务剩余 Piece 的 Peer 中最早冷却剩余时间。

该结果只供 Native 调度包内使用，不增加 HTTP API、配置、数据库字段或网络协议。组合扫描以现有 `peers` 列表作为唯一遍历来源；`records` 与 `peers` 在现有同步维护不变量下保持同一组 Peer，冷却边界不再另行遍历 `records`。

### 2. 单次 Peer/Piece 扫描

组合扫描复用现有租约辅助逻辑，并在同一轮 Peer 遍历中同时计算选择结果和等待边界：

1. 组合扫描开始时只读取一次 `now`；`pieces == null` 或为空时返回 `peer == null` 与 `nextLeaseDelayNanos == 0`，保持现有空集合行为。
2. 对 `inFlight > 0` 的 Peer 直接跳过；busy Peer 不参与租约选择，也不参与现有冷却边界计算。
3. 对每个空闲 Peer 只遍历一次 `pieces`：
   - `UNKNOWN`：立即视为可服务，不产生 confirmed bonus；
   - `ABSENT`：继续检查下一个 Piece；
   - `PRESENT`：视为可服务并记录 confirmed bonus，然后结束该 Peer 的 Piece 扫描；
   - `availability == null`：立即视为可服务且没有 confirmed bonus。
4. 对可服务的空闲 Peer，先计算 `max(0, cooldownUntil - now)` 并更新最早冷却边界。这个计算不受 `tried` 或失败集合过滤影响，与现有 `nextLeaseDelayNanos()` 一致：任何空闲且可能服务剩余 Piece 的 Peer 都可以决定等待窗口。
5. 之后才应用现有租约选择过滤：`tried`、失败 Peer 排除/受控 fallback、冷却时间和原有评分。非失败候选仍优先于失败 fallback；冷却中的 Peer 不会被租用。
6. 评分、preferred-thread 加分、confirmed 加分、稳定 tie-break、最终 `inFlight++` 保持原实现；选中 Peer 最多增加一次租约计数。

### 3. 等待路径接入

`awaitLeaseWithStatus()` 每轮只调用组合扫描一次：

- 有选中 Peer 时，直接返回现有 `LeaseWaitResult`，不改变 `missedInitialLease`；
- 没有选中 Peer 时，直接使用组合结果中的 `nextLeaseDelayNanos` 计算 `boundedLeaseWaitNanos`，不再额外调用 `nextLeaseDelayNanos()`；
- `notifyAll`、中断、任务 deadline、1ms progress floor 和静默停滞判断保持不变。

现有包内 `nextLeaseDelayNanos()` 保留，已有调用方和回归测试继续验证原始冷却边界语义；本轮只从 Actor 等待热路径移除重复调用。

## 并发与语义不变量

- 组合扫描在现有 `PeerHealthTracker` 同步边界内读取 Peer 状态、availability、冷却和 `inFlight`，不暴露中间状态。
- 不改变 `Health.cooldownUntil`、失败计数、指数冷却、失败 Peer fallback、Peer 排序或 Actor 数量。
- 不改变 `PeerPieceAvailability` 的 `UNKNOWN/ABSENT/PRESENT` 解释，也不复制或暴露内部 `BitSet`。
- `nextLeaseDelayNanos == Long.MAX_VALUE` 仍表示没有可预估的空闲服务 Peer；`0` 仍表示已有空闲服务 Peer或空集合的旧行为；等待边界仍由 `boundedLeaseWaitNanos` 负责 1ms 下限。
- 释放、成功、失败、发现和 amnesty 的现有 `notifyAll` 唤醒行为不变。

## 测试与验收

在 `PeerHealthTrackerTest` 增加：

1. 组合扫描在没有可服务 Peer 时返回空 Peer 与无界冷却，并用计数型 Piece 集合确认每个 Peer 在同一轮只执行一次 availability 状态遍历；代表 Piece 的选择与每个 Peer 的状态遍历分别计数，避免把租约评分所需的代表 Piece 读取误判为重复扫描。旧实现应先因缺少组合扫描结果接口而 RED。
2. 两个冷却且可服务的 Peer 返回最早冷却时间，确认组合结果与现有 `nextLeaseDelayNanos()` 语义一致。
3. 保留现有失败 Peer fallback、busy Peer、不可服务 Peer、等待唤醒和租约释放测试，确认选择和等待边界未变。

验证顺序：

- 先运行 `PeerHealthTrackerTest` 的 RED/GREEN；
- 运行 `PeerHealthTrackerTest`、`PeerBlockMailboxTest`、`PeerRoundConnectionPoolTest`、`PeerIncrementalFrameReaderTest`、`NativeWorkerJobManagerTest`；
- 运行后端 `mvn -q clean test`、`mvn -q clean compile -DskipTests` 与 `mvn -q test`；
- 执行 `git diff --check` 与全仓无 BOM 检查；
- 重启 8082 后端并确认唯一监听者；
- 不恢复、创建或改变任何用户磁力任务。

## 范围边界

- 不改 Native 配置、网络协议、socket 超时、Block 请求窗口、Mailbox、Checkpoint、Worker、数据库、API 或前端。
- 不增加线程、队列、定时任务、缓存或新的外部依赖。
- 不删除现有 `nextLeaseDelayNanos()`，不改变非 Actor 调用方的可见行为。
- 没有可重复未完成任务时，不报告下载速度、吞吐或百分比提升。
