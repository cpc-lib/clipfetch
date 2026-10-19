# Native Mailbox Actor 吞吐优化设计

状态：用户已批准架构、数据流、异常处理与验收标准；规格自检完成，等待用户最终复核。

日期：2026-10-18

## 1. 背景与现场证据

实验入口 Native Worker 下载 Ubuntu 26.04 Live Server ISO 时表现为短时突发传输与长时间空转交替。只读采样期间，任务 verified bytes 从 `248250368` 增至 `291241984`，平均 verified 吞吐约 `221764–262870 B/s`，但多次出现实时网络 `0 B/s`。Block RTT P95 持续约 `4096 ms`。

Peer 池约有 92 个候选，其中约 25 个曾成功传输、约 90 个达到故障判定。运行配置虽然设置 `TORRENT_NATIVE_PARALLEL_PIECE_WORKERS=128`，当前稳定路径的 `AdaptivePeerConcurrency` 仍从 8 个槽位起步，并按整轮 Piece 成功率扩容。单 Piece 对坏 Peer 最多串行尝试 4 次，使公网高故障率被放大为长尾等待。

现有多路 `PeerMultiplexedRound` 不能直接启用：它只把固定的前 N 个 Peer 各绑定到一个 Future；Actor 遇到网络故障后退出，本轮没有补位；该路径也未接入 `PeerHealthTracker` 的健康排序、冷却和动态 Peer 刷新。直接打开 Feature Flag 可能让有效并发持续下降。

当前工作树已有 ISSUE-002 的 End-game 污染与跨轮 Socket 复用修复，涉及 `PeerBlockMailbox`、`PeerMultiplexedRound`、`PeerPieceDownloadSession` 和 `PeerRoundConnectionPool`。本设计必须保留这些未提交改动，不得覆盖或弱化迟到帧隔离、CANCEL 和 SHA-1 安全边界。

## 2. 目标

1. 把多路 Mailbox 路径改为健康感知、可自动补位的持久 Actor 监督器。
2. 在公网坏 Peer 比例较高时维持有效并发，充分使用已验证 Peer。
3. 保留 Block 级流水线、超时接管、End-game 竞速、连接复用和 Checkpoint 续传能力。
4. 保证网络线程不能直接提交磁盘或数据库状态；只有 owner 线程可以发布通过 SHA-1 的完整 Piece。
5. 提供足够的 Actor、租约和 Socket 退役指标，能够证明提速来自真实并发，而不是 UI 估算。
6. 通过 Feature Flag 保留旧 Peer-centric 路径作为即时回退方案。

## 3. 非目标

- 不改 BitTorrent 元数据格式、Checkpoint 格式、SQLite/MySQL 表结构或 Flyway 迁移。
- 不引入 Netty、异步 HTTP 客户端、Redis、MQ、调度服务或新的进程。
- 不重写为 Java NIO Selector 事件循环。
- 不改变 JWT、用户隔离、下载目录 ACL 或任务状态机语义。
- 不自动迁移、删除、暂停或重建现有用户任务。
- 不通过降低 Piece SHA-1 校验、接受迟到 Block 或复用污染 Socket 来换取速度。

## 4. 方案比较

### 4.1 直接启用现有 Mailbox

改动最少，但固定 Peer Actor 在失败后不会补位，也不使用健康评分。公网前排 Peer 失效时有效并发会快速坍缩，因此否决。

### 4.2 健康感知的持久 Actor 监督器

复用现有 `PeerBlockMailbox`、`PeerRoundConnectionPool` 和 `VerifiedPieceCheckpoint`，只重构多路网络调度。Actor 不固定绑定 Peer；一次 Peer 会话结束后可以立即租用另一个 Peer。该方案直接解决现场瓶颈，并保留既有协议安全逻辑，因此采用。

### 4.3 Java NIO Selector 全量重写

理论连接扩展性最高，但需要重写帧解析、连接生命周期、取消、超时与测试夹具，风险和范围明显超过当前问题，因此否决。

## 5. 总体架构

```text
Peer 发现与健康池
        ↓
PeerMultiplexedRound Actor 监督器
        ↓
共享 PeerBlockMailbox ←→ PeerRoundConnectionPool
        ↓
PieceBlockAssembler + SHA-1
        ↓
owner 线程 VerifiedPieceCheckpoint + 数据库 CAS
```

### 5.1 `PeerMultiplexedRound`

由固定 Peer Future 调度器改为 Actor 监督器，负责：

