# Native 轮次内失败 Peer 隔离设计

## 目标

降低 Native multiplexed 下载在同一 Piece 轮次内重复租用已失败 Peer 造成的超时等待和空闲 Actor 轮询，同时保留蜂群稀疏时的恢复能力。

## 当前证据与边界

历史日志显示 Native round 的 `transferWallMs` 约为 `61018..170812`，`diskCommitMs` 约为 `2019..9952`；`timeout` 是主要失败原因，`noWorkWaits` 达到 `58203..313603`。这些日志发生在上一轮 Peer admission 修复前，只能用于定位候选瓶颈，不能作为本次修复后的速度基准。

当前 `PeerMultiplexedRound` 已把失败 Peer 放入 `failedThisRound`，但该集合只用于诊断；`PeerHealthTracker` 的租约选择不读取它。失败 Peer 只经过指数冷却，冷却结束后仍可能在同一轮重新获得租约。目标磁力任务已经完成，本轮不重复下载它。

## 方案

### 1. 轮次内失败隔离

在 `PeerHealthTracker` 增加带轮次失败集合的 Actor 专用租约入口：

```java
synchronized InetSocketAddress leaseExclusive(
        Set<Integer> pieces,
        PeerPieceAvailability availability,
        Set<InetSocketAddress> failedThisRound)
```

选择顺序保持现有健康评分、Bitfield 确认和线程亲和排序，仅增加一层过滤：

1. 优先从不在 `failedThisRound` 的可用 Peer 中选择。
2. 如果没有任何未失败 Peer 可以服务剩余 Piece，再从原有候选集合中受控回退；仍遵守 `cooldownUntil`、`inFlight`、availability 和评分规则。
3. 失败集合只属于当前 `PeerMultiplexedRound`，不写入数据库、不跨任务、不改变跨轮 `Health` 记录。

现有无失败集合的 `leaseExclusive` 重载保持兼容，其他调度路径不改变。`nextLeaseDelayNanos` 保持现有冷却语义，避免为本次最小修复引入新的等待状态机。

### 2. 失败回退指标

在 multiplexed round 中，当带失败集合的租约入口返回了仍在 `failedThisRound` 中的 Peer 时，增加一个轮次本地计数 `failedPeerFallbacks`。诊断日志追加该字段，便于区分：

- `failedPeerFallbacks` 较低：失败隔离成功让 Actor 转向其他 Peer。
- `failedPeerFallbacks` 较高：当前蜂群没有足够替代 Peer，继续隔离只会让 Actor 空等，需要新的发现或更换任务。

不改变现有 `failedPeers`、`noWorkWaits`、`receivedBlocks` 和 `staleBlocksDropped` 字段含义。

### 3. 失败与成功路径

- `health.failure(endpoint)` 之后继续加入 `failedThisRound`；同一轮后续正常租约跳过该 endpoint。
- 新发现 Peer 通过现有 `mergeDiscovered` 加入共享 tracker，不自动进入失败集合，因此可立即参与探索。
- 受控回退只在没有未失败候选时发生，且仍受现有失败冷却限制；不解除冷却、不清空失败集合。
- Actor 释放、socket 复用、取消、Block 重分配、SHA-1 校验和磁盘提交流程保持不变。

## 测试策略

先为 `PeerHealthTracker` 写失败测试，再实现：

1. 有一个健康候选和一个已加入 `failedThisRound` 的候选时，租约选择健康候选。
2. 所有可用候选都在失败集合时，冷却未结束前不回退；冷却结束后允许受控回退。
3. 不带失败集合的既有租约入口行为不变。
4. 运行现有 `PeerRoundConnectionPoolTest`、`PeerHealthTrackerTest` 和完整 Maven 测试。

本轮验证重点是失败 Peer 重租次数、`failedPeerFallbacks`、`noWorkWaits`、`transferWallMs` 的变化；没有未完成且可重复的任务时，不声称实际下载速度提升。

## 不采用的方案

- 直接把 `peer-max-in-flight-blocks` 或 stall timeout 调低：可能误伤高 RTT 健康 Peer，且不解决失败 Peer 在同轮重租。
- 直接降低全局 Actor 硬上限：会掩盖 Peer admission 问题，并可能降低健康蜂群吞吐。
- 重写完整 AIMD/队列状态机：改动范围过大，当前日志尚不足以证明需要架构级重写。
