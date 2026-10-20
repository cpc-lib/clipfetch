# ClipFetch 项目交接（HANDOFF）

更新：2026-10-20；本轮目标：优化 `native` 全部下载路径的有效并发与失败 Peer 重试；工作目录：`D:\release\clipfetch`。

> 事实优先级：当前工作树及本轮命令/运行结果 > 本文 > ISSUE.md 历史记录。

## 1. 本轮结论

针对用户提供的磁力：

`magnet:?xt=urn:btih:e1fc140a6391357fa1cf08ddb70274f9c05eb88b&dn=ubuntu-26.04-live-server-amd64.iso&xl=2918598656`

已完成 Native 下载调度优化并重启后端。数据库只读检查显示该任务在本次重启前已经完成：`2,918,598,656 / 2,918,598,656`，因此本轮没有重新启动完整 ISO 下载，也没有伪造本轮速度提升数据。

## 2. 实际改动

### 配置链路

- `backend/.env`：实际运行配置新增/确认 `TORRENT_NATIVE_PEER_MAX_IN_FLIGHT_BLOCKS=128`、`TORRENT_NATIVE_PEER_FAILURE_BACKOFF_MAX_MILLIS=8000`。
- `.env.example`、`backend/src/main/resources/application.yml`、`NativeTorrentProperties`、`NativeTorrentConfiguration`：补齐配置传递。
- 最大在途 Block 合法范围为 16..256；失败 Peer 退避上限合法范围为 250..60000ms。

### Native 调度

- `PeerBlockRequestWindow`、`PeerBlockPipelineController`、`PeerBlockMailbox`：将原来固定的 Peer 64 Block 上限改为配置驱动，实际运行值为 128；同时保留协议安全上限 256。
- `PeerPieceDownloadSession`、`PeerRoundConnectionPool`、`PeerMultiplexedRound`：将配置窗口贯通到复用与非复用两条下载路径。
- `PeerHealthTracker`：失败 Peer 不再被当前 round 永久排除，改为按指数冷却后重新参与竞争；冷却上限可配置，并提供最早可重试时间，减少 50ms 无效轮询。
- `NativeTaskWorkerRoundService`：将失败退避配置传入 Peer 健康状态。
- `PeerBlockCancellation`：同步使用新的安全上限常量。

### 回归测试与计划

- 新增：
  - `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerHealthTrackerTest.java`
  - `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerBlockPipelineControllerTest.java`
  - `backend/src/test/java/cc/ivera/magnet/engine/piece/PeerBlockMailboxTest.java`
- 实施计划：`docs/superpowers/plans/2026-10-20-native-download-throughput.md`。

## 3. 验证命令与真实结果

| 命令/操作 | Exit Code / 结果 |
|---|---|
| `mvn -q '-Dtest=PeerHealthTrackerTest,PeerBlockPipelineControllerTest,PeerBlockMailboxTest' test` | **0**，3 个核心回归测试通过 |
| `mvn -q clean test` | **0**，完整后端测试通过 |
| `mvn -q clean compile -DskipTests` | **0**，干净编译通过 |
| `git diff --check` | **0**，无空白错误 |
| 变更文本 BOM 检查 | 通过，18 个文件均无 BOM |
| 重启后端 | 旧 PID 45484 已停止；`mvn spring-boot:run` 新 PID 12604 启动成功 |
| 端口检查 | 8082 当前处于 Listen；Flyway 成功校验 23 个迁移 |
| 目标 magnet 本轮速度复测 | 未执行：目标任务已在重启前完成，避免重复创建完整下载 |

## 4. 当前运行状态

- 后端由 `mvn spring-boot:run` 运行在 `SERVER_PORT=8082`，PID 12604。
- 当前没有为验证而新建的额外下载任务。
- 工作树存在本轮未提交改动；按项目约定未创建 commit。

## 5. 遗留事项与下一步

1. 若需要量化本轮速度收益，应对同一 magnet 新建一个可控的短时 Native 任务，采集至少一个完整 round 的 `downloaded_wire_bytes`、耗时、`noWorkWaits`、超时和跨轮复用计数；本轮未做该复测。
2. 如实测仍低于目标，应建立 qBittorrent 同 Swarm 基线，区分代码瓶颈与 Peer/Swarm 物理供给限制。
3. 历史遗留：`application.yml` 的 `bitfield-probe-timeout-millis` 默认值与 Java 字段默认值仍存在 600/1200 不一致，本轮未改动。