- 计算 Actor 目标数量并启动/补充 Actor 循环。
- 协调 Peer 独占租约，保证同一 Peer 同时只有一个 Actor。
- 监控 Actor Future；Peer 局部异常在 Actor 循环内部处理，内部不变量异常才终止整轮。
- 在 owner 线程持续排空 verified Piece 队列并触发持久提交回调。
- 在 Peer 不足时触发单个异步刷新，并把结果合并到健康池。
- 在任务取消、预算耗尽或 owner 失败时关闭本任务所有 Actor。

### 5.2 `PeerHealthTracker`

继续作为 owner/task 级健康状态源，并增加 Mailbox Actor 所需能力：

- 租用最佳 Peer，排除冷却、已被其他 Actor 占用或已知不具备剩余 Piece 的 Peer。
- 优先已验证 Peer和当前 Actor 可复用的健康连接，同时保留探索槽位。
- 接收完整 Piece SHA-1 通过后的贡献字节、RTT 和吞吐反馈。
- 接收连接失败、协议错误、哈希污染等有界失败反馈。
- 新发现 Peer 到达时，优先淘汰从未成功且连续失败的旧 Peer，即使池尚未达到 256 上限；已验证 Peer不得因刷新被淘汰。

### 5.3 `PeerRoundConnectionPool`

继续独占实际 Socket 和 BEP3 帧读写：

- 一个 Actor 在任意时刻只拥有一个 Peer Socket。
- 健康 Socket 可在同 Actor 内跨 Piece 使用，并可在下一轮按现有 task scope 规则复用。
- EOF、Choke、超时、协议异常立即关闭当前 Socket。
- 未确认 Block、CANCEL 后可能迟到的数据或部分帧会污染连接，污染 Socket 必须退役。
- `runMailbox` 返回或回调本次 Peer 会话的 Block 数、字节数、RTT 和退役原因，供健康池和指标使用。

### 5.4 `PeerBlockMailbox`

保持共享 Block 租约的唯一事实源：

- 每个 Block 只有受控 primary/backup 租约。
- 每 Peer 最多 64 个未完成 Block；每次写批次最多 16 个。
- 超时转移必须撤销旧租约并发送 CANCEL。
- End-game 双租约继续使用现有污染隔离规则。
- Actor 断开时释放其未完成租约，使其他 Actor 可以继续，不丢失其他 Peer 已交付的 Block。

### 5.5 owner 线程

网络 Actor 只能交付请求匹配的 Block，不能写 Checkpoint 或数据库。owner 线程负责：

- 组装每个 Piece 并校验 SHA-1。
- 仅把完整且 SHA-1 正确的 Piece放入 staged group。
- 按现有 group commit 策略发布 Checkpoint，并执行任务进度 CAS。
- 任务取消或失败时丢弃未发布 staged group；已经发布的 verified Piece 保留。

### 5.6 并发状态所有权

- `PeerHealthTracker`、Peer 独占租约表、刷新在途状态和 Actor 汇总指标均由监督器统一持有，Actor 不直接保留或修改这些可变对象。
- Peer 分配、租约释放、健康反馈、发现结果合并与淘汰必须经过同一个监督器同步边界串行化，避免两个 Actor 同时选中同一 Peer，或刷新线程与失败回报互相覆盖。
- Actor 只回传不可变的会话结果（Block 贡献、RTT、吞吐、结束原因和 Socket 退役状态）；监督器完成状态变更后再向该 Actor 发放下一份 Peer 租约。
- 多 Actor 对同一 Piece 的 Block 交付必须按 Block 槽位原子接收；第一个合法交付生效，重复或迟到交付按现有 End-game 污染规则处理，不能覆盖已接收数据。
- Checkpoint staging、发布和数据库 CAS 仍只允许 owner 线程执行，不纳入监督器或 Actor 的同步边界。

## 6. Actor 并发策略

Actor 绝对上限为：

```text
min(parallelPieceWorkers, 32, 本轮调度 Piece 数, 当前可租用 Peer 数)
```

`parallelPieceWorkers` 继续使用现有配置，不新增重复配置项。多路模式内部的 32 Actor 安全上限防止用户把 128 Piece Worker 配置直接放大为 128 个并发 Socket。

目标 Actor 数规则：

- 没有已验证 Peer时，最多启动 8 个探索 Actor。
- 已有已验证 Peer时，目标为“可用已验证 Peer 数 + 最多 4 个未知 Peer 探索槽位”，再受 32 Actor 上限约束。
- 新 Peer 在当前轮完成有效贡献后，监督器允许当前轮内扩容，不等待下一轮。
- Peer 进入冷却、断开或被污染退役后，其 Actor 槽位立即补租其他 Peer。
- 同一 Peer 不允许同时被两个 Actor 租用；只有在租约释放后才能再次选择。
- Mailbox 接近完成时不再补充无必要 Actor，避免 End-game 扩大污染面。

