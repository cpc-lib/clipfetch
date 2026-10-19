# ClipFetch 自研 BitTorrent 引擎研发规划书

> 版本：1.0 · 日期：2026-10-14 · 状态：设计基线（尚未实施）
> 项目根目录：D:\release\clipfetch
> 文档目录：torrent/
> 核心目标：**同一 InfoHash 可由多个用户分别创建下载任务，每个任务拥有独立文件选择、进度、启停状态、带宽统计、断点和存储目录。**

## 1. 现状与立项目的

现有 ClipFetch 为单体应用：Java 21 / Spring Boot 3.5.4 / MyBatis-Plus 3.5.7；前端 Vue 3 + Vite + Tailwind；数据库默认 SQLite，可切换 MySQL；后端包为 cc.ivera。现有磁力功能在 cc.ivera.magnet 内，通过 QBittorrentClient 调用 qBittorrent，使用 RemoteTorrentFileGateway / DownloadTicketStore 支持浏览器取流。

已确认的限制：
- backend/src/main/resources/db/migration/{mysql,sqlite}/V10__magnet_download_task.sql 已建立任务归属表；MySQL 的 magnet_download_task 对 info_hash 有全局 UNIQUE。
- MagnetController#createDownload 通过 taskByHash 控制：同一 InfoHash 由一个 qBittorrent 任务持有；不同用户无法分别创建独立下载实例。
- 当前下载任务进度来源于 qBittorrent；同种子唯一底层任务意味着暂停、文件优先级、删除和下载完成状态不可为多个用户独立管理。
- 已有 V9～V12 历史迁移；严禁改写历史迁移文件，后续脚本必须从实际最新版本之后编号。
- 仓库 AGENTS.md 要求单体、SQL 写 XML、双库迁移、JWT+user_id 隔离、UTF-8 无 BOM、无 Redis/MQ/cron/新微服务。

**本项目只建设合法授权资源的 BitTorrent 获取能力，不建设资源搜索/内容索引或规避访问控制功能。**

## 2. 明确的成功标准

| 编号 | 必须实现的结果 | 通过条件 |
|---|---|---|
| G01 | 同 Hash 多用户独立 | 用户 A、B 输入相同 Magnet，得到不同 taskUuid、目录、进度快照 |
| G02 | 任务行为隔离 | A 暂停、重启、删除任务不改变 B 的运行状态或已落盘数据 |
| G03 | 文件选择隔离 | A 仅选文件 0，B 仅选文件 2；下载清单与进度各自计算 |
| G04 | 断点续传 | JVM 异常退出后重启恢复 A/B 各自已验证的下载结果 |
| G05 | 正确进度 | selectedTotalBytes 作为分母；只计所选文件中已校验且持久化的有效字节 |
| G06 | 访问隔离 | 非本人不能查询、控制、获取 ticket 或下载他人文件 |
| G07 | 协议自研 | 不使用 qBittorrent、aria2、迅雷、libtorrent 或其他 BT 内核作为新引擎 |
| G08 | 协议互操作 | 与合规的公开测试种子、受控 Peer/Tracker/DHT 测试环境互通 |
| G09 | 旧功能安全 | 旧 qBittorrent 任务可以继续运行；新引擎灰度接管新建任务 |
| G10 | 兼容项目 | 沿用 Spring Boot 单体、现有前端、JWT、MyBatis XML、SQLite/MySQL |

非目标：自建公网 Tracker 运营平台；磁力搜索引擎；首期 BT v2 全功能；跨节点分布式调度；跨用户物理数据去重；浏览器直接实现原生 BT。

## 3. 产品边界与隔离语义

### 3.1 四个独立标识

- userId：用户身份，来源于服务端认证上下文，**绝不信任前端传来的 userId**。
- taskUuid：用户的**下载任务**，是增删改查、WebSocket/轮询、文件票据、恢复状态的唯一业务 ID。
- infoHash：BT 内容资源 ID（首期 BT v1，20 字节 SHA-1，规范化成 40 位 hex）；**不能用它作为用户任务 ID**。
- metadataId：服务端已验证种子元数据缓存 ID，可按内容哈希复用，但不得携带任何用户进度。

### 3.2 强隔离和允许的共享

