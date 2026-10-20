# ClipFetch 项目交接（HANDOFF）

更新：2026-10-20（第 2 次）；最新操作：连接超时调优已实测生效（546→840 KiB/s）；定位并修复跨轮 socket 复用恒为 0 的根因（缓存寿命 < 单次 RTT）；工作目录：`D:\release\clipfetch`。

> 事实优先级：当前工作树及此次测试输出 > 本文 > ISSUE.md 历史记录。

## 1. 当前状态与系统边界

ClipFetch 单体多媒体解析与下载平台。后端 Java 21 + Spring Boot 3.5.4；前端 Vue 3 + Vite 6 + Tailwind CSS 3，vue-router 路由架构 + 异步懒加载分包 + 移动端响应式导航。磁力下载支持 qBittorrent 与自研 Native BitTorrent 双引擎。

## 2. 速度优化进展（任务 a9c3273d，2.72 GiB，目标 6MiB/s）

**已实测生效**（22:08 重启加载新配置）：
- 每轮 256 piece 耗时 88~170s → **77.9s**（546~746 → **840 KiB/s**）
- `noWorkWaits` 10~16 万 → 5.8 万；`timeout` 失败仍主导（73/轮，为真死 Peer 握手超时，属正常探测成本）

**本轮新修复（跨轮 socket 复用恒为 0 的根因）**：
- [PeerRoundConnectionPool.cacheLifetimeNanos](backend/src/main/java/cc/ivera/magnet/engine/piece/PeerRoundConnectionPool.java#L44-L55)：原逻辑「RTT P95≥800ms → 缓存仅 15s」，而本 Swarm P95=32s——**缓存寿命小于单次 Block RTT**，socket 在轮间/领取间隙必被判陈旧关闭，`crossRoundReuseHits` 永远为 0。改为随实测 RTT 缩放：`max(30s, min(2×P95, 90s))`（本 Swarm → 64s），低延迟快路（≤350ms 且有复用 → 75s）不变。作用域生命周期已核实无问题（`beginTask` 每连续会话一次，`endTask` 仅在会话结束）。
- 预期：已知好 Peer 跨轮免 TCP 建连+握手+unchoke（每 Peer 每轮省约 10~13s 串行空转），配合失败退避减少 Actor 空转。

**诊断结论存档**：速度差距=有效并发坍缩（每 Actor 平均在途约 2 块 vs 目标 8~64），源于失败 Peer 冷却导致可租池枯竭 + 每 Peer 每轮串行重建连接。已实施：连接超时放宽（建连 3.5s/握手 6s）、复用寿命修复。下一杠杆：失败 Peer 的指数退避上限 30s 偏长。

## 3. 上轮修复存档

- Bitfield 探针校验上限 5000→10000ms + 错误信息带实际值（[NativeTaskWorkerRoundService.java:386-393](backend/src/main/java/cc/ivera/magnet/engine/piece/NativeTaskWorkerRoundService.java)）。

## 4. 执行过的验证命令与真实结果

| 命令 | 真实 Exit Code | 结果 |
|---|---|---|
| `mvn -q clean compile -DskipTests` | **0** | 编译通过（复用修复后） |
| BOM 检查 | — | `112 97 99` 无 BOM |
| `mvn clean test` | — | src/test/java 已被提交 `c611d4f` 清空，恒 0 tests，无回归能力 |
| `.env` 超时调优实测 | — | 已生效：546→840 KiB/s |
| **跨轮复用修复** | — | **未实测**（需重启后端观察「复用/跨轮」计数与速度） |

## 5. 遗留事项与下一步

1. **重启后端**加载 PeerRoundConnectionPool 新字节码，观察前端面板「复用」与「跨轮」计数是否 >0、每轮耗时是否进一步下降。
2. **建立公平基线**：qBittorrent 下载同一个 a9c3273d 种子测峰值；6MB/s 历史基线来自 ubuntu 种子（bc44f3ad），Swarm 质量不同。若 qBittorrent 在本 Swarm 也仅几百 KiB/s，则受 Swarm 物理限制。
3. 若仍 <1.5MiB/s：下一杠杆为 `PeerHealthTracker.failure()` 指数退避上限 30s 缩短（如 8s），让死 Peer 更快重试、活 Peer 更快解冻。
4. 测试体系已清空，如需回归能力需重建核心测试。
5. 预存不一致（未改）：yml `bitfield-probe-timeout-millis` 默认 600 vs Java 字段默认 1200。
