# ClipFetch · 自研 BitTorrent 引擎设计文档

> 日期：2026-10-14 · 进度：**已实现默认关闭的认证异步 Metadata API：提交、状态、分页文件列表与取消；PeX/LSD/公共 Tracker 协议组件仍是受控原型，Native 文件下载尚未投入生产**
> 本目录属于项目 D:\release\clipfetch，收纳技术规划、落地交接和后续实施记录。

## 文档索引

| 文件 | 目的 | 读者 |
|---|---|---|
| [01-自研BitTorrent引擎研发规划书.md](./01-自研BitTorrent引擎研发规划书.md) | 建设目标、阶段任务、优先级、风险、DoD、用户隔离验收矩阵、灰度与回滚 | 产品、技术负责人、实施 Agent |
| [02-自研BitTorrent协议技术实现方案.md](./02-自研BitTorrent协议技术实现方案.md) | BEP3/5/9/10/15 技术实现、Java 包结构、任务模型、数据库、API、Piece 进度、断点恢复、安全和测试 | Java 开发、测试、架构评审 |
| [03-实施进度-P0-P1.md](./03-实施进度-P0-P1.md) | 首轮实际代码变更、测试结果、仍未实现部分、下一阶段交接 | 开发与实施 Agent |
| [04-实施进度-P2-协议基础.md](./04-实施进度-P2-协议基础.md) | Tracker / DHT / KRPC 字节协议实现、测试和 P2 网络阶段交接 | 开发与实施 Agent |
| [05-实施进度-P2-UDP联通.md](./05-实施进度-P2-UDP联通.md) | UDP Tracker 与 DHT Ping 联通、地址策略、模拟网络测试及接续任务 | 开发与实施 Agent |
| [06-实施进度-P2-HTTP-Tracker.md](./06-实施进度-P2-HTTP-Tracker.md) | HTTP Tracker 实际连接、报文边界、本地模拟测试及待补项 | 开发与实施 Agent |
| [07-实施进度-P2-DHT查询.md](./07-实施进度-P2-DHT查询.md) | DHT 单节点 get_peers/find_node UDP 查询、响应验证和后续工作 | 开发与实施 Agent |
| [08-实施进度-P2-DHT迭代发现.md](./08-实施进度-P2-DHT迭代发现.md) | DHT 有界迭代 Peer 发现、测试及后续接入计划 | 开发与实施 Agent |
| [09-实施进度-P2-PeerTCP握手.md](./09-实施进度-P2-PeerTCP握手.md) | Peer 候选池、TCP 握手、InfoHash 验证、测试及交接 | 开发与实施 Agent |
| [10-实施进度-P2-BEP10-BEP9元数据.md](./10-实施进度-P2-BEP10-BEP9元数据.md) | 扩展握手、元数据分片重组、SHA-1 校验、测试与交接 | 开发与实施 Agent |
| [11-实施进度-P2-元数据端到端.md](./11-实施进度-P2-元数据端到端.md) | TCP Peer 会话、BEP10/BEP9 本地端到端校验、后续完整 Magnet 流程交接 | 开发与实施 Agent |
| [12-实施进度-P2-Magnet解析闭环.md](./12-实施进度-P2-Magnet解析闭环.md) | DiscoveryService、MetadataResolver、Tracker→Peer→文件列表本地集成测试与交接 | 开发与实施 Agent |
| [13-实施进度-P2安全加固-P3分片基础.md](./13-实施进度-P2安全加固-P3分片基础.md) | 网络地址与绝对超时加固、P3 Piece 规划与 SHA-1 测试、下一阶段交接 | 开发与实施 Agent |
| [14-实施进度-P3-Piece网络传输与受控落盘.md](./14-实施进度-P3-Piece网络传输与受控落盘.md) | BEP3 单 Piece 下载、Block 重组、跨文件 .part 写入与测试交接 | 开发与实施 Agent |
| [15-实测-Ubuntu26.04-Magnet-Metadata.md](./15-实测-Ubuntu26.04-Magnet-Metadata.md) | Ubuntu 26.04 官方 InfoHash 比对、真实 DHT/HTTPS Tracker/Peer 实测、自动 Bootstrap 实现与限制 | 开发与实施 Agent |
| [16-实施进度-PeX-LSD-公共Tracker加速.md](./16-实施进度-PeX-LSD-公共Tracker加速.md) | BEP11 PeX、BEP14 LSD、可信公共 Tracker 发现、Ubuntu 实机 Announce 结果和后续交接 | 开发与实施 Agent |
| [17-实施进度-Private隔离-异步Metadata.md](./17-实施进度-Private隔离-异步Metadata.md) | 经验证元数据的 private 隔离、公开许可、私人 Tracker 白名单和多用户 Metadata Job 原型 | 开发与实施 Agent |
| [18-实施进度-认证Metadata-API.md](./18-实施进度-认证Metadata-API.md) | Spring Boot 认证异步元数据接口、默认关闭功能开关、多用户隔离、测试与剩余风险 | 开发与实施 Agent |