单轮 Piece 调度数量继续遵循现有 `piecesPerRound` 和剩余任务预算，不因 Actor 上限被强制降为 32。32 只限制同时活跃的 Peer Actor/Socket；Piece assembler、Block 请求和 verified 结果队列继续按既有轮次预算有界，不为每个 Actor 复制完整 Piece 缓冲区。

## 7. 数据流

1. owner 线程按现有 `piecesPerRound` 和剩余任务预算选择未完成 Piece，建立共享 Mailbox 和 Piece assembler。
2. 监督器根据健康池计算目标 Actor 数，每个 Actor 获取互斥 Peer 租约。
3. Actor 从连接池复用或建立认证 Socket，按 RTT/吞吐控制的 16–64 Block 目标窗口从 Mailbox 领取工作。
4. Actor 只接受与当前租约匹配的 PIECE 帧；合法 Block 写入对应 assembler，并记录 Piece→Peer 的贡献字节。
5. Actor 遇到 Peer 局部错误时关闭 Socket、更新健康状态、释放租约并选择下一个 Peer；该 Actor Future 不退出。
6. Piece 完成后由 owner 线程执行 SHA-1。通过后才奖励贡献者并进入 verified 队列；失败时不得提交，并临时冷却及关闭该 Piece 的全部贡献者连接，Piece 留待后续轮次重试。
7. owner 线程按现有策略发布 staged group 和数据库 CAS，网络 Actor 继续填充其他 Piece。
8. Mailbox 完成、网络预算耗尽、取消或任务级故障时，监督器结束所有 Actor 并执行连接清理。

## 8. 动态 Peer 刷新与淘汰

- 同一任务同时只允许一个刷新请求在途。
- 沿用现有 25 秒刷新节流，禁止每轮或每 Actor 洪泛 Tracker/DHT。
- 当可租用 Peer 少于 Actor 目标，或故障 Peer 达到现有阈值时触发刷新。
- 合并前先保护所有已验证 Peer和当前活跃租约。
- 对“从未成功且连续失败至少两次”的非活跃 Peer，允许在池未满时被新 Peer替换。
- 没有新 Peer时只保持冷却和有界等待，不反复删除/重加同一 endpoint。
- 刷新失败是可恢复事件，不终止仍有健康 Peer工作的下载轮次。

## 9. 异常处理

### 9.1 Peer 局部且可恢复

连接/握手超时、EOF、Choke、Block 停滞等只退役当前 Socket，记录失败并补租其他 Peer。Bitfield 明确不包含目标 Piece 时只释放租约，不计为网络故障。

### 9.2 协议与完整性错误

未请求、重复、错索引或畸形 Block 立即关闭 Socket并处罚 Peer。Piece SHA-1 失败时不写 Checkpoint；由于一个 Piece 可能由多个 Peer贡献，所有贡献者暂时冷却且相关 Socket 关闭，但不做永久封禁。

### 9.3 Socket 污染

存在未完成帧、未确认请求或 End-game CANCEL 后潜在迟到数据时，Socket 必须退役。不得为了提高复用率绕过 `shouldRetire` 或部分帧检查。

### 9.4 任务级故障

用户暂停/取消、Revision 变化、实例 Fence 失效、网络总预算到期、磁盘/数据库错误或内部状态不变量破坏时：

1. 停止发放新 Peer 租约；
2. 关闭本任务全部活跃/连接中的 Socket；
3. 取消 Actor 和刷新 Future；
4. 丢弃未发布 staged group；
5. 保留此前已发布的 verified Checkpoint；
6. 把错误交给现有 Worker 状态机处理。

### 9.5 无可用 Peer

刷新后仍无可用 Peer时，Actor 进行有界等待，不允许毫秒级空转。连续无 verified 进展达到既有阈值后，Worker 进入 `PARTIAL`，任务安全回到 `PAUSED`。

## 10. 指标与前端显示

在现有 Worker Metrics 中增加：

- `targetPeerActors`：监督器当前目标 Actor 数。
- `activePeerActors`：当前持有 Peer 租约的 Actor 数。
- `actorPeerReassignments`：Actor 因会话结束而换租 Peer 的累计次数。
- `retiredPeerSockets`：本作业主动退役的 Socket 累计数。

日志按原因区分 connect/EOF/choke/stall/protocol/contamination/hash，但不记录 token、Cookie、完整 Magnet 或用户秘密。前端在现有任务指标行展示目标/活跃 Actor、换租和退役数，不新增页面或路由。

现有 opened/reused/cross-round/idle Socket、Block 队列占用、P50/P95 RTT、请求/接收计数继续保留。

## 11. Feature Flag 与回退

