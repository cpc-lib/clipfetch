# ClipFetch — Native BitTorrent 问题清单（ISSUE.md）

更新日期：2026-10-18
项目路径：`D:\release\clipfetch`
状态：**存在未解决问题；不能宣称当前指定 Magnet 下载通过或 P3.2 全面验收**

## 1. 本轮测试对象与验收边界

```text
magnet:?xt=urn:btih:e1fc140a6391357fa1cf08ddb70274f9c05eb88b&dn=ubuntu-26.04-live-server-amd64.iso&xl=2918598656
```

- InfoHash（v1）：`e1fc140a6391357fa1cf08ddb70274f9c05eb88b`
- 文件名：`ubuntu-26.04-live-server-amd64.iso`；期望大小：**2,918,598,656 bytes**
- **可信元数据对照**：项目已有 `torrent/ubuntu-26.04-live-server-amd64.iso.torrent`，223,040 bytes。直接读取 bencoded `info` 原始字节计算 SHA-1，与 Magnet 完全一致。Piece length **262,144 bytes**，共 **11,134 Pieces**。
- 官方 HTTPS Tracker 来自上述现有 `.torrent` 的 `announce-list`；**用户原 Magnet 并未携带 `tr=`**。因此本测试的 Tracker 成功不等于纯 Magnet DHT/BEP9 获取 metadata 已通过。
- 早期记录仅覆盖短时公网协议诊断和后端测试；最新隔离全量验收已在第 3 节补充。整个过程没有启用生产 Native 功能或覆盖用户任务。

## 2. 2026-10-17 实际执行结果

| 阶段 | 实际结果 | 是否通过 |
|---|---|---|
| Magnet 与现有 torrent InfoHash | SHA-1 完全匹配 | 通过 |
| 文件长度 / Piece 布局 | 2,918,598,656 bytes / 256 KiB / 11,134 Pieces | 通过 |
| `https://torrent.ubuntu.com/announce` | HTTP 200；返回 1 条 Peer | 通过 |
| `https://ipv6.torrent.ubuntu.com/announce` | HTTP 200；返回 1 条 Peer | 通过 |
| Peer 去重后的公网候选 | `185.125.190.59:6920`，共 1 个 | 发现成功，覆盖不足 |
| BEP3 TCP handshake | 返回 InfoHash 匹配 | 通过 |
| BEP10 extension | 此 Peer 的 reserved bits 未声明支持 | 不支持 |
| BEP3 Interested / Unchoke | 收到 Message ID=1，长度 1 | 通过 |
| Piece 0 / 16 KiB REQUEST | 发送后 Peer 抛 EOF（远端关闭） | **失败** |
| 本次真实 Block / Piece SHA-1 | 0 个有效 Block，无法验证任何完整 Piece | **未通过** |
| 后端 Mailbox + TCP 跨轮组合专项 | `mvn -q '-Dtest=PeerBlockMailboxTest,PeerMultiplexedOverdueTransferIntegrationTest,PeerMultiplexedCrossRoundRetirementTest' test`；Exit Code **1** | **失败** |

**本次公网关键证据**：

```text
TORRENT_SHA1 e1fc140a6391357fa1cf08ddb70274f9c05eb88b SIZE 2918598656 PIECES 11134
TRACKER https://torrent.ubuntu.com/announce 200 peers 1
TRACKER https://ipv6.torrent.ubuntu.com/announce 200 peers 1
PEER 185.125.190.59 6920 HASH_OK True EXT False
MESSAGE 1 1
PEER_FAIL 185.125.190.59 6920 EOFError peer closed
```

以上为 **2026-10-17 较早的单 Peer 公网基线**，不代表后续扩大 Swarm 候选后的最新下载状态。该 Peer 曾在 16 个并发 Block REQUEST 或单 Block REQUEST 后 EOF；公网 Peer 行为具有时效性，不能仅凭它归因生产代码错误。最新已验证 Piece 和持久续传结果见下文 ISSUE-001。

## 3. 未关闭问题（按优先级）

### ISSUE-001 — Live Server ISO 隔离公网全量验收（P1，已完成）

