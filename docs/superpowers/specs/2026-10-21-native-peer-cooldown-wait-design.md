# Native Peer 冷却等待优化设计规格

## 1. 目标

减少 Native multiplexed Actor 在暂时没有可租 Peer 时的无效唤醒与重复扫描，同时保持现有 Peer 冷却时长、租约选择结果、失败 Peer 受控回退、通知唤醒和网络行为不变。

## 2. 根因与证据

- `PeerMultiplexedRound` 在首次 `leaseExclusive` 返回 `null` 后，原先使用 50–250ms 的轮询 `sleep`；即使已经计算出冷却剩余时间，也只会在短轮询窗口后重新扫描。
- `PeerHealthTracker` 已能计算空闲且可服务剩余 Piece 的 Peer 中最早的 `cooldownUntil`（`nextLeaseDelayNanos`），但当前等待循环没有使用这个结果。
- 当 Peer 的冷却剩余时间为 500ms、数秒或更长时，Actor 会在冷却结束前按 50–250ms 轮询窗口反复唤醒，重新执行候选扫描；每次扫描都在同一个 tracker `synchronized` 监视器内完成。
- `release`、成功观测、失败处理、Peer 发现和 swarm amnesty 已通过 `notifyAll` 唤醒等待 Actor，因此等待时间可以延长而不牺牲这些状态变化的响应速度。
- 当前没有未完成 Native 下载任务，历史日志不能提供本修复的前后速度基准；本设计只针对可验证的调度等待行为。

## 3. 方案

### 3.1 Tracker 内的动态等待窗口

在 `PeerHealthTracker.awaitLease` 的每次无候选循环中：

1. 保持现有 `leaseExclusive(pieces, availability, failedThisRound)` 首次尝试及其所有过滤、评分和失败回退语义。
2. 计算本次调用剩余的等待截止时间。
3. 调用现有 `nextLeaseDelayNanos(pieces, availability)`，得到空闲且可能服务剩余 Piece 的 Peer 中最早冷却结束的剩余时间。
4. 实际等待窗口取 `min(剩余等待时间, 最近冷却剩余时间)`。
   - 没有可预估冷却的候选（返回 `Long.MAX_VALUE`）时，等待调用方给出的完整边界；这覆盖所有候选忙碌、没有可服务 Piece 或等待通知的情况。
   - 计算结果为零时等待至少 1ms，避免状态未变化时形成忙循环。
5. 使用现有 monitor `wait`；被 `notifyAll` 或线程中断提前唤醒后，重新执行同一租约选择。

为使等待窗口计算可在无时序依赖的单元测试中验证，增加一个包可见的静态纯策略方法，仅负责对“剩余截止时间”和“最近冷却时间”取边界；它不保存状态，也不对外形成 API。

### 3.2 Actor 等待上限

`PeerMultiplexedRound` 把原先 50–250ms 轮询 sleep 替换为 `awaitLease`，其单次等待上限扩展到现有的 3 秒静默退出边界，并以同一个包内常量复用 Actor 的静默退出判断。这样：

- 最近冷却结束前不会再按 50–250ms 轮询窗口无意义扫描；
- Peer 释放、成功、失败、发现或 amnesty 的通知仍会立即唤醒 Actor；
- 没有冷却候选且没有通知时，Actor 最多等待现有 3 秒静默边界，然后继续原有退出判断；
- 任务取消仍由线程中断处理，任务总 deadline 和 drain deadline 不改变。

不修改 owner supervisor 的任务 deadline、socket 生命周期、失败计数、Actor admission、Mailbox、请求窗口或 Piece 提交流程。

## 4. 语义与并发不变量

- 不修改任何 `Health.cooldownUntil`、失败计数、冷却指数或 amnesty 行为。
- 动态等待只改变下一次重试的时间，不改变 `leaseExclusive` 的候选过滤、未失败 Peer 优先级或失败 Peer 回退条件。
- `nextLeaseDelayNanos` 仍在 tracker 监视器内读取 `inFlight`、冷却和 Piece 可用性；等待窗口计算与候选选择之间不会暴露中间状态。
- 释放或其他状态变更的 `notifyAll` 仍可使等待早于计算的冷却时间结束；提前唤醒后必须重新选择，不直接复用旧候选。
- `failedThisRound` 仍只读；当所有未失败候选暂时不可用时，已冷却的失败 Peer 仍可按原有受控回退规则被选择。
- 1ms 进度下限仅防止零窗口忙循环，不改变任何 Peer 选择或冷却值。

## 5. 测试与验收

在 `PeerHealthTrackerTest` 增加：

1. 动态等待策略在“最近冷却小于剩余截止时间”“剩余截止时间更短”“没有可预估冷却”和“零冷却进度下限”四种情况下返回确定结果。
2. `nextLeaseDelayNanos` 对空闲冷却 Peer 返回最早冷却剩余时间，对全部忙碌/不可服务 Peer 返回无界等待标记；已有失败回退和通知唤醒测试保持不变。

验证顺序：

- 先运行 `PeerHealthTrackerTest` 的 RED/GREEN；
- 运行 `PeerBlockMailboxTest`、`PeerHealthTrackerTest`、`PeerRoundConnectionPoolTest` 聚焦测试；
- 运行后端 `mvn -q clean test` 与 `mvn -q clean compile -DskipTests`；
- 执行 `git diff --check` 与变更文本无 BOM 检查；
- 重启 8082 后端并确认唯一监听者。

完成后只报告调度等待代码和测试证据，不报告未经可重复未完成磁力任务基准支持的下载速度百分比。

## 6. 非目标

- 不改变 Peer 冷却时长、失败计数、租约排序、失败回退、Peer 数量或 Actor 探索窗口。
- 不引入新的环境变量、定时任务、调度线程、队列、缓存、数据库迁移、API 或前端代码。
- 不改变 250ms 的其他 Mailbox overdue/reassignment 检查路径。
- 不修改 Native 网络协议、socket 超时、Block 请求窗口、磁盘提交或任务恢复行为。
