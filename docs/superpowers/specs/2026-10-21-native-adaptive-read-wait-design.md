# Native Peer 自适应读等待优化设计规格

## 1. 目标

减少 Native multiplexed `runMailbox` 在已有 Block 请求、但 Peer 暂无数据时由固定 50ms socket timeout 造成的重复唤醒与循环检查，同时保持任务 deadline、Peer 首块/停滞超时、取消响应、协议解析和请求窗口语义不变。读等待仍受现有 Mailbox 250ms overdue/reassignment 检查节奏约束，避免长读延迟 CANCEL 和 overdue lease 处理。

本轮只处理 A；B 冷却等待已经完成，C 连续 Worker 退避留待后续独立设计。

## 2. 根因与证据

- `PeerRoundConnectionPool.runMailbox` 当前每次调用 `PeerIncrementalFrameReader.readFrame` 都传入固定 `50` 毫秒。
- `PeerIncrementalFrameReader` 已支持按调用设置 socket `SoTimeout`，并缓存同一 Socket 的当前 timeout；数据到达时 `InputStream.read` 会立即返回，不需要依赖 50ms tick 才能处理完整 Frame。
- `runMailbox` 已有 `noProgressTimeoutNanos`，并在读超时后按“部分 Frame 最近进度或本轮空闲起点”检查 Peer 是否停滞；因此读等待窗口可以直接受任务 deadline 和该停滞 deadline 的较小值约束。
- 固定 50ms 会让每个有请求的 Actor 在无数据期间最多每秒醒来约 20 次，增加高 Actor 数下的线程调度和共享 mailbox 检查；读等待改为任务/Peer/Mailbox 三者较早窗口，Mailbox 检查窗口沿用现有 250ms cadence；当前没有未完成 Native 任务，不能据此报告实际下载速度提升百分比。

## 3. 方案

### 3.1 动态读等待窗口

在 `PeerRoundConnectionPool` 增加包可见的纯策略方法：

```java
static int boundedReadTimeoutMillis(long remainingDeadlineNanos,
                                    long remainingProgressNanos)
```

策略只接收两个剩余时间，不读取或修改 Socket、Mailbox 或任务状态：

1. 取任务 deadline 剩余时间、Peer no-progress deadline 剩余时间与现有 Mailbox overdue/reassignment 检查剩余时间的较小值。Mailbox 检查窗口最多为现有 250ms，不新增配置。
2. 用向上取整的毫秒数传给现有 `readFrame`，保证正的纳秒窗口不会被截断为零。
3. 窗口已过期或小于 1ms 时返回 1ms；调用方随后沿用现有循环条件和停滞判断退出，不改变超时分类。
4. 超过 Java `int` 毫秒范围时限制为 `Integer.MAX_VALUE`。

`runMailbox` 在每次进入 socket 读之前计算：

- `remainingDeadlineNanos = deadline - now`；
- `remainingProgressNanos = noProgressTimeoutNanos - (now - progress)`，其中 `progress` 继续沿用现有的部分 Frame 最近进度或 `idleSince`；
- `remainingMailboxCheckNanos` 在 `nextReassignmentCheck` 已初始化时取 `nextReassignmentCheck - readNow`；未初始化时使用现有 250ms cadence 作为 fallback。已过期且有可重分配槽位时保留过期值，让循环立即处理 overdue lease；已过期但 pipeline 已满时回退到 250ms，避免无可重分配工作时形成 1ms 忙循环。
- 将策略结果传给 `incremental.readFrame(link.in,link.socket,readTimeoutMillis)`。

读返回 Frame 时先检查任务 deadline，超过硬 deadline 的 Frame 不再提交给 Listener；未过期时沿用现有协议处理。读返回 `null` 时保留原有 `now/progress` 重新检查和 `SocketTimeoutException` 失败路径。`PeerIncrementalFrameReader` 的增量 Header/Payload 保留、Socket timeout 缓存和 Frame 解码不改。

### 3.2 取消与并发边界

- 不新增线程、Selector、定时器、配置项或网络连接；读等待复用现有 Mailbox 250ms 检查 cadence，不增加新的可配置参数。
- 用户任务取消仍由现有 `interrupt + close Socket` 路径唤醒阻塞读；跨 Actor 协议 CANCEL 和 overdue lease 最多等到现有 250ms 检查窗口，任务 deadline 通过读返回后的硬检查保持边界。
- 不改变 `noProgressTimeoutNanos` 的计算、Peer RTT 自适应、请求窗口、Mailbox claim/reassignment、失败计数或连接复用。
- 不修改其他 `receive`/握手/单 Piece 读路径的 timeout 逻辑。

## 4. 不变量

- 有数据可读时，处理延迟仍由 Socket/InputStream 返回决定，不增加固定等待。
- 单次 socket 等待按任务 deadline、Peer no-progress 和现有 Mailbox 检查窗口取最小值，并按毫秒向上取整；读返回后硬检查任务 deadline，不提交越过 deadline 的 Frame。
- Partial Frame 不能因 timeout 窗口变化而丢失或重新解释；下次读继续使用同一个增量 Reader 状态。
- Peer 无数据直到 no-progress deadline 时仍按原有失败路径关闭连接、记录失败并重租其他 Peer。
- 用户取消、round deadline、drain deadline 和现有异常分类保持不变。

## 5. 测试与验收

在 `PeerRoundConnectionPoolTest` 增加纯策略测试，覆盖：

1. Peer no-progress 剩余时间更短时选择该窗口；
2. 任务 deadline 更短时选择任务窗口；
3. 已过期/亚毫秒窗口返回 1ms 进度下限；
4. 超大纳秒窗口不会溢出毫秒 `int`。

保留并补充 `PeerIncrementalFrameReader` 相关行为测试，覆盖动态 `SoTimeout` 更新、partial Header/Payload 在 timeout 后继续拼接；并验证：

- `mvn -q "-Dtest=PeerRoundConnectionPoolTest" test` 先 RED 后 GREEN；
- `mvn -q "-Dtest=PeerBlockMailboxTest,PeerHealthTrackerTest,PeerRoundConnectionPoolTest" test`；
- 后端 `mvn -q clean test`、`mvn -q clean compile -DskipTests`、`mvn -q test`；
- `git diff --check` 与变更文本无 BOM 检查；
- 重启 8082 并确认唯一监听者。

完成后只报告读等待策略和测试证据；没有可重复的未完成 Native 任务时，不报告下载速度百分比。

## 6. 非目标

- 不改变 Peer 首块 timeout、stall timeout、connect/handshake timeout 或 RTT 计算。
- 不改变 Block request window、Piece/Actor 数量、Mailbox 250ms overdue/reassignment 检查或 Peer 选择。
- 不引入新的环境变量、定时调度、线程、队列、缓存、数据库、API 或前端代码。
- 不处理连续 Worker 失败重试和停滞退避；该内容属于后续 C 设计。