**历史故障复现**：按第 2 节向官方 Tracker announce，连接到 `185.125.190.59:6920`，验证 handshake，发送 INTERESTED，等待 UNCHOKE 后请求第 0 Piece 的 16 KiB Block。后续已通过扩大公网候选与健康重试获得真实有效 Piece，不能再把此 Peer 的 EOF 当成整体下载失败。

**预期**：至少一个具备对应 Piece 的 Peer 能够发送完整有效 Block；收到完整 256 KiB Piece 后，使用 `.torrent` 的 SHA-1 piece hash 验证。

**2026-10-17 较早单 Peer 诊断结果**：当时的公网连接在 REQUEST 后 EOF，0 个有效 Block。这个结果仅代表所测 `185.125.190.59:6920` Peer，**不是当前所有公网 Peer 的结论**；完整 ISO 仍未产出。

**最新进展**：通过 Tracker/DHT 扩大 Swarm Peer 发现、Peer 健康重试与持久 Checkpoint，已完成全部 11,134 个 Piece 的 SHA-1 校验与持久化。旧的「完全没有有效 Block」已经得到新证据纠正；公网不同轮次的 Peer 候选集合不同，不能仅凭并发变化推断性能因果。

**阶段验收：真实公网 Piece SHA-1、持久化恢复、全量文件、finalization 与官方 SHA-256 均已通过**。ISSUE-001 的隔离公网全量验收完成；该结论不等同于已登录生产 `NativeWorkerJobManager` 的业务 E2E 验收。

#### 2026-10-17 有界公网全量下载推进（真实数据，不是合成 Peer）

- 使用 `torrent/ubuntu-26.04-live-server-amd64.iso.torrent`（hash `e1fc140a6391357fa1cf08ddb70274f9c05eb88b`），开启 opt-in `NativeFullDownloadVerificationTest`，下载根固定 `D:\data\clipfetch-issue001\ubuntu-server-20261017`，不放在会被 `mvn clean` 删除的 `backend/target` 中。生产 Native Feature Flags、用户任务、数据库均未修改。
- **基线，4 并发、3 分钟预算**：Tracker 1/2 响应，50 候选，19 TCP 可连接，31 超时；第一轮提交 255/256 Piece（66,846,720 Bytes，0.775 MiB/s），第二轮提交 255/256（66,846,720 Bytes，0.806 MiB/s）。最终 `NATIVE_FULL_RESULT status=PARTIAL verifiedBytes=133693440 percent=4.5807 avgMiBps=0.790 rounds=2`；Maven Exit **0**，169.78 秒。
- **重启/Checkpoint 续传，8 并发、2 分钟预算**：启动时 `resumedBytes=133693440`，证明前轮 SHA-1 已验证字节从磁盘恢复。此轮 Tracker 1/2 响应，97 候选，47 TCP 可连接，50 超时；两轮提交 256/256 和 253/256 Piece（本次新增 133,431,296 Bytes）。最终 `NATIVE_FULL_RESULT status=PARTIAL verifiedBytes=267124736 percent=9.1525 gainedBytes=133431296 avgMiBps=1.248 rounds=2`；Maven Exit **0**，113.86 秒。
- **累计：1,864 个完整 256KiB Piece、488,636,416 Bytes（16.7422%）验证并持久化**（从 267,124,736 字节经 363,855,872 字节、390,332,416 字节连续增量续传至 488,636,416 字节，最新一轮 3 分钟预算净增 98,304,000 字节 / 375 Pieces，平均 0.536 MiB/s）。最终仍为部分文件 `ubuntu-26.04-live-server-amd64.iso.clipfetch.part`，重读 Checkpoint 校验一致；未达到全量前绝不标记为完整 ISO。
- **权威官方 SHA-256 校验源**：从 Ubuntu 官方源 `https://releases.ubuntu.com/26.04/SHA256SUMS` 实时抓取对照，确认 `ubuntu-26.04-live-server-amd64.iso` 的官方期望 SHA-256 为 `dec49008a71f6098d0bcfc822021f4d042d5f2db279e4d75bdd981304f1ca5d9`；已将其作为常量 `UBUNTU_EXPECTED_SHA256` 接入 `NativeFullDownloadVerificationTest`，在全量完成 finalization 时执行严格断言。
- 对 `NativeFullDownloadVerificationTest` 增加 `native.full.parallel`（合法范围 1–8）、`parallelWorkers` 日志，以及持久输出路径禁止落在 Maven `target` 树下的显式拒绝逻辑。新离线测试 `rejectsTransientBuildDirectoryAndUnboundedParallelismOffline` Maven Exit **0**；后端全量 `mvn -q test` Exit Code **0**。源代码未硬编码本次绝对输出路径。
- **单 Piece 与 SQLite Worker 复测**：`NativePublicInternetAcceptanceTest` 真实连接公网 Swarm 验证 262,144 字节 SHA-1 Piece 与持久 Checkpoint 重启（Exit 0）；`NativeTaskCreationServiceTest#officialUbuntuTorrentFlowsThroughSqliteAsyncWorkerAndCheckpoint` 提交 4 轮 4 Piece（1,048,576 字节）经 SQLite CAS 验证与对账（Exit 0）。
- **性能结论限制**：公网 Swarm Peer 候选分布随时间变化，8 并发实测吞吐受当时 Peer 响应率影响；后续需同等候选条件的重复观测。
- 此处属于公共 Swarm 的生产 Piece/Checkpoint 组件驱动的 **测试 Harness**，不是已登录用户的正式连续 `NativeWorkerJobManager`，不能拿它替代 ISSUE-002 的公网 Worker 多路复用指标。

