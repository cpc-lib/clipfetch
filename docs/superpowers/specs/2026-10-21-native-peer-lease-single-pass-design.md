# Native Peer 租约单次扫描设计规格

## 目标

降低 Native multiplexed Actor 获取 Peer 租约时的锁内扫描与重复计算，同时保持现有租约选择结果、失败 Peer 受控回退、冷却规则和网络行为不变。

## 根因与证据

- `PeerMultiplexedRound` 的 Actor 通过 `PeerHealthTracker.leaseExclusive(remaining, availability, failedThisRound)` 获取独占 Peer。
- 当前实现先扫描一次排除 `failedThisRound` 的候选；没有候选时，再从头扫描一次全部候选以实现受控回退。
- 两次扫描都在 `PeerHealthTracker` 的 `synchronized` 监视器内进行，并重复执行 Peer 状态、Bitfield 可用性和评分判断。
- 历史诊断日志（最后写入 2026-10-20，早于当前修复）显示 `noWorkWaits` 很高、`timeout` 占主要失败原因；当前没有未完成下载任务，因此这些数据只用于定位调度热点，不能作为本轮吞吐基准。

## 方案

将带轮次失败过滤的租约选择改为一次 Peer 扫描，同时维护两个最佳候选：

1. 对每个 Peer 只执行一次现有的 `tried`、冷却、`inFlight`、Piece 可用性和评分检查。
2. 未在 `failedThisRound` 中的候选进入“首选候选”；已失败候选进入“回退候选”。两个候选组都沿用现有评分和稳定顺序。
3. 扫描结束后，首选候选非空就返回它；只有首选候选为空时才返回回退候选。
4. 只对最终返回的 Peer 增加一次 `inFlight`；没有候选时不改变任何记录。
5. 不带失败集合的既有 `leaseExclusive` 重载继续使用同一选择逻辑，但不会产生额外回退扫描。

逻辑等价关系：当前两阶段算法先从未失败候选中选最高分；若该集合为空，再从全部可用候选中选最高分。由于第二阶段只有在第一阶段为空时才会选出候选，单次扫描记录两个分组的最高分即可得到相同结果。

## 并发与不变量

- 保持现有 `synchronized` 方法边界；候选状态读取、评分、最终 `inFlight` 增加仍在同一个 `PeerHealthTracker` 监视器内完成。
- `failedThisRound` 只读，不由 tracker 修改；跨轮失败记录、冷却时间和健康评分不改变。
- `PeerPieceAvailability` 的 `couldHave`/`confirmed` 过滤、线程亲和加分、吞吐/失败/RTT 评分和稳定 tie-break 规则不改变。
- 首选候选优先级高于回退候选，即使回退候选评分更高；这与现有受控回退语义一致。
- 选中 Peer 的 `inFlight` 只增加一次；调用方仍通过 `release()` 释放。

## 验证

在 `PeerHealthTrackerTest` 覆盖：

- 存在未失败候选时，即使失败候选评分更高，也优先租用未失败候选。
- 所有可用候选都在失败集合时，冷却未结束前不回退，冷却结束后仍允许原有受控回退。
- 不带失败集合的既有租约重载仍可选中可用 Peer。
- 候选成功租约后只占用一个 `inFlight`，释放后可再次租用。

完成标准：测试先以预期的缺失/不等价行为失败，再以最小生产改动通过；随后通过 `PeerBlockMailboxTest`、`PeerHealthTrackerTest`、`PeerRoundConnectionPoolTest` 聚焦测试、后端全量测试、clean compile、diff/BOM 检查，并重启 8082 后端。

## 非目标

- 不改变 Peer 数量、Actor 探索窗口、Block 请求窗口、超时、冷却时长或网络协议。
- 不引入候选缓存、定时任务、线程、队列、数据库、API 或新配置项。
- 不修改 `awaitLease` 的等待时长或唤醒策略；Peer 冷却等待优化 B 另行设计和验证。
- 没有可重复的未完成 Native 任务时，不报告具体下载速度或百分比提升。
