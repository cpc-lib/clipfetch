# ClipFetch vs libtorrent — 网络吞吐与实时速度优化（2026-10-17）

## 问题

Ubuntu ISO 任务截图：81.1%，验证总量约 2.20 GiB / 2.72 GiB，已验证均速约 808.31 KiB/s，网络接收瞬时 0 B/s，当前轮已有 107.5 MiB（43 Piece）待校验/批量提交。上述值不是相同的时间窗口：全程验证均速不能作为当前有效载荷速度；轮内积累的数据不能直接等同于持久化数据。本轮解决指标口径和 Peer Block pipeline 的可核实问题，尚未进行公网 A/B，因此不宣称达到 qBittorrent 吞吐。

## 对照 libtorrent 源码

- https://github.com/arvidn/libtorrent/blob/RC_2_1/src/piece_picker.cpp — 按 Piece/Block 优先级及状态调度。
- https://github.com/arvidn/libtorrent/blob/RC_2_1/src/peer_connection.cpp — 每 Peer 的待请求队列、max_out_request_queue 和吞吐控制，提供 bounded request queue 思路。
- 仅借鉴算法，不复制 BSD 代码。下一步还需从实际 Peer queue、disk queue、TCP/uTP 栈、块抢占和缓存命中率持续与 libtorrent 对齐。

## 本轮实际修改

1. `PeerBlockRequestWindow.java`
   - 原代码仅用从本 Piece 开始的累计速率 × 固定 3 秒计算目标 Block 请求数，极易受初始采样影响。
   - 改为近期吞吐 EWMA × RTT 感知请求时间（2x RTT，边界 0.25—2 秒），在已有滑动 slow-start 窗口、最少 4、最多 64 个 16KiB Block 内自适应。
   - 收到 ACK 后每 ≥100ms 更新最近速率 EWMA。保留 per-Block deadline 与已有 Block 数量限制，避免消息噪声阻碍真正 Block 超时。
2. `NativeNetworkRateMeter.java` + `NativeWorkerJobManager.java`
   - 原 metrics() 每次 API 查询都以该请求间隔的零增量替换实时速度，导致浏览器短周期拉取显示 0 B/s，即使下一瞬间继续有流量。
   - 增加任务本地、无跨用户状态共享的平滑速度计：首次接收到有效载荷立即非零；之后 ≥500ms 取样并通过 EWMA 平滑；连续约 5 秒无字节才视为真正停止（显示零速）。计数器保持单调、不回退。
   - 保留总网络载荷（非持久化进度）与平均验证速度、轮内待提交体积三个独立数值。
3. 新增 `NativeNetworkRateMeterTest.java`：高频查询不闪零、连续无流量正确归零、传输恢复、参数边界。
4. 原有 `PeerBlockRequestWindowTest` 与 `NativeWorkerJobManagerTest` 仍用于边界兼容验证。

## 结果与下轮优先级

运行 `cd D:\release\clipfetch\backend; mvn -q clean test`，以最终 Surefire XML 统计和退出码为准。本轮未重启 Spring Boot，也未改动 .env、数据库/Checkpoint、不提交 Git、不启用实验多 Piece mailbox 或 uTP 公网。
下一阶段：观测 verified delta/5min、payload 5min、有效 Peer/失败 Peer、Block RTT p50/p95、request queue 占用与取消率、磁盘 fsync 和 CAS 耗时，并测试空请求队列是否影响 0 B/s；然后在相同 Ubuntu torrent 与独立下载时段对比 qBittorrent。真实连续 0 B/s 超过 5 秒仍表示没有收取数据，需要调查 Peer choke、无有效 Piece、Tracker 网络或读线程阻塞，而不是掩盖。