#### 2026-10-17 最新全量验收结果

- 继续复用 `D:\data\clipfetch-issue001\ubuntu-server-20261017` 的持久 Checkpoint，未修改生产配置、`.env`、数据库、生产下载目录或已有用户任务。
- `NativeFullDownloadVerificationTest` 最终输出：`status=COMPLETE`、`verifiedBytes=2918598656`、`totalBytes=2918598656`、`percent=100.0000`；全部 11,134 个 Piece 经 SHA-1 校验并通过 Checkpoint 重读。
- 自动执行 `VerifiedPieceCheckpoint.finalizeVerifiedFiles()`，生成最终文件 `D:\data\clipfetch-issue001\ubuntu-server-20261017\ubuntu-26.04-live-server-amd64.iso`，文件大小 `2918598656` bytes，`.clipfetch.part` 不存在。
- Harness 与独立 PowerShell `Get-FileHash -Algorithm SHA256` 均验证官方 SHA-256：`dec49008a71f6098d0bcfc822021f4d042d5f2db279e4d75bdd981304f1ca5d9`；最终 Maven Exit Code **0**，独立哈希命令 Exit Code **0**。
- **当前结论**：ISSUE-001 的隔离公网全量验收已完成。该产物属于隔离测试目录，不代表生产 Native 已启用，也不替代 ISSUE-002 的登录 UI→API→公网 Worker 多路复用验收。



### ISSUE-002 — P3.2 End-game 污染导致跨轮 TCP 复用失败（P1，OPEN，已复现）

**复现**：

```powershell
cd D:\release\clipfetch\backend
mvn -q '-Dtest=PeerBlockMailboxTest,PeerMultiplexedOverdueTransferIntegrationTest,PeerMultiplexedCrossRoundRetirementTest' test
```

**预期**：跨轮测试首轮保留健康 idle Socket 1 个，污染的慢 Peer 退役；下一轮健康连接能够跨轮复用。

**实际**：本轮 Exit 1；`PeerMultiplexedCrossRoundRetirementTest.java:150` 报 `expected: 1 but was: 0`。`TaskConnectionStats[openedSockets=2,reuseHits=0,crossRoundReuseHits=0,idleSockets=0]`，两个 Peer 各 accept 一次。上一轮带 JUL FINE 的现场观察：双方 `uncertainOrOutstanding=true`，但 `outstandingBlocks=0`、`partialFrame=false`、`remainingBlocks=0`、`completed=24`。

**现有代码约束**：`PeerBlockMailbox.deliver` 在 End-game primary/backup 同时存在时会把竞速双方标记为 `uncertainSockets`，以隔离 CANCEL 后潜在迟到 PIECE。不能单纯取消标记、降低 SHA-1 校验或削弱迟到响应断言。

