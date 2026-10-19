# ISSUE-002 跨轮 Worker Socket 复用修复计划

## 目标

让连续 Native Worker 在同一用户、任务、InfoHash 和长期 Actor scope 内，能够把上一轮安全保留的健康 TCP Socket 交给下一轮同一 Peer；污染或存在未消费数据的 Socket 仍必须退役。成功标准是：回归测试能稳定证明健康连接跨轮复用、污染 donor 重连、迟到 PIECE 不计入完成量；Worker 层测试能证明连续轮次走真实 task scope；所有相关 Maven 验证通过。

## 步骤

1. [x] 追踪连续 Worker 的 task scope、Actor pool、`beginRound/endRound`、连接池 idle/cache 命中和 telemetry 数据流，确认公网观测到 `idleSockets=1` 且 `crossRoundPeerReuses=0` 的最小原因。
   - 验证：现有 `PeerRoundConnectionPoolTest`、`PeerMultiplexedCrossRoundRetirementTest` 和源码调用链证据。

2. [x] 在最接近根因的测试层先增加一个当前实现应失败的回归测试，覆盖缓存 Peer 不在新候选列表时仍需被调度。
   - 验证：运行指定测试，先记录非零 Exit Code 和失败断言。

3. [x] 只修改造成失败的生产代码，保持 End-game CANCEL、迟到 PIECE 隔离、SHA-1 校验、用户/任务/InfoHash 归属边界不变。
   - 验证：运行新增回归测试和现有 Mailbox/跨轮/超时接管专项。

4. [x] 运行后端编译与全量测试，检查 diff、无 BOM 和工作树边界；使用现有暂停任务做有界公网 Worker 复验。
   - 验证：`mvn -q -DskipTests compile`、`mvn -q test`、`git diff --check`，并记录真实 Exit Code；本轮未执行 clean，因为运行中的验收服务持有 target 日志文件。

5. [x] 将真实进展整体写回根目录 `HANDOFF.md`，明确区分本地回归通过与公网 Worker 是否重新验收。