| 对象 | 策略 | 原因 |
|---|---|---|
| 用户下载任务、状态机、优先级 | 每 taskUuid 独立 | A 的 Pause/Resume/Delete 不影响 B |
| 下载连接与 PieceScheduler | 首期每任务独立 | 避免跨用户请求混排与取消传播 |
| Piece 位图、Block 请求、速度统计 | 每任务独立 | 进度与计费/资源统计准确 |
| 输出目录与临时文件 | 每 taskUuid 独立 | 防止串写、覆盖和误删 |
| 元数据解析结果 | 仅在哈希校验后共享不可变结果 | 减少重复解析；对外仍按用户鉴权 |
| DHT 路由表、Bootstrap 状态 | 引擎实例共享 | 节约 UDP 资源；不属于用户下载状态 |
| Trackers/Peer 候选发现 | 可共享经过清洗的公共结果 | 严禁共享任务进度或访问令牌 |
| 实际 Piece 内容 | 首期**不跨用户共享** | 保证下载生命周期严格独立 |

隔离层次为**业务状态隔离、存储隔离、控制面隔离、进度隔离**；并非公网 IP/物理网络身份隔离。由于多个任务共用主机带宽与出口 IP，不能承诺绝对网络资源隔离；引入 per-user 资源配额实现公平性。

### 3.3 文件获取模式

系统服务端下载，用户通过现有短时票据和文件网关取文件。用户无法指定任意服务器目录；使用服务端配置的根目录：
~~~text
<storageRoot>/users/<userId>/tasks/<taskUuid>/files/
<storageRoot>/users/<userId>/tasks/<taskUuid>/.state/
<storageRoot>/users/<userId>/tasks/<taskUuid>/.piece-cache/
~~~
禁止将用户输入作为路径片段；创建目录前对真实路径做归一化、父目录检查和符号链接防护。

## 4. 研发交付阶段

| 阶段 | 产物 | 验收闸门 | 相对工作量（人日，粗估） |
|---|---|---|---|
| P0 方案与测试基建 | 协议契约、测试数据、组件骨架、特性开关 | 设计评审通过，旧接口不破坏 | 5–8 |
| P1 协议解析 | Magnet v1、Bencode、.torrent 校验、Peer Wire 编解码 | 官方/自制 golden vectors 通过 | 10–15 |
| P2 Peer 发现与 Metadata | HTTP/UDP Tracker、DHT BEP5、BEP10/9 | 可独立解析受控 Magnet 并返回文件树 | 20–35 |
| P3 自研下载闭环 | TCP Peers、请求流水线、Piece 校验、NIO 落盘 | 单用户下载校验成功；kill -9 恢复 | 25–40 |
| P4 多用户隔离 | TaskActor、按任务存储、任务表、API、文件勾选、配额 | 两用户同 Hash 并发互不影响 | 15–25 |
| P5 产品接入 | Vue 页面、ticket/Range、任务列表、灰度/迁移 | 新旧模式共存，端到端测试通过 | 10–18 |
| P6 稳定性与兼容优化 | PEX、IPv6、连接评分、限流、故障注入、压测 | 限额下稳定运行，无跨用户串扰 | 20–35 |
| P7 增强（另立版本） | uTP、BEP52 v2/hybrid、更复杂公平调度 | 单独验收，不阻塞 v1 | 待评估 |

总量估计 105–176 人日，**仅为工程规划量级，不构成交付保证**；依赖协议测试覆盖、单/多人投入、平台与资源可用性。首个可供试用的闭环到 P4，生产发布需 P5/P6 通过。禁止将仅能解析 Magnet 的组件称为完整下载内核。

## 5. 阶段任务分解

### P0：工程与测试先行
1. 在同一 backend Maven 应用内建立 cc.ivera.magnet.engine 包，不创建微服务或新增部署单元。
2. 定义 TorrentEngine 接口与 QBT/NATIVE feature flag；默认 QBT，NATIVE 首期仅管理员/测试账户灰度。
3. 建立无公网依赖的受控测试 Tracker、Peer fixture、生成的合法 .torrent 与 Magnet；准备多文件跨 Piece 边界样例。
4. 确认 V12 为当前已用版本，再创建 MySQL 与 SQLite 等价的新迁移；不得改写 V10 唯一索引以破坏旧任务。

