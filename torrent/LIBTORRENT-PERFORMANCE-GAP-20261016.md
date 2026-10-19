# ClipFetch Native BitTorrent 与 qBittorrent/libtorrent 性能差距及落地计划

> 日期：2026-10-16。比较对象是用户截图中的 Ubuntu 26.04 Live Server ISO（公共合法测试种子）。
> 本文为基于官方源码/文档以及 ClipFetch 实际代码、日志的分析，不代表已达到 libtorrent 性能。

## 1. 源码定位

- qBittorrent UI / Session 封装：[qbittorrent/qBittorrent/src/base/bittorrent/sessionimpl.cpp](https://github.com/qbittorrent/qBittorrent/blob/master/src/base/bittorrent/sessionimpl.cpp)。此处管理 session settings / DHT / LSD / PeX / libtorrent 会话，并非独立的 BT 传输引擎。
- 下载核心：[arvidn/libtorrent](https://github.com/arvidn/libtorrent)，重点：`src/piece_picker.cpp`、`src/peer_connection.cpp`、`src/bt_peer_connection.cpp`、`src/request_blocks.cpp`、`src/utp_stream.cpp`、`src/torrent.cpp`。
- libtorrent 官方说明：[requesting pieces](https://blog.libtorrent.org/2011/11/requesting-pieces/)、[slow start](https://blog.libtorrent.org/2015/07/slow-start/)、[streaming](https://www.libtorrent.org/streaming.html)、[Settings](https://www.libtorrent.org/reference-Settings.html)。

### 比较口径

截图中 qBittorrent 约数百 KiB/s（约 700 KiB/s），ClipFetch Native 约 199 KiB/s，但截图时刻、可用 Peer、网络负载和速率采样时间未校准，不能据此认定一个稳定的 3~4 倍差距。更可信的差异是 qBittorrent 有更多实际参与传输的 Peer；Native 日志里曾出现 90+ 候选、仅 5~16 可用并发、整轮有大量失败 Piece 的情况。

## 2. 核心机制对照

| 维度 | libtorrent | ClipFetch 本轮前 | 本轮改动及剩余缺口 |
|---|---|---|---|
| 分块调度 | Peer-centric、按连接队列空位获取 Block，考虑吞吐/时延和可用性 | 按 Piece 选择 Peer，整 Piece 超时后重新获取 | **已实现单 Piece 跨 Peer Block 暂存续传**；仍缺跨 Piece、Peer-centric 全局调度 |
| Block pipeline | 按下载速率与估算队列时间维护 outstanding requests，应用层 slow start | 初始 8、上限 32 个 16KiB Block | **已调整初始 16、上限 64**，最多 1 MiB/Peer outstanding；后续需要基于 RTT/EWMA 自适应而非固定上限 |
| 失败处理 | Block 超时重排、维护下载队列和 Peer 质量 | 部分 Block 已收到仍需从头下载整个 Piece | **已保留失败 Peer 送达的合法 Block**，仅向下一 Peer 请求缺失块；最后完整 Piece 必须通过 SHA-1 |
| Bitfield | Piece 可用性与连接健康是不同信号 | 无当前 Piece 时错误关闭健康 TCP Socket | **已区分不包含 Piece 与传输故障**；保留原 TCP 连接并避免故障惩罚 |
| 生命周期 | 持续 session，Keepalive、请求队列与连接管理 | 按轮 Piece 线程+缓存 TCP 连接，可跨轮复用但命中率有限 | 已有跨轮连接复用统计；未来应引入持续 Peer actor/事件循环 |
| Transport | TCP + uTP，可接受入站连接 | 主要 TCP 主动拨号 | **未实现 uTP / 完整入站 Peer 服务**；性能/覆盖率差距可能很大 |
| 发现 | DHT、Tracker、PeX、LSD 与 session 事件处理 | 有这些发现通路但质量和时效仍有限 | 之前已加入动态刷新；本轮未替换成熟 tracker/DHT 路由算法 |
| 进度 | 多层 Piece/Block 状态 | manifest + CAS 的 64 Piece 组提交 | 原有有效 Block/暂存统计仍在，只有 SHA-1+fsync+CAS 后才记为安全进度 |

## 3. 本轮已经落地的 P1 代码改造

### 3.1 异常 Peer 断线后的 Block 续传

本轮改造涉及：

- `PieceBlockAssembler.java`：`missingBlocks(planner)` 与 `reset()`。
- `PeerBlockRequestWindow.java`：允许稀疏 Block 列表，并严格拒绝未请求/重复/错误偏移，初始 16 / 最大 64 个 outstanding。
- `PeerPieceDownloadSession.java`、`PeerRoundConnectionPool.java`：复用同一 Piece 的临时 assembler，失败后只向下一个 Peer 请求缺失块。
- `PieceDownloadCoordinator.java`：并行 Worker 内单 Piece 的 Peer 重试共享一个内存 assembler，只有成功验证完整 Piece 才能由 owner 线程提交 Checkpoint。

严格约束：

1. 临时 Block 只在**一个 Worker/一个 Piece**范围内复用，不跨用户、跨任务、跨持久化恢复共享。
2. Block 请求及响应仍执行索引、offset、长度、重复校验；只有完成整个 Piece 的 SHA-1 才能提交。
3. 完整 Piece SHA-1 不匹配时调用 `reset()` 清空所有临时 Block，不延续受污染数据；遇到其他 Peer 的网络失败时保留此前可用的临时块。
4. 暂存 Block 的内存仍受并发 Piece 上限及总缓冲预算限制；不会将部分 Block 直接计入可靠下载进度。

回归用例 `PeerResumableBlockTransferTest`：一个 256 KiB Piece 的第一个 Peer 交付前 8 个 16 KiB 块后断开；第二个 Peer 只交付后 8 块。总共接收完整 16 块，而非重复 8 块。最终完成完整 SHA-1 校验并落盘。

### 3.2 Bitfield-negative 不是 TCP 故障

`PeerRoundConnectionPool.PieceUnavailableException` 将“Peer 不拥有当前 Piece”与 Socket I/O 错误区分：

- 保留已建立并通过握手的 Socket，不因为当前 Piece 不可用而关闭。
- `PieceDownloadCoordinator` 不给这类 Peer 增加失败惩罚；改为尝试其他 Peer。
- `PeerRoundConnectionPoolTest` 覆盖已连接 Peer 提供第一块、明确缺少第二块的情况，校验缓存和关闭行为。

### 3.3 请求管线扩容

256 KiB Piece 包含 16 个 16 KiB Block：原始队列一次只请求 8 个 Block，至少需要等待首批响应才能请求余下 8 个。现在第一波可请求完整 16 个 Block，减少人工引入的往返等待。大 Piece 使用上限 64 个 Block（1 MiB）并随着收到 Block 增长窗口；仍限制内存和拒绝非法响应。

## 4. 推荐的后续顺序

| 阶段 | 优先级 | 内容 | 验收 |
|---|---|---|---|
| P2 | 高 | 持续 Peer actor、跨 Piece 的 per-peer 请求队列、RTT/速率 EWMA 动态调窗口，处理 HAVE / Choke / Unchoke | 真实活跃 Peer > 当前、TCP 复用率提高、重复下载降低 |
| P3 | 高 | Block-level deadline / slow-peer 抢占、同一 Block 跨 Peer 的安全有限重试、尾部 end-game 去重 | p95 Piece 完成时延降低、冗余字节可度量 |
| P4 | 高 | uTP + UDP Socket manager、入站 TCP/uTP 与 NAT/端口映射（需要明确部署/防火墙授权） | 在相同公共 Ubuntu swarm 获取更高连接覆盖 |
| P5 | 中 | Peer 資源预算隔离、共享 Verified Piece Store、主机级限速/公平性、下载队列与磁盘缓存优化 | 多任务长时间稳定、完整故障恢复 |
| P6 | 中 | BEP 52 v2 与 Hybrid 的 Merkle 验证、元数据与哈希请求调度兼容 | 专项 v1/v2/hybrid 互操作矩阵 |

## 5. 验收方式

使用相同的 **Ubuntu 官方 ISO** Torrent，分别记录 qBittorrent 与 Native 的 5 分钟窗口：实际下载速度（网络 payload 与有效验证进度分列）、TCP 连接数及复用命中率、有效 Peer、失败 Piece、Block 重复下载、每 Peer 延迟、下载队列深度与 p50/p95 Piece 延迟。必须注明同一设备/网络时间段，同一个 InfoHash，尽量避免同时运行抢带宽的两个任务。

运行本地回归：

```powershell
cd D:\release\clipfetch\backend
mvn -q '-Dtest=PeerResumableBlockTransferTest,PeerBlockRequestWindowTest,PeerRoundConnectionPoolTest' test
mvn -q test
```

**重要：** 代码落地和测试通过不代表当前 Spring Boot 进程已加载新类；要测试新版网络逻辑，应先暂停当前任务，维护窗口正常重启服务后恢复。不要通过删除数据库、Checkpoint 或清空正在下载的文件来“提速”。