**最新复测（2026-10-17）**：已新增 `PeerBlockMailbox` 的 FINE 级 `native_endgame_backup`、`native_endgame_contamination` Block 事件日志，与 `native_socket_retire` 联合使用；日志开启的跨轮独立专项连续 5/5 通过，但默认日志下的组合专项连续 3 轮中第 3 轮又复现 `idleSockets=0`，退出码 1。**本问题仍 OPEN，未修复**，不能把只增加诊断当成调度修复。

**修复试验与回退（2026-10-17）**：曾试验禁止为 already-uncertain donor 创建新 End-game backup，但在 `PeerMultiplexedOverdueTransferIntegrationTest` 中造成 `EOFException: Missing reassigned Blocks`，破坏永久慢 Peer 场景的剩余 Block 活性；已**撤回**。撤回后 Mailbox/超时接管/End-game 专项 Exit 0，9.21 秒，不能因此宣称跨轮问题解决。

**最新针对性修复（2026-10-17）**：End-game 对已标记退役的 donor，不再构造双租约 backup，改由 `reassignOverdue` 执行具有 CANCEL 和旧租约撤销的独占尾部转移；未标记 donor 仍使用既有 End-game backup。新增 Mailbox 验证；跨轮 TCP、End-game、超时接管四项组合初次通过，后续连续 **5/5 次 Exit 0**。这是一项真实生产调度逻辑改动，尚未通过公网连续 Worker 下载验收，不能视为所有网络问题解决。

**本轮最终回归**：后端全量 `mvn -q test` Exit Code **0**（71.07 秒）。**后续**：使用独立任务和合法 Peer 开展持续 verified Bytes/s、真实跨轮复用、EOF 退役与迟到帧安全验证；在公网/生产 Worker 证据齐全前保持部分验收状态。

### ISSUE-002 公网真实下载补测（2026-10-17，PARTIAL）

- 对用户指定的 `e1fc140a6391357fa1cf08ddb70274f9c05eb88b` 使用现有匹配的官方 `.torrent`、新隔离根 `backend/target/issue002-public-20261017/`，执行有界 opt-in `NativeFullDownloadVerificationTest`（3 分钟、`native.full.reuse=true`）。Tracker 1/2 响应，Peer 候选 50、TCP 可连接 18、超时 32。
- **真正公网 Peer 数据已成功**：首轮 `verifiedBytes=43515904`、166 个 256KiB Piece、1.4910%，`gainedBytes=43515904`、`avgMiBps=0.237`、`status=PARTIAL`、Maven Exit 0，183.54 秒；`VerifiedPieceCheckpoint` 通过重新打开后内容 SHA-1 复核。预算到期 `Native task network deadline exceeded` 是受控 PARTIAL，不是整份下载完成。
- 再次打开同目录测试输出 `resumedBytes=43515904`，证明经过进程退出仍能恢复已验证进度；第二轮发现 50 个候选中 21 个 TCP 可达、29 个超时。第二次 `NATIVE_FULL_RESULT status=PARTIAL verifiedBytes=69206016 totalBytes=2918598656 percent=2.3712 gainedBytes=25690112 avgMiBps=0.213`，Maven Exit 0（122.96 秒）。**新增 98 个有效 Piece，累计 264 个、69,206,016 已验证字节（2.3712%）**。
- **验收边界必须清晰**：此为生产 Java Piece/Checkpoint 组件驱动的公网 **测试 Harness**，并非真实 JWT/用户隔离的 `NativeWorkerJobManager` 连续 Worker，且未实际调用 ISSUE-002 修复的 `PeerMultiplexedRound` 多路 TCP Actor；`peerConnectionReuse=true` 只是配置开关，**没有有效的 Socket opened/reuse/retired 计数证据**。因此公网有 Verified Piece 的问题得到改善，但 **ISSUE-002 公网 Worker 验收未完成，不应 CLOSE**。
- 下载产物当前仅部分 `.clipfetch.part` + checkpoint；未完成 2,918,598,656 字节也未获得最终 SHA-256 证据，不能称为成品 ISO。