### P1：协议字节级正确性
- BEP3 BencodeDecoder/Encoder；**获取 InfoHash 必须对原始 info bencode 字节求 SHA-1**，不要对可能非规范化的解码对象重编码后求哈希。
- 防整数溢出、递归过深、嵌套字典过多、超大字段、非法路径与畸形消息。
- Magnet btih 40 位 hex / 32 位 Base32；去重与规范化，多个 tr；btmh 先识别但首期明确 UNSUPPORTED_V2。
- Wire handshake、keep-alive、choke/unchoke、interested、bitfield、have、request、piece、cancel 编解码。

### P2：发现与元数据
- HTTP Tracker + UDP Tracker、DHT KRPC ping/find_node/get_peers、迭代查找、Peer 去重。
- BEP10 extension handshake + BEP9 ut_metadata 分片获取；大小/索引/超时/重试/完整哈希验证。
- 只将验证通过的元数据写共享缓存；解析历史仍按 userId 保存。
- 无 Peer 时明确 FAILED/TIMEOUT/RETRYABLE，不伪造文件列表或承诺一定能够下载。

### P3：下载器
- 每 taskUuid 的 PeerSession 集合、availability 计数、Rarest First、Endgame 重复请求/Cancel。
- Block 请求小窗口起步（典型 16 KiB，可配置）；每 Piece SHA-1 验证后才提交进度。
- 安全 FileChannel 映射：global torrent offset → 文件区间；只落盘用户所选文件对应区间。
- 跨文件边界 Piece 的未选择字节保存在该任务私有边界缓存，供重启后全 Piece 重新校验。
- 只有完成哈希校验、写入成功且 checkpoint 持久化后，任务进度快照才更新为已验证字节。
- 持久化 Piece 状态/必要边界字节、运行版本与文件长度；崩溃恢复时逐项检查可信性。

### P4：用户隔离
- 每个业务下载任务分配全新的 UUID，即使相同用户同 Hash 的不同任务也独立。
- 数据查询使用 (task_uuid, user_id, deleted=0)；服务层禁止只凭 infoHash 操作业务任务。
- 单任务事件队列串行化 START/PAUSE/DELETE/PIECE_COMMIT；跨任务并行。
- per-user 活动任务/连接数/带宽/磁盘配额；资源耗尽返回明确错误而不是影响其他任务。
- 验证“用户 A 停止后用户 B 的 session、状态、文件和速率不被修改”。

### P5：接入与迁移
- 沿用 /api/magnet/parse、/history 等兼容入口；新任务接口优先使用 taskUuid 路径。
- 旧 /downloads/{hash} 控制接口仅操作 legacy QBT 任务；native 不可通过 hash 控制，防止歧义。
- 前端沿用 Vue 3/Tailwind、已有 JWT 及 request.js，新增引擎类型与独立任务进度。
- 使用现有 ticket 流程绑定 userId、taskUuid、fileIndex；浏览器下载支持 Range；不直接暴露磁盘路径。
- 迁移采用双引擎共存，不执行危险的“原地转换未完成 qBittorrent 任务”；可在明确用户授权后重新创建 native 任务。

### P6：稳定性
- 错误注入：Peer 半包、慢速、错误哈希、重放、超时、磁盘满、权限丢失、网络中断、进程意外退出。
- 并发压测：100 个逻辑任务的存储/认证隔离，实际连接数按受控可用资源调整；监测 FD、堆外内存、线程。
- 受控 Tracker/Peer 互操作测试后，再使用合法公开测试资源做真实公网联调。

## 6. 测试与验收矩阵

| 用例 | 输入 | 预期 |
|---|---|---|
| T01 | A、B 同一 InfoHash，不同文件勾选 | 产生两个 taskUuid；目录互不交叉 |
| T02 | A、B 同一 Hash，均选完整内容 | 物理写入两个独立任务目录；进度不同步 |
| T03 | A Pause，B 正在下载 | A 速度归零/PAUSED；B 保持 DOWNLOADING |
| T04 | A 删除文件并清理，B 在用同 Hash | 只删除 A 的任务工作区，B 的工作区完好 |
| T05 | A 访问 B 的 taskUuid、ticket、fileIndex | 返回 404/403，不披露 B 的元数据 |
| T06 | A 和 B 同时请求相同 Magnet metadata | 可复用已验证缓存，历史记录仍独立 |
| T07 | 部分文件跨 Piece 边界 | 百分比按选中逻辑字节计算，边界字节不进入未选文件 |
| T08 | 下载中 kill JVM 后重启 | 分别恢复 A、B 校验状态；不能出现假 100% |
| T09 | 限速、超时、磁盘满 | 仅关联任务降级/失败；其他用户可继续 |
| T10 | 同用户双任务同 Hash，选择不同 | 两份独立任务，状态、目录、进度独立 |
| T11 | 恶意 .torrent 文件名 / 路径穿越 | 文件拒绝写入任务根目录之外 |
| T12 | 旧 QBT 与新 NATIVE 同时存在 | 旧任务管理和取流不回归；未授权不自动迁移 |

