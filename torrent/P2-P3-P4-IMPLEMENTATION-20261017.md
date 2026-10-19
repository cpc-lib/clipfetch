# ClipFetch Native Torrent — P2/P3/P4 技术实施记录

更新时间：2026-10-17。修改位置：D:\release\clipfetch。
本轮只针对合法公开测试 Torrent（Ubuntu ISO）作验证；不得将静态单元测试视为真实公网测速。

## 实施范围和完成情况

| 阶段 | 已实现 | 尚未完成 |
|---|---|---|
| P2 持续任务级 Peer Actor | TaskPeerActorPool 在认证过的 owner/task/hash 范围内复用线程池；跨 Piece 轮次保留 actor 线程；PeerHealthTracker 继承已验证 Peer/线程亲和；取消关闭 actor 与连接。 | **尚未实现完整的 per-peer mailbox 和真正跨 Piece 的 Peer-centric 全局 Block Scheduler**。目前任务级复用底层已有 Piece 分派器，而不是模仿 libtorrent 独立 Peer Actor 驱动全局 Piece Picker。 |
| P3 Block 超时与调度 | PeerBlockRequestWindow 按 RTT EWMA、最近吞吐调整 outstanding 16KiB Block 窗口；独立的未回应 Block 截止时间；连接太慢即退出该 Peer 并通过现有 Partial Piece salvage 向下个 Peer 请求缺失 Block；上限 64 Block，逐 Piece SHA1 后提交。 | **尚未实现多个 Peer 同时竞争同一慢 Block 的 end-game 抢占、BEP3 Cancel 消息去重和全局 request queue**；未完成公网 p95 延迟测试。 |
| P4 入站 TCP | NativeInboundPeerServer：默认关闭；必须显式启用 Native、Worker、复用与入站开关；公网监听需额外 public exposure 开关；限流、线程和等待有界；只对唯一匹配的已验证公开活动任务接纳 inbound Peer；验证 BEP3 握手、Bitfield、Unchoke 后将 Socket 纳入任务私有 Peer 池；实际可通过入站 Socket 请求并验证 Piece。 | 尚未在公网验证 NAT/防火墙可达性。没有自动 UPnP、防火墙或路由器设置。 |
| P4 uTP | UtpPacketCodec 支持 BEP29 v1 ST_SYN/STATE/DATA/FIN/RESET 报文格式、边界检查、Selective ACK 位图；UtpCongestionWindow 提供延迟反馈的发送窗口与丢包降窗基础策略。 | **未实现完整 uTP 可靠字节流**（重传计时器、乱序队列、packet ACK/SACK 状态机、拥塞回退、UDP multiplex、Socket 适配、与 Piece Scheduler 对接）；**目前不能通过 uTP 下载**。 |

## 核心修改

- `backend/src/main/java/cc/ivera/magnet/engine/piece/TaskPeerActorPool.java`：按任务作用域保留线程池、限制并发≤128、取消销毁与隔离。
- `PeerPieceDownloadSession.java`、`PieceDownloadCoordinator.java`、`PeerHealthTracker.java`：连续作业借用 actor pool，避免轮次间重建 ThreadPoolExecutor；跨轮传承健康 Peer 亲和，不改变 owner 线程的最终 SHA-1/Checkpoint/DB CAS。
- `PeerBlockRequestWindow.java`、`PeerRoundConnectionPool.java`：Block RTT EWMA/到期检查，吞吐自适应 outstanding window。网络断线后保留合法已收到 Block，迁移到其他 Peer 仍需通过完整 Piece SHA1。
- `NativeInboundPeerServer.java`、`NativeTaskWorkerRoundService.java`、`NativeTorrentConfiguration.java`、`NativeTorrentProperties.java`：入站公开 Torrent 能力和任务隔离。
- `backend/src/main/java/cc/ivera/magnet/engine/utp/`：BEP29 codec 和 LEDBAT 风格拥塞控制数据模型。
- 对应测试：`TaskPeerActorPoolTest`、`PeerBlockRequestWindowTest`、`NativeInboundPeerServerTest`、`UtpPacketCodecTest`，以及已有的跨轮、Partial Piece 续传、任务分离测试。

