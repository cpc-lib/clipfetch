# Native Peer Admission 修复设计

## 目标

降低 Native multiplexed 下载中由错误 Peer 晋升和并发膨胀造成的网络等待。只有完成 SHA-1 校验的 Piece 才能使 Peer 进入已验证集合；重复失败或退化的已验证 Peer 不再继续贡献 Actor 配额。

## 证据与根因

2026-10-20 的 Native 日志显示，单轮处理 256 个 Piece 时 Actor 数从 232 增长到 241，失败 Peer 为 161–255，`noWorkWaits` 达到 129597–171461，`transferWallMs` 约 61037–74849。同期 `degraded` 最高 150，但 `workerSlots` 仍维持 236–241。

代码中 `PeerMultiplexedRound` 当前只要一个 Peer 会话收到过 Block，就调用 `PeerHealthTracker.success`；但 `PeerHealthTracker` 的成功语义和注释要求成功应建立在完整 SHA-1 Piece 上。`computeTargetActors` 又按历史 `verifiedPeerCount + exploration` 扩大 Actor 数，导致部分传输 Peer 被提前晋升，退化 Peer 也继续推高并发目标。

## 方案

### 1. Peer 晋升条件

在 `PeerHealthTracker` 增加会话结果入口：

```java
synchronized void observeSession(InetSocketAddress peer,
                                 long bytes,long elapsedNanos,
                                 int verifiedPieces) {
    if (verifiedPieces < 1) return;
    if (bytes > 0 && elapsedNanos > 0) success(peer,bytes,elapsedNanos);
    else success(peer);
}
```

`PeerMultiplexedRound` 为每个 Actor 统计本次会话成功交付的完整 Piece 数，只有该数大于零才调用 `observeSession`。仅收到 Block、超时、断开或被重新分配的 Peer 不晋升；已有失败路径保持不变。

### 2. Actor admission

在 `PeerHealthTracker` 增加健康已验证 Peer 计数：`successes > 0` 且不满足现有退化条件（连续失败至少 2 次，或无成功且失败至少 3 次）。`PeerMultiplexedRound.computeTargetActors` 改用该计数加现有探索槽，并继续受 `maxActors` 限制。探索槽、连接上限、超时和磁盘提交策略不改。

这样健康 Peer 仍可逐步扩容，退化 Peer 会从 Actor 目标中退出；不会引入新的硬编码并发上限或新的环境参数。

## 测试

- `PeerHealthTrackerTest` 验证：零个已验证 Piece 的会话不增加 verified Peer；完成一个已验证 Piece 的会话会增加 verified Peer；连续失败的已验证 Peer 不贡献健康已验证计数。
- 验证 `computeTargetActors` 在一个健康已验证 Peer、一个退化已验证 Peer和探索槽存在时，只按健康 Peer 加探索槽计算。
- 运行 Native 现有回归测试、完整 Maven 测试和编译检查。
- 真实速度基准暂不在本次实现中执行：用户给定的 Ubuntu 镜像任务已完成，重复下载会造成无意义的带宽消耗。

## 不采用的方案

- 直接把 multiplexed Actor 硬上限降到 96/128：实现简单，但可能压低健康蜂群吞吐，并掩盖成功状态错误。
- 立刻重写完整 AIMD admission：潜在收益更大，但超出本次已确认的最小根因修复范围。