**2026-10-17 18:29 实际验收补记：**经代码检查，生产 `NativeTaskWorkerRoundService` 需 `ongoingSession` 存在、`parallel>1`、同时启用 `peerConnectionReuse` 与 `multiplexedPeerMailbox`；`PieceDownloadCoordinator` 还需有效 `actorScope` 和至少两个 Peer 才会进入真正多路 Actor。针对 `PeerMultiplexedCrossRoundRetirementTest,PeerMultiplexedOverdueTransferIntegrationTest,PeerMultiplexedRoundIntegrationTest,PeerBlockMailboxTest` 的真实本地 TCP 专项 **Exit 0（9.77 秒）**，后端全量 `mvn -q test` **Exit 0（68.06 秒）**。但先前测试下载目录 `backend/target/issue002-public-20261017` 当前不存在，本轮未沿用之前的 Checkpoint，也未新启隔离认证 Worker 公网任务或采集公网 `crossRoundReuseHits`。因此 **PARTIAL，不可 CLOSE**。

### ISSUE-003 — Magnet 无 Tracker 时的公网发现、BEP9 Metadata 闭环（P1，PARTIAL）

- **无 `tr=` 纯磁力闭环验证**：已在 `NativeMagnetIntegrationTest.pureMagnetWithoutTrackerResolvesViaDhtAndBep9ToFileList` 中完成端到端全链路集成验证：
  - 输入：纯 Magnet 链接 `magnet:?xt=urn:btih:<hash>`，**未携带任何 `&tr=` 或 Tracker 参数**。
  - 发现：通过 BEP5 UDP DHT `get_peers` RPC 查询，从种子节点成功获取 Peer 地址（`127.0.0.1:peerPort`），无需任何 HTTP/UDP Tracker。
  - 握手：对端发起 BEP3 TCP 握手并声明支持 BEP10 扩展（`flags[5] = 0x10`）。
  - 协商：发送 BEP10 Extension Handshake 成功协商 `ut_metadata` 扩展 ID 及元数据尺寸。
  - 传输：通过 BEP9 `ut_metadata` 发送请求（`msg_type=0`）并接收分片数据（`msg_type=1`），获取完整 raw bencoded `info` 字典。
  - 校验：对原始 `info` 字节计算 SHA-1，严格比对 InfoHash 完全一致后解析为 `TorrentInfo`（文件名、文件大小、Piece 哈希列表），并生成 `verifiedTorrentBytes()`。
  - 执行结果：`mvn -q "-Dtest=NativeMagnetIntegrationTest" test` **Exit Code 0**（2.1 秒）。
- **公网 Swarm 边界限制**：针对指定 Ubuntu 26.04 Live Server Swarm，公网现存官方 Peer（如 `185.125.190.59`）未声明支持 BEP10 扩展（`reserved[5] & 0x10 == 0`）。虽然代码已实现用户显式同意的受控公共 Tracker 目录回退（`DiscoveryService.discoverWithPublicDiscoveryConsent`），但在公网 Peer 不支持 BEP10/BEP9 的情况下，无法从该 Peer 获取元数据。纯 DHT + BEP9 协议链路在合成受控网络已 100% 验证通过。

### ISSUE-004 — 用户登录 UI→API→真实公网 Worker E2E（P1，PARTIAL）

- **认证边界与多用户隔离**：`NativeJwtTaskFlowTest` 覆盖 Bearer JWT 校验、未鉴权拒绝（HTTP 401）、跨用户访问拒绝（HTTP 404）、任务创建、文件索引选择、`prepare` 状态切换（READY）、工作作业提交（QUEUED/RUNNING）、状态轮询、取消/暂停、版本冲突防护（HTTP 409）及独立 Feature Flag 控制的孤儿任务恢复（Exit Code 0）。
- **控制器与业务链路**：`NativeTaskCreationControllerTest`、`NativeTaskControlControllerTest`、`NativeWorkerJobControllerTest`、`NativeTaskCreationServiceTest` 完整验证 REST 接口行为及 SQLite 事务。
- **前端浏览器验收**：`frontend/scripts/native_torrent_browser_acceptance.py` 基于 Playwright 验证全部 8 个场景（访客拒绝、文件上传解析、文件复选创建、有界 Worker 提交、刷新重连作业、暂停取消、孤儿任务恢复弹窗确认、Feature Flag UI 隐藏）。
- **生产安全边界**：生产 Native BitTorrent 功能默认关闭（`productionReady=false`），未擅自操作已有用户的生产任务。

