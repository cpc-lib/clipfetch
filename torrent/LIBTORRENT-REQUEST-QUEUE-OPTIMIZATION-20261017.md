# ClipFetch Native BT / libtorrent 对齐：Peer Block 请求队列改造（2026-10-17）

## 基准参考
- 官方仓库：https://github.com/arvidn/libtorrent
- 核心源码：https://github.com/arvidn/libtorrent/blob/RC_2_1/src/request_blocks.cpp
- 其他参考：https://github.com/arvidn/libtorrent/blob/RC_2_1/src/peer_connection.cpp
- 实际读取 `request_a_block` 的逻辑：目标队列 `desired_queue_size` 减去当前下载队列与请求队列数量，决定缺口；可用 Piece/Block 选择依赖 Peer Bitfield；优先非忙请求，在 End-game 才允许 Busy Block 额外请求。

## 本次实现
1. `PeerRoundConnectionPool.java`：实验性的 TCP 多 Piece mailbox 在 Socket 暂无可读数据时，从固定 15ms 休眠改为 1ms；无待处理 Block 时从 15ms 改为 2ms。避免每次 Block 到达刚错过轮询时额外产生最多约 15ms 的等待。保留基于 `BufferedInputStream.available()` 的完整帧读取前检查，避免在帧中途 Timeout 导致 BEP3 字节流失步。CPU 使用可能略有增加，需基准验证。默认实验开关关闭，不改变标准 Piece 路径。
2. `PeerBlockMailbox.java`：新增任务内 QueueStats 快照：total、completed、unclaimed、primaryLeases、backupLeases；仅统计内存队列，不进入跨用户共享状态。新增相应状态转移测试，用于下一阶段将队列仪表盘纳入 Worker 遥测以及 Peer 动态调度。
3. 上轮 `PeerBlockRequestWindow.java` 已针对 RTT 和有效吞吐 EWMA 调整，每 Peer 在途请求硬上限 64；本次保留而非盲目提高上限。
4. 新增的模块不绕过 InfoHash 握手、Checkpoints、Verified Piece Store 或 fenced CAS；无真实公网监听、未打开 uTP、未执行在线服务重启或任何任务删除。

## 验证
```powershell
cd D:\release\clipfetch\backend
mvn -q '-Dtest=PeerMultiplexedRoundIntegrationTest,PeerMultiplexedEndgameIntegrationTest,PeerBlockMailboxTest,PeerBlockRequestWindowTest' test
mvn -q clean test
git -C D:\release\clipfetch diff --check
```
专项已通过；全量结果以最终 exit code + Surefire XML 为准。开发代码在工作区未 commit/push。

## 仍需推进
- 将 QueueStats 和 Peer Actor 网络指标透出到 task metrics，分开计量真实 payload rate、queue occupancy、空等比例、Peer RTT p95、Peer choke 时间、Socket 复用率、验收 CAS/fsync 阻塞时间。
- 避免每轮断开多 Piece Socket；从 libtorrent 对齐持续的 Peer-centric 请求及下载队列和 Bitfield 更新。
- 采用相同官方 Ubuntu 种子、相同网络时段、一次只运行一个客户端，比较 qBittorrent 与 ClipFetch 至少 5 分钟的 verified MB/s、CPU、磁盘队列、payload MB/s。若提升未实测，不宣称追平 libtorrent。
