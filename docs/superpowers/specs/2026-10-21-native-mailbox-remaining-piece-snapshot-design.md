# Native Mailbox 剩余 Piece 快照缓存规格

## 目标

降低 Native BitTorrent 调度热路径中 `PeerBlockMailbox.remainingPieces()` 的重复分配与全量扫描开销，保持现有租约、网络请求和 Piece 完成行为不变。

## 背景与证据

- `PeerMultiplexedRound` 的 Actor 租 Peer 循环会反复调用 `remainingPieces()`。
- 当前实现每次都在 mailbox 锁内遍历 `remainingByPiece`，并创建一个新的 `HashSet`。
- `remainingByPiece` 只在 mailbox 构造时增加 Piece 计数，在 `deliver()` 接受 Block 后减少计数。
- 因此，剩余 Piece 集合只会在某个 Piece 的最后一个 Block 完成时发生变化；普通 Block 完成不需要刷新集合。

## 方案

在 `PeerBlockMailbox` 内维护一个按首次出现顺序保存 Piece 索引的可变集合，以及其不可变快照：

1. 构造 mailbox 时收集所有 Piece，并建立初始快照。
2. `remainingPieces()` 继续使用现有 `synchronized` 边界，但直接返回当前不可变快照，不再遍历 `remainingByPiece` 或每次分配集合。
3. `deliver()` 使某个 Piece 的 `remainingByPiece` 计数降为零时，从可变 Piece 集合删除该索引，并创建下一份不可变快照。
4. `deliver()` 只完成普通 Block 时不刷新快照。
5. 快照使用不可变集合；调用方不能通过返回值修改 mailbox 状态。

## 并发与不变量

- 保留 `remainingPieces()`、构造和 `deliver()` 当前的 mailbox 同步边界。
- 快照引用只在 mailbox 锁内替换；返回后集合不可变，因此 Actor 可以在锁外安全读取返回值。
- 快照包含且仅包含仍有未完成 Block 的 Piece。
- Piece 的顺序保持构造时首次出现的顺序；不改变现有 Piece 选择或健康 Peer 租约逻辑。
- `remainingByPiece` 继续作为 Block 计数的事实来源；本优化不改变计数语义。

## 验证

新增 mailbox 单元测试覆盖：

- 初始快照包含全部剩余 Piece，且重复读取返回同一快照对象，证明热路径没有重复创建集合。
- 快照返回值不可变。
- Piece 尚有 Block 未完成时仍在快照中，最后一个 Block 被接受后才从新快照中移除。
- 普通 Block 完成不会错误地提前移除 Piece。

完成标准：相关测试先以预期的缺失实现失败，再在最小生产改动后通过；随后通过 mailbox/health/connection-pool 聚焦测试、后端全量测试和 clean compile。

## 非目标

- 不改变 Peer 并发数、请求窗口、超时、端口、配置、数据库或 API。
- 不改动 `PeerHealthTracker` 的嵌套扫描或 BitSet 表达。
- 不新增运行时遥测、调度策略、网络协议行为或缓存失效机制。