### ISSUE-005 — 崩溃后跨数据库恢复与用户保存目录验证（P1，PARTIAL）

- **存储目录白名单与路径穿越防御**：`NativeDownloadRootServiceTest` 验证仅允许在服务器白名单基准路径下浏览和选择保存目录；严禁浏览其他用户私有目录；符号链接、非目录、不存在路径及路径穿越尝试均抛出安全异常；失效配置自动安全回退至默认存储根（Exit Code 0）。
- **异常崩溃与断点续传恢复**：`NativeCheckpointCrashProcessTest` 通过独立子 JVM 执行 `Runtime.getRuntime().halt(37)` 模拟进程瞬间崩溃；新 Checkpoint 实例从磁盘重读并逐 Piece 进行 SHA-1 重核；磁盘损坏 Piece 被剔除并重新请求，已验证 Piece 不重复下载（Exit Code 0）。
- **孤儿任务对账与恢复**：`NativeOwnedOrphanRecoveryServiceTest` 与 `NativeOrphanReconciliationServiceTest` 验证单实例模式下基于租约超时与执行栅栏的孤儿任务恢复，防止旧实例持有脏任务。
- **MySQL 集成测试边界**：`NativeMysqlTwoJvmCommitIT`、`NativeMysqlDisconnectIT`、`NativeMysqlAtomicLeaseIT` 在未配置 `clipfetch.mysql.*` 或 `CLIPFETCH_MYSQL_IT_PASSWORD` 环境变量时显式跳过（Skipped 3，Exit Code 0），不伪造通过结果。

### ISSUE-006 — 公网 Peer 健康度与持续吞吐证据（P2，PARTIAL）

- **健康度与调度算法**：`PeerHealthTrackerTest` 验证 Peer 指数级惩罚冷却、有效吞吐加权、RTT P95 排序及跨轮继承；慢 Peer 不被硬性封禁，稀有 Piece 持有者保持调度资格（Exit Code 0）。
- **自适应并发窗口**：`AdaptivePeerConcurrencyTest` 验证 AIMD 拥塞控制窗口缩放、连续失败时的保底探测槽位（floor=8）、成功恢复时的窗口扩展（Exit Code 0）。
- **队列与延迟遥测**：`NativePeerRequestTelemetryTest` 验证 Actor 请求队列占用率（occupancy percent）、P50/P95 RTT 统计与多线程 Actor 隔离（Exit Code 0）。
- **多 Peer Piece 协同调度**：`PeerCentricCoordinatorIntegrationTest` 修复了本地套件负载下的等待预算后，双 Peer 动态分配、广告位过滤与提交聚合稳定通过（Exit Code 0）。

## 4. 以前的成果（与本次测试严格隔离）

- 旧 `ISSUE.md` 记录 P4.70/P4.71 使用独立 Native harness 完整验证 **Desktop** ISO（不同 InfoHash `dafc8c…`，6,518,974,464 bytes），不是本次 Live Server 结果。
- 历史 P4.71 记录也称曾完成 **Live Server** ISO 全量哈希和改名；但本轮检查其记载路径 `D:\release\clipfetch\backend\target\native-full-download-server\ubuntu-26.04-live-server-amd64.iso`，**该路径当前 NOT_FOUND**。这不证明历史记录造假，也不能当作本轮现存文件证据；需重新定位文件、核对体积及 SHA-256。
- 历史 P4.75 记录独立 SQLite 运行实例发生崩溃后续传；本轮未对该任务再次读状态，故保留为历史验证而非当前结论。

## 5. 本轮代码 / 文件变更与复测要求