- 实现和自动化验证期间保持 `TORRENT_NATIVE_MULTIPLEXED_PEER_MAILBOX_ENABLED=false`。
- 只有全部自动化门槛和公网有界门槛通过后，才在未提交的 `backend/.env` 中设置为 `true` 并重启服务。
- 当前正在运行的任务不热切换网络引擎；等待其自然暂停/结束后再重启。
- 如果公网验证出现完整性、线程泄漏或吞吐回退，立即关闭该 Flag 并重启，继续使用旧 Peer-centric 路径；Checkpoint 格式不变，无需数据迁移。

## 12. 自动化测试设计

所有生产行为严格按 TDD 实施，先运行并确认测试因缺少目标行为而失败，再写最小实现使其通过。

### 12.1 Actor 补位

新增本地 TCP 集成测试：前 16 个 Peer在握手或首个请求时失败，后续健康 Peer可提供全部 Block。期望同一轮完成 Piece，`actorPeerReassignments>0`，且不是依赖下一轮恢复。

### 12.2 Peer 互斥租约

并发测试记录每个 endpoint 的同时活跃 Actor 数，任何时刻不得超过 1；租约释放后允许其他 Actor 重新选择。

### 12.3 动态刷新与淘汰

扩展 `PeerHealthTrackerTest`：已验证 Peer受保护；新发现 Peer替换从未成功且连续失败的 Peer；池未达到 256 时同样执行替换；活跃租约不得被淘汰。

### 12.4 完整性和污染

覆盖错误 Block、Piece SHA-1 失败、End-game 迟到帧、部分帧和未确认请求。期望 verified bytes 不增加，污染 Socket 不复用，健康 Socket仍可跨轮复用。

### 12.5 取消与 Fence

在 Actor 持有活跃 Socket 和未发布 staged group 时触发取消/Fence 失效。期望所有 Socket、Actor、刷新 Future 在有界时间内结束，未发布数据不进入 Checkpoint，已发布数据保持可恢复。

### 12.6 32 Actor 资源边界

使用确定性的本地 Peer测试 32 Actor、32 Piece、16–64 Block 管线。验证最大 Actor、Socket、Mailbox Block、结果队列和线程数量均不超过设计上限，结束后没有线程或 Socket 泄漏。

### 12.7 既有回归

以下 ISSUE-002 专项连续执行 10 次全部通过：

- `PeerBlockMailboxTest`
- `PeerMultiplexedRoundIntegrationTest`
- `PeerMultiplexedOverdueTransferIntegrationTest`
- `PeerMultiplexedCrossRoundRetirementTest`

随后执行后端全量测试、干净编译、前端构建和 `git diff --check`。

## 13. 公网性能验收

- 使用新的隔离任务和目录，不修改当前正在运行的用户任务。
- 开启优化路径前，在相邻时间窗口记录旧 Peer-centric 路径基线；优化路径至少连续运行 300 秒。
- 当已验证可传输 Peer不少于 16 时，`activePeerActors` 必须达到至少 16，并产生 Actor 换租、有效 Block、Socket 复用和跨轮复用证据。
- 优化路径中位 verified 吞吐必须达到旧路径至少 2 倍，且不低于 `0.5 MiB/s`。任一条件未达到，都不得宣称提速完成。
- 公网 Swarm 变化过大、无法形成相邻可比样本时，只报告观测结果；合成本地性能测试不能冒充公网吞吐通过。

## 14. 最终文件验收

公网有界性能通过后，继续复用同一优化任务 Checkpoint 至 100%：

- `verifiedBytes = 2918598656`
- 自动调用 `finalizeVerifiedFiles()`
- 最终文件大小 `2918598656` bytes
- SHA-256 为 `dec49008a71f6098d0bcfc822021f4d042d5f2db279e4d75bdd981304f1ca5d9`
- 最终无残留 `.clipfetch.part`

只有上述证据齐全，才能同时宣称吞吐优化和完整文件验收完成。

## 15. 预期修改范围

生产代码预计只触及：

- `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerMultiplexedRound.java`
- `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerHealthTracker.java`
- `backend/src/main/java/cc/ivera/magnet/engine/piece/PeerRoundConnectionPool.java`
- `backend/src/main/java/cc/ivera/magnet/engine/piece/PieceDownloadCoordinator.java`
- `backend/src/main/java/cc/ivera/magnet/engine/piece/NativeWorkerJobManager.java`
- `frontend/src/components/NativeTorrentManager.vue`

测试优先修改既有 Mailbox、健康度和 Worker 测试；只有当职责无法在现有测试类中清晰表达时，新增一个聚焦 Actor 补位的集成测试类。不得借此重构无关模块。

运行启用仅修改本机未提交的 `backend/.env`；不把实验开关写死在源码或提交秘密配置。