发布阻断项：任意跨用户读/删；错误完成率；hash mismatch 却报告完成；状态丢失导致覆盖文件；跨路径删除；无法恢复的静默损坏。

## 7. 灰度与回滚策略

1. 配置 TORRENT_ENGINE_MODE=QBT（默认），TORRENT_NATIVE_ENABLED=false。
2. 第一轮灰度：仅系统管理员测试账户可为新建任务选择 NATIVE；历史 QBT 任务仍由 QBT 驱动。
3. 第二轮：受控用户组逐步增加，跟踪 parse success、checksum error、resume success、p95 API latency、任务级公平性。
4. 回滚：把**新任务路由**切回 QBT；已经运行的 native 任务优先保持可读、暂停和导出，不能简单删除其状态或文件。
5. 旧 qBittorrent 不在新引擎达到功能一致、连续稳定性验证通过前移除。

## 8. 关键风险与处理

| 风险 | 影响 | 处理 |
|---|---|---|
| 无可用 Peer / 网络屏蔽 UDP | 无法解析/下载 | Tracker+DHT 多路径；明确可重试错误 |
| 过量连接引发 FD/内存耗尽 | 整站服务异常 | 全局/用户/任务三层令牌与连接上限 |
| 未选文件跨 Piece 边界 | 进度错误/恢复不可验证 | 任务私有边界缓存与逻辑字节映射 |
| 磁盘满或异常重启 | 数据损坏或错误恢复 | 原子 checkpoint、fsync 策略、重校验 |
| 恶意 Tracker/Peer/元数据 | SSRF、解析异常、路径穿越 | 目标地址过滤、长度/层级限制、隔离目录 |
| 旧接口基于 Hash | 跨用户操作歧义 | NATIVE 控制接口只用 taskUuid |
| Java 单体网络 I/O 争用 | 影响 API 响应 | Selector/I/O 池与业务线程隔离、有界队列 |
| v2/hybrid 与 v1 不同 | 哈希与 Piece 计算出错 | v1 完成后独立实施 BEP52 |
| 共享出口 IP | 网络层不完全隔离 | 文档明确边界，限制连接/带宽 |

## 9. 设计评审与完成定义（DoD）

- 所有新协议代码原创实现；允许 JDK/Spring Boot 的通用基础能力，不引入第三方 BT 内核。
- 每一 BEP 模块有字节级单元测试、超时与上界控制。
- 每一用户任务 API 带 userId 归属校验；不存在无归属的 taskUuid 控制入口。
- 新的 MySQL 与 SQLite 迁移版本同步、SQL 写 mapper XML、UTF-8 无 BOM。
- 兼容性入口与旧业务基于 feature flag 分流，并编写明确回滚步骤。
- P0～P6 全部验收后才可以在生产默认切换 NATIVE。
- 用于合法拥有或授权下载的测试内容，不把下载速度当成仅由软件决定的承诺。

## 10. 配套文档

- [02-自研BitTorrent协议技术实现方案.md](./02-自研BitTorrent协议技术实现方案.md)：协议模块、具体类设计、数据库、接口、计算公式、安全、恢复和运维。
- [README.md](./README.md)：目录索引、架构决策及落地顺序。

## 11. 官方协议依据

- BEP 3 — https://www.bittorrent.org/beps/bep_0003.html
- BEP 5 — https://www.bittorrent.org/beps/bep_0005.html
- BEP 9 — https://www.bittorrent.org/beps/bep_0009.html
- BEP 10 — https://www.bittorrent.org/beps/bep_0010.html
- BEP 11 — https://www.bittorrent.org/beps/bep_0011.html
- BEP 15 — https://www.bittorrent.org/beps/bep_0015.html
- BEP 29 — https://www.bittorrent.org/beps/bep_0029.html
- BEP 52 — https://www.bittorrent.org/beps/bep_0052.html