- 本轮新增 `NativeMagnetIntegrationTest.pureMagnetWithoutTrackerResolvesViaDhtAndBep9ToFileList`，完成无 `tr=` 纯磁力经 BEP5 DHT 发现、BEP10 扩展握手、BEP9 元数据分片传输、SHA-1 InfoHash 校验和文件列表提取的端到端集成测试。
- 本轮验证了 ISSUE-003 至 ISSUE-006 的全部单元与集成测试集合，执行 `mvn -q test`（Exit Code 0，400+ 测试全部通过）与 `npm run build`（Exit Code 0）。
- 检查 `git diff --check`（Exit Code 0），所有修改与新增代码均为无 BOM UTF-8。生产 Native 功能与用户数据未受破坏。
- 遵循 `AGENTS.md` 规范，不主动创建 commit，更新 `ISSUE.md` 与 `HANDOFF.md`。

### ISSUE-007 — Fenced Worker 收尾遭线程中断导致 Ownership lock interrupted（P1，代码修复已验证、运行环境待验收）

**故障现场（2026-10-18）**：`task=bca6313e-1889-471e-8505-425220f82585 owner=1` 的 Native Piece 作业打印 `IOException: Ownership lock interrupted`，栈依次为 `NativeCheckpointOwnershipLock.execute` → `NativeTaskLifecycleService.pauseFencedWithDiskLock` → `NativeTaskRoundRunner.runFenced` 的 `finally` → `NativeWorkerJobManager.runContinuous`。

**已确认根因**：真实 Worker 退出网络下载前可能已经收到取消/线程中断；`finally` 持久化 `RUNNING→PAUSED` 依然需要在 `piece-commit.lock` 下完成，旧版一般锁获取在看到中断标记后立即抛 IOException，阻断安全收尾并覆盖原始取消异常。

**实际修复**：
- `NativeCheckpointOwnershipLock.executeForCleanup`：仅 fenced 收尾作用域容忍并暂存中断；与普通 `execute` 共用本地文件系统安全检查、独占 FileLock、5 秒获取时限，结束前恢复中断位。
- `NativeTaskLifecycleService.pauseFencedWithDiskLock` 改用这条专用收尾路径，原有 owner/task/revision/epoch/instance 严格 fencing 检查不变。
- 三个回归：真实 SQLite 的 Worker 中断→PAUSED 与原始异常不被覆盖；持锁 Piece 写入期间第二次中断的并发收尾；普通锁获取仍可中断。

**验证**：新增故障用例修改前 Exit 1；修复后针对性回归连续 3/3 Exit 0；`NativeCheckpointCrossJvmLockTest,NativeTaskLifecycleServiceTest` 专项 Exit 0；后端全量 `mvn -q test` Exit 0（70.75 秒），`git diff --check` Exit 0。

**待验收事项**：当前运行中的 8082 后端尚未有序重启加载新代码；`bca6313e…` 任务的真实数据库状态、Checkpoint 与租约未做只读核对。先确保没有进行中的 Piece 写入，再有序重启并使用已有任务 API 完成取消/暂停/继续 E2E；若任务仍 RUNNING 且旧 owner 租约未过期，必须通过既有 fence/orphan-recovery 流程处理，禁止直接改任务状态。**当前状态：PATCHED / TESTED，待部署与真实 Worker 验收**。

### ISSUE-008 — Multi-Piece Actor 轮次超时硬杀与连续作业吞吐受限修复（P1，代码修复已验证、运行环境待验收）

**故障现场（2026-10-18）**：
- 用户实测任务 `bca6313e-1889-471e-8505-425220f82585` 在连续作业中持续失败（前端报错显示："作业: 失败 第 0 轮 动态 Peer 池 78 · 已证实可传输 20 · 故障 65 · 刷新 0 次"）。
- 后端日志 `D:\release\clipfetch\logs\clipfetch.log` 打印：
  `java.io.IOException: Multi-Piece Peer actors did not drain after task deadline`
  `at cc.ivera.magnet.engine.piece.PeerMultiplexedRound.fetch(PeerMultiplexedRound.java:215)`
- 下载速度极低，仅 162 KiB/s - 410 KiB/s，无法达到多 Peer 聚合预期。

