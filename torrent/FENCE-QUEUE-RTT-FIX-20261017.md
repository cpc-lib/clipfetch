# ClipFetch Native — Fence 失效与 Peer Block 遥测优化交接（2026-10-17）

## 故障与证据

错误：`java.lang.IllegalStateException: Native fenced round not admissible`，发生在 `NativeTaskRoundRunner.runFenced:32`，它在 CLAIM 前同时验证入参、Checkpoint、一致的 Coordinator、Heartbeat。最可能的触发项是实例心跳：

- `D:\data\clipfetch\sqlite\fvd.db` 原 Worker Instance 仍显示 `ACTIVE`，但最后一次心跳约为本地 **2026-10-17 08:57:10**，租约截止约 **08:57:55**。
- 相关异常约 **08:57:47**。无法从旧日志确认心跳为何停止：可能为 `renewAtomic` 拒绝或数据库查询异常。数据库记录的后续过期进一步证明实例并未持续续约。
- 旧代码每个网络 Block guard 都调用 `heartbeat::isHealthy`，触发数据库心跳健康查询，同时每次 Guard 还调用 `lifecycle.requireFence`；高并发下载可能造成不必要的数据库压力。这是推断出的高风险热点，不代表已经证明为本次租约丢失的唯一原因。

## 修复（保证 fail closed）

1. `NativeInstanceLeaseUnavailableException`：单独标识心跳/租约不可用，和非法参数分开；不再使用一条缺乏上下文的错误描述。
2. `NativeTaskRoundRunner.runFenced`：入场时仍必须进行数据库最新的实例健康检查、唯一 Owner/Epoch 和 Checkpoint 认证。每个网络 Guard 继续执行完整任务 Fence 检查。仅对重复的 Heartbeat DB 健康查询使用任务局部的约 250ms `AtomicLong` CAS 减压；数据库进度同步仍必须做 Owner/Epoch/Revision CAS。Heartbeat 续约器独立 15 秒周期，不被缓存替换。
3. `NativeWorkerJobManager`：捕获专属租约不可用异常并尝试把持久任务安全转为 PAUSED；终止内存作业为 CANCELLED，不误报普通 Peer 下载失败，不对未知/已过期租约强行重领。数据库 Pause 失败时记录错误，必须人工核实所有权。
4. `NativeWorkerInstanceHeartbeat`：新增实例租约拒绝/心跳续期失败/DB 健康查询失败的结构化日志（仅错误类型，不包含任何凭据）；租约一旦丢失，仍不可在同实例内自动恢复。
5. **不要**直接修改实例租约行或清理旧 Checkpoint。租约丢失后仍需正常重启注册新 Worker Instance，并按控制台恢复原任务。

## Peer 请求队列 / RTT API + UI（真实链路）

- `NativePeerRequestTelemetry`：任务独立并发统计。每 Actor 在途请求和窗口容量；已发送、已回应 Block；按 2 的幂分桶（毫秒）的 RTT P50/P95 **近似值**，防止为保存每次请求分配无限内存。
- `PeerRoundConnectionPool` + `PeerPieceDownloadSession`：在真正的标准 TCP Piece 16KiB Request/ACK/Refill 事件中更新队列与 RTT；下载结束/断线清理 actor 指标。实验多 Piece TCP Mailbox 也跟踪 ACK、窗口和 RTT。
- `NativeWorkerJobManager.Metrics.peerRequestQueue`：新增 `sentBlocks`、`acknowledgedBlocks`、`outstandingBlocks`、`capacityBlocks`、`occupancyPercent`、`rttP50Millis`、`rttP95Millis`；保留旧 API 的便捷构造方法。
- `NativeTorrentManager.vue`：真实作业 RUNNING 时可查看队列占用/Block RTT P50/P95/累计请求与回应。此数据与「实时网络接收」「已验证均速」「待提交 Piece」保持不同口径。
- `PeerBlockMailbox.deliver`：新请求完成时，用真实租约开始时间更新 Peer RTT 估算，后续 End-game 备用请求阈值可参考观测 RTT。

### 局限

- RTT 来自协议 REQUEST 至 Block 应答时间（应用层往返时间，受磁盘和 Peer 调度影响），非 ICMP RTT。
- P50/P95 为对数桶上界，样本较少时并非精确分位值。可能出现瞬时 0 outstanding，表示抽样时窗口正好为空/作业不在传输，不代表一直没有下载。
- 目前未根据占用率自动调整全局 Actor 上限，避免在缺少公网实测时错误过度扩容。

## 5 分钟吞吐基准

脚本：`torrent/benchmarks/measure-5min.ps1`。独立调用 ClipFetch 和 qBittorrent 各一次，每次 300 秒，5 秒轮询，导出 CSV 和该时间窗实际 payload / durable KiB/s。

对比的前提：
- 同一合法公开 Ubuntu 官方 ISO，匹配 InfoHash、文件选择、保存介质，独立的**干净下载目录**，每轮足够可下载字节。
- 同一设备/网络相近时段，轮流测试以避免相互抢 Peer 和带宽；不在不同进度/不同 Peer 状态下把两个瞬时速度数字直接相比。
- 截止本次检查，Windows 目标机没有正在运行的 qBittorrent，也没有找到常规安装目录可执行文件；原 ClipFetch 任务 `PAUSED`、绝大部分数据已完成。因此**没有执行出可比的 2 x 300 秒实际公网样本，不得填写伪造的增益比**。
- benchmark 脚本仅读取已有的受权指标接口，不会自动启动/暂停/删除任何任务。使用者提供合法的 ClipFetch bearer token 或 qBittorrent WebUI session 给本地进程。密钥不能写在脚本/日志中。

## 验证

```powershell
cd D:\release\clipfetch\backend
mvn -q clean test
cd ..\frontend
npm run build
```

本轮实际验证：`mvn -q clean test` **370 项测试 / 96 套件，0 failures、0 errors、5 skipped，退出码 0**；`npm run build` 通过（90 modules）；`git diff --check` 通过；PowerShell benchmark 脚本 ParseErrors=0。未执行缺少 qBittorrent 基准和有效下载任务的 2x5 分钟公网 A/B，因此没有可靠增益数值。

维护期正常重启新代码后观察 5 分钟：心跳 renewal 日志、队列占用均值、请求/ACK 差值、RTT P95、有效载荷 5min 平均、已验证 5min 平均、Peer 连接新建/复用。若心跳失效仍立刻 fail-closed，并保留数据，禁止自动重领旧 Epoch。

## 用户数据保护

未自动重启或停止在线服务，未删除任务/Checkpoint，未更改 .env 凭据、数据库 Schema、防火墙和 NAT，未执行 Git commit/push。修改在当前工作目录等待下次正常维护重启后生效。