## P4 开关（默认全部关闭）

```dotenv
TORRENT_NATIVE_INBOUND_TCP_ENABLED=false
TORRENT_NATIVE_INBOUND_PUBLIC_EXPOSURE_ENABLED=false
TORRENT_NATIVE_INBOUND_BIND_HOST=127.0.0.1
TORRENT_NATIVE_INBOUND_LISTEN_PORT=6889
```

- 默认不会监听 TCP 6889，也不会自动改 Windows 防火墙或路由器映射。
- 要监听公网，不仅需要启用入站 TCP，还须显式启用 public exposure，设置 bind host。仅在运维授权并完成防火墙/NAT 审核后操作。
- 同一 InfoHash 对应多个用户的活动任务时，入站连接无法提供应用层用户身份，**拒绝接纳**，杜绝跨用户 Socket 共享。
- 只在已审核公共 Torrent 的活动任务开启期间注册入站连接；任务结束立即清理。
- 因为入站 Socket 源端口一般不是对方 Tracker 宣告的监听端口，所以必须使用本次接受连接的真实 remote endpoint 建立任务内候选，不能再次尝试向原始源端口拨号。

## 验收测试

```powershell
cd D:\release\clipfetch\backend
mvn -q clean test
```

本轮干净构建实测：**335 项测试，0 failures，0 errors，5 skipped**。Git `diff --check` 返回 0。此结果证明代码与自动化回归正常，不等价于 uTP 公网互操作或传输速度达到 qBittorrent。

专项验收：
- TaskPeerActorPoolTest：同一 task 跨轮池复用、任务隔离、关闭线程；
- PeerRoundConnectionPoolTest / ParallelPeerRoundReuseTest：socket 缓存与跨轮复用；
- PeerBlockRequestWindowTest：部分 Blocks、超时截止、动态窗口及异常包；
- NativeInboundPeerServerTest：本机主动连入已授权 InfoHash，已接纳 Socket 直接提供并验证 Piece，端口默认关闭、公网暴露双开关；
- UtpPacketCodecTest：合法 SYN+DATA+SACK 编码解码、非法扩展拒绝、RTT/delay 拥塞窗口调整。

## 尚需完成（后续批次）

1. P2.2：将 PieceDownloadCoordinator 转换为 Peer-centric Actor Mailbox：每 Peer 固定 Actor、全局 Block Picker，支持多 Piece outstanding、Choke/HAVE 更新、带权公平调度；完成与跨用户 Piece Store 去重安全隔离。
2. P3.2：Block Lease 按 Peer RTT/P95 重派，BEP3 CANCEL/end-game 去重，自动检测 Snubbed Peer 并降权；按 verified B/s、wire B/s、重复字节占比持续评估。
3. P4.2：uTP retransmission + ACK/SACK + packet reordering + FIN/RESET + UDP demux + delay-based congestion + flow control 实现，再经真实公开 libtorrent/uTP Peer 互操作；只有完整 end-to-end 验证后才允许打开 uTP 功能开关。
4. 同一公开 Ubuntu ISO 固定 InfoHash 基准，在相同网络、不同时段分别运行原 Native、更新后 Native、qBittorrent，至少测试 5min：p50/p95 Block 时延、连接数、Socket 复用率、重复下载率、有效吞吐、CPU/内存、故障恢复。

## 已有任务和安全性

运行中的 Spring Boot 服务**不会自动热加载**这次代码；不应擅自重启或清空 Checkpoint。维护窗口由用户先暂停任务、正常重启后恢复。
修改无需删除数据库、已验证的 Piece 数据或用户任务，亦不会自动暴露监听端口。
