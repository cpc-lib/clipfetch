# Native Peer 可用性单次扫描设计规格

## 目标

降低 Native multiplexed Actor 租约热路径在 `PeerHealthTracker` 监视器内的 Piece 可用性扫描开销，同时保持现有 Peer 选择结果、失败 Peer fallback、冷却与租约计数语义不变。

## 背景与证据

- `PeerMultiplexedRound` 的 Actor 会持续调用 `PeerHealthTracker.leaseExclusive()`。
- 当前实现对每个候选 Peer 先扫描剩余 Piece 判断 `couldHave`，通过后又重新扫描剩余 Piece 判断是否存在 confirmed bitfield。
- 两次扫描都发生在 `PeerHealthTracker` 的同步边界内；历史 Native 诊断曾出现十万级 `noWorkWaits`，因此该重复扫描会放大锁持有和调度 CPU 开销。
- 上一轮已将 `PeerBlockMailbox.remainingPieces()` 改为稳定快照，当前剩余成本主要是对同一快照的重复遍历。
- 当前没有可重复的未完成 Native 下载任务，不能用本轮规格宣称实际吞吐提升。

## 方案

### 1. 单次状态查询

在 `PeerPieceAvailability` 增加包内状态查询，返回三种状态：

- `UNKNOWN`：Peer 没有 bitfield 报告，仍按现有规则视为可能拥有 Piece；
- `ABSENT`：Peer 报告了 bitfield 且没有该 Piece；
- `PRESENT`：Peer 报告了 bitfield 且拥有该 Piece。

该查询只读现有不可变 availability 快照，不复制 `BitSet`，不改变已有公开方法的语义。

### 2. 租约扫描

`PeerHealthTracker` 对每个候选 Peer 只遍历一次 `pieces`：

- `UNKNOWN` 立即使 Peer 可服务，并结束该 Peer 的 Piece 扫描，因为未知 Peer 不可能再产生 confirmed 加分；
- `ABSENT` 继续检查下一个剩余 Piece；
- `PRESENT` 同时使 Peer 可服务并记录 confirmed 加分，然后结束扫描。

现有 `performanceScore`、preferred socket 加分、confirmed 加分、失败 Peer 排除/受控 fallback、`inFlight` 更新和最终排序均保持不变。`availability == null` 的旧行为仍视为可服务且没有 confirmed 加分。

### 3. 范围边界

- 不改变 Peer 并发数、超时、请求窗口、冷却时间、Actor 数量或网络协议。
- 不新增配置、线程、数据库字段、API 或持久化状态。
- 不改动 `nextLeaseDelayNanos()`、Mailbox、Checkpoint 或 Worker 轮次流程。

## 测试与验收

- 将已有 Piece 迭代计数回归从“双扫描”调整为“每个候选 Peer 单次扫描”，先验证旧实现按预期失败。
- 增加 confirmed Peer 仍优先于 unknown Peer 的回归，确保排序加分没有因合并扫描而丢失。
- 运行 Peer availability/health/mailbox/connection-pool 相关测试、后端全量测试和 clean compile。
- 检查 `git diff --check` 与全仓文本 UTF-8 BOM。
- 重启 8082 后端并验证唯一监听者。
- 最终不报告下载速度百分比，除非后续出现可比的真实未完成 Native 任务样本。