**已确认根因**：
1. **Actor 循环未感知关闭**：`PeerMultiplexedRound.fetch` 中的 Actor 工作线程循环仅检查 `mailbox.remaining() > 0`，未检查 `!mailbox.closedForClaiming()`。在主线程触发超时并关闭申请后，Actor 仍不断向空闲 Peer 重新租约并启动 `runMailbox`。
2. **致命的 owner 线程中断与连接池清空**：
   - 当 15 秒排水超时到期时，旧代码直接调用 `session.abortForThread(owner)` 并抛出 `IOException("Multi-Piece Peer actors did not drain after task deadline")`。
   - `abortForThread(owner)` 会向 `owner`（作业管理器工作线程）打上线程中断标记，并调用 `task.idle.clear()` 清空跨轮空闲连接池。
   - 在 `PeerMultiplexedRound.fetch` 的 `finally` 块中，`if (pending || owner.isInterrupted()) session.abortForThread(owner)` 在有任意子 actor 未结束时也会无条件打断 `owner`。
   - 上游 `NativeWorkerJobManager` 检测到线程被中断或捕获 `IOException`，判定为不可恢复错误，直接将作业置为 `State.FAILED`，导致第 0 轮直接夭折，后续轮次完全无法执行。
3. **吞吐人为压制**：
   - `PieceDownloadCoordinator.parallelMultiplexed` 将每轮调度 Piece 数量硬编码限制为 `Math.min(maxPieces, 32)`（仅 8MB），且将超时预算计算写死为 `perPeerBudget * 3 = 24秒`（上限 40秒），完全未利用 `.env` 中配置的 `TORRENT_NATIVE_NETWORK_ROUND_BUDGET_SECONDS=120`。
   - 并发 Actor 上限定为 64，跨轮连接缓存 `TASK_IDLE_CAP` 设为 64，限制了高并发场景。
   - `PeerRoundConnectionPool` 在读取增量数据片段（`incremental.hasPartialFrame()`）时每轮询无条件 `Thread.sleep(2)`（Windows 系统时钟常达 15.6ms），导致分片 TCP 块流速受阻。

**实际修复**：
1. **连接池与会话分级终止**：
   - `PeerRoundConnectionPool`：新增 `abortRoundChildren(Thread owner)`，仅清理当轮运行中的子线程及其活跃 Socket，完好保留 `task.idle` 跨轮连接；`TASK_IDLE_CAP` 扩容至 128；在 `hasPartialFrame()` 阶段使用 `Thread.onSpinWait()` 零延迟处理分片 TCP 包重组。
   - `PeerPieceDownloadSession`：新增 `abortChildren(Thread owner)`，专用于轮次结束时平滑清理挂起的子 Actor，严禁中断 `owner`。
2. **优雅轮次排水与异常消除**：
   - `PeerMultiplexedRound`：Actor 循环追加 `!mailbox.closedForClaiming()`；超时排水到期时改调 `session.abortChildren(owner)`，取消挂起 Future 并安全捞取已验证 Piece，优雅退出并提交成果而非抛出致命异常；`finally` 块仅在 `owner.isInterrupted()` 时调用 `abortForThread(owner)`，否则仅调用 `abortChildren(owner)`；探索槽位增至 `Math.min(32, maxActors)`。
3. **解除批次与预算瓶颈**：
   - `PieceDownloadCoordinator`：移除 `32` 片硬编码限制，按 `MAX_IN_FLIGHT_PIECE_BYTES`（128MB）限制 Piece 批次（256KB 片长支持至 128 片）；轮次预算与 Piece 数量及 `NativeNetworkBudget`（120秒）自适应联动；Actor 上限放宽至 128。
4. **单测与回归**：
   - 更新 `PeerMultiplexedRoundIntegrationTest` 增加 owner 中断断言（验证排水后 owner 未被中断）。
   - 全量回归 `mvn -q test` 412 个测试全数通过（Exit Code 0），`git diff --check` Exit Code 0。

**待验收事项**：
- 后端服务需重启加载新字节码。
- 用户可在前端重新点击启动 `bca6313e…` 任务，观察连续作业是否跨越第 0 轮平滑进入后续轮次，且下载速度随连接复用提升。**当前状态：PATCHED / TESTED，待部署与用户实测**。
