# ClipFetch 自研 BitTorrent · P3 Piece 网络传输与受控落盘

> 日期：2026-10-14  
> 项目：D:\release\clipfetch  
> 验证：mvn -f D:\release\clipfetch\backend\pom.xml -q clean test，最终 Exit Code 0  
> 阶段定位：**本地模拟 Peer 的一个完整 Piece：BEP3 请求 -> 收到数据 -> 整片 SHA-1 -> 写入 .part 文件。并非生产可用的完整下载引擎。**

## 1. 本轮实际落地

- backend/src/main/java/cc/ivera/magnet/engine/piece/PieceBlockAssembler.java
  - 单 Piece 最大缓冲 8 MiB，按固定的 16 KiB Block 校验索引、起点、长度。
  - 允许 Block 任意顺序到达。重复相同字节不重复计数，不同字节的冲突重复块立即拒绝。
  - 只在所有 Block 到齐后使用 PieceHashVerifier 校验 Torrent v1 所声明的 SHA-1。
  - 返回经验证后的防御性复制数据；这一步不等于持久化完成。
- backend/src/main/java/cc/ivera/magnet/engine/piece/PeerPieceDownloadSession.java
  - 独立 TCP Socket 连接 Peer，BEP3 握手及 InfoHash、Peer ID 校验。
  - 发送 Interested，等待 Unchoke，按计划逐个发送 Request（同一时刻只有一个未完成的 Request）。
  - 接收 Piece 消息、校验 index/begin/Block size，处理远端 Choke、缺失 Bitfield、远端断开。
  - 限制单帧 64KiB、每阶段意外消息次数、整条单 Piece 传输的单调截止时间。
  - 使用 PieceBlockAssembler 实现内存重组及整片 SHA-1 校验；不写磁盘，不记录用户进度。
  - **原型限制**：尚无请求流水线、乱序响应多请求并发、Peer 切换、重试、速率配额或 Selector Reactor。
- backend/src/main/java/cc/ivera/magnet/engine/piece/VerifiedPieceFileStore.java
  - 存储前再次完整校验 Piece SHA-1，未通过时禁止开始写入。
  - 对每个被选中的 FileEntry，将同一 Piece 的重叠范围映射到文件偏移。
  - 跨文件 Piece 会分拆到多个受选中文件；不创建未选中文件。
  - 文件以 .clipfetch.part 结尾，FileChannel 指定位置写入后 force(true)。
  - 检查相对路径越界、输出路径符号链接及目录结构。
  - **原型限制**：调用方必须提供已经授权、用户与 taskUuid 唯一的根目录；目录检查不构成抗路径竞争的最终安全方案；多文件写入不是原子事务；尚无断点恢复/checkpoint。因此不能仅凭写入返回的字节计入可恢复进度。

## 2. 测试结果

新增 3 个测试类，8 项测试：
- piece/PieceBlockAssemblerTest.java（3）：乱序组装、相同重复块幂等、冲突/非对齐/越界、损坏 SHA-1、最大内存限制。
- piece/PeerPieceDownloadSessionTest.java（2）：本地 TCP fixture 模拟 BEP3 握手、Interested、Bitfield、Unchoke、3 个 Request/Piece Block；正确片段真实落盘到测试目录；损坏片段无法通过 SHA-1，也不会触发落盘。
- piece/VerifiedPieceFileStoreTest.java（3）：跨文件的正确偏移、仅写选中文件、错误 SHA-1 拒绝、目录穿越拒绝。

最终 Maven clean test：27 个测试类、60 项测试、Failures=0、Errors=0、Skipped=0、进程 Exit Code 0。

## 3. 当前验收边界

**完成的是本机 fixture 上“单 Piece TCP 获取 + 完整哈希 + 本地 .part 文件映射”的原型闭环。**
尚无实际公网 Piece 下载兼容性验收；尚未将 DiscoveryService/MetadataResolver/PeerPieceDownloadSession 与正式 Native Task 生命周期编排成整体服务。
生产仍运行旧 qBittorrent 下载路径，TORRENT_NATIVE_ENABLED 默认 false。
本轮没有引入 MQ/Redis/微服务、没有改写历史 Flyway、没有创建 Git commit。

## 4. 下轮优先级

1. **PieceScheduler**：按 selectedFiles 与 verified bitmap 规划全部 wanted Pieces，限制并发与内存，处理 Peer 失败/重试及块流水线。
2. **Checkpoint**：严格在整片 SHA-1 和 FileChannel force 后写入持久化状态；崩溃恢复时重新从磁盘验证 Piece，不盲信保存的位图。
3. **跨文件边界恢复**：被下载的 Piece 同时包含未选文件字节时，需保存足以恢复 SHA-1 的旁路 Piece 数据，或恢复时重新下载边界 Piece。
4. **用户级状态机**：授权 userId + taskUuid 与独立目录和会话，完成暂停/恢复/取消、文件选择、速度与真实进度更新。
5. **压力和安全测试**：大文件/大 Piece、部分下载、磁盘写失败、符号链接/重解析点竞争、网络中断、Peer 恶意消息、请求超时、端到端公网授权资源。
6. 完成验收后方可考虑启用 Native 引擎或对外提供创建任务入口。

## 5. 下一轮接续

先读 AGENTS.md 与本目录 01~14。保护 Git 工作区内未提交的现有改动；严禁把临时文件或内存 SHA-1 校验结果直接当成用户已完成进度，仍不得以 InfoHash 代替 taskUuid 执行任务操作。
