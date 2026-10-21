# Native Actor admission 失败 Peer 过滤设计

## 目标

避免 Native multiplexed 下一轮继续为刚刚发生过失败的已验证 Peer 分配 Actor 配额，降低无效连接、超时和空闲等待；失败 Peer 仍保留在探索和受控回退路径中，不做永久封禁。

## 证据与假设

历史 Native 日志中，单轮约有 200 个 Actor，但 `failedPeers` 达到 166–313，且 `timeout` 是主要失败原因。当前 `healthyVerifiedPeerCount()` 只要求 `successes > 0 && consecutiveFailures < 2`，所以只失败过一次的 Peer 仍会贡献已验证 Actor 配额；`computeTargetActors()` 再叠加最多 64 个探索槽，下一轮仍可能启动过多 Actor。

本轮没有修复后的真实下载样本，因此这是基于代码和历史日志的单一候选根因，不把它表述为已实测确认的速度结论。

## 方案

把健康已验证 Peer 的 admission 条件收紧为：

```java
h.successes > 0 && h.consecutiveFailures == 0
```

只影响 `healthyVerifiedPeerCount()` 对 Actor 目标的统计。`failure()` 的冷却、跨轮记录、`lease()`/`leaseExclusive()` 候选选择、轮次内失败隔离、探索槽和受控回退全部保持不变。Peer 后续完成一个已验证 Piece 后，现有 `success()` 会清零连续失败计数并重新进入健康配额。

## 测试与边界

- 新测试先让一个已验证 Peer 只失败一次，断言它不再计入 Actor 目标；当前实现应因仍使用 `<2` 而失败。
- 保留既有连续失败两次的 admission 测试，确保退化 Peer 仍被排除。
- 运行聚焦测试、全量测试、编译、diff/BOM 检查并重启 8082 后端。
- 不重复下载已完成 Ubuntu 任务；没有新的未完成任务基准时，不声称实际速度提升。