## 一个最重要的设计原则

~~~text
资源唯一性：infoHash
业务任务唯一性：taskUuid
用户归属：userId

同一 infoHash 可以有 N 个 taskUuid。
同一 taskUuid 永远只归属于一个 userId。
用户 A 的暂停、删除、文件选择、进度、连接和磁盘不能改变用户 B 的任务。
~~~

因此，新引擎将保留已验证元数据、DHT 等必要的只读/网络基础设施共享，但每个用户的下载会话、Piece 调度、位图、速率、输出目录和状态持久化互相独立。**不等同于不同公网 IP 或物理带宽的完全隔离。**

## 对照当前项目的实施事实

- 后端 Java 21 + Spring Boot 3.5.4、Vue 3 + Vite + Tailwind、SQLite/MySQL，保持当前单体部署，不新建微服务。
- 旧磁力模块 cc.ivera.magnet 依赖 qBittorrent，历史功能保持可用。
- 旧表 magnet_download_task 中 info_hash 唯一约束属于旧引擎模型；native 使用全新的 torrent_native_task 表以 taskUuid 管理。
- 旧的 /api/magnet/downloads/{hash} 不可直接用来操作 native 多用户下载，否则会重新引入同 Hash 任务冲突。
- native 新接口统一用 /api/magnet/native/tasks/{taskUuid}，每次从已认证上下文取得 userId 并验证归属。
- 由于现有 V12 迁移已存在，规划示例使用 V13；**正式实施前再次核实两个数据库目录的最新迁移版本号**。
- 项目根目录 AGENTS.md 优先约束代码风格、SQL XML、双库迁移、无 BOM 和新基础设施引入。

## 推荐启动实施顺序

1. 阅读 AGENTS.md、规划书、技术方案，确认与现有需求和网络部署一致。
2. 先完成协议 fixture 测试和引擎 API/feature flag，不动 legacy。
3. 再做 Magnet、Bencode、Tracker、DHT、ut_metadata 获取文件清单。
4. 之后实现 TCP Peer/Block/Piece/哈希校验/存储和恢复。
5. 引入 UUID 任务存储、双库迁移、按用户隔离的 REST API 与进度计算。
6. 最后对接 Vue 下载管理、短时票据、Range，以及 A/B 同 Hash 并发隔离测试。
7. 灰度启用 NATIVE，旧 qBittorrent 数据不做自动迁移，具备回滚手段后再决定默认切换。

**阶段边界**：已有 Native 协议组件、模拟 Peer 集成测试及默认关闭的 Metadata API，但公网磁力解析存在兼容性限制，持久化 Piece 下载与断点恢复尚不完整；不得宣称可替换 qBittorrent。

## 完成判定

- 两用户同 Hash 可以独立下载，任选不同文件。
- 暂停/恢复/删除任意一个任务，另一任务不受影响。
- 每个任务的 verifiedSelectedBytes、进度和下载速度独立计算。
- JVM 强制退出后可以安全恢复且不会报告假完成。
- 跨用户查看、控制、下载文件与票据全部被拒绝。
- 新旧引擎共存，SQLite/MySQL 双库通过迁移与回归测试。
