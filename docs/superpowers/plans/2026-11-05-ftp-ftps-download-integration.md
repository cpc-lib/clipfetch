# ClipFetch FTP / FTPS 下载集成实施计划

> 规划日期：2026-11-05（America/Los_Angeles）
> 状态：**Plan-only，尚未实施**
> 仓库：D:\release\clipfetch
> 依据：AGENTS.md、HANDOFF.md、ISSUE.md，以及 docs/superpowers/plans/ 与 specs/ 全部现有 Markdown。
> 实施纪律：TDD、分阶段验收、真实记录 Exit Code；本轮仅写 Plan 和 HANDOFF，不修改业务代码、不启动服务、不执行构建、不主动 commit。

## 1. 产品目标和首期边界

目标：为现有 ClipFetch 新增 **由服务器执行的 FTP/FTPS 文件下载任务**，用户登录后提交 FTP 服务器、远端文件路径及可选认证资料，后端下载到服务器受控目录，支持任务状态查询、进度、暂停、恢复、取消、逻辑删除及完成文件交付。

必须满足：

1. FTP 普通连接和 **显式 FTPS（FTP over TLS / AUTH TLS）**，各自明确展示安全属性；
2. 被动模式 EPSV/PASV、BINARY 传输、单文件 RETR、可配置并发与速度/大小约束；
3. 检查远端 SIZE/MDTM/MLST 等能力后才允许安全断点续传；
4. 数据库持久化用户任务与检查点，Spring Boot 重启可恢复合规任务；
5. 同一 FTP 路径由不同用户创建时，首期 **严格隔离、不跨用户共享文件和凭据**；
6. 每个任务有独立临时文件、完成文件、安全导出和资源清理策略；
7. Windows 与 Linux 可运行；不要求 FTP 服务本身 Docker 化。

### 1.1 首期不做

- **SFTP（SSH File Transfer Protocol）不是 FTP/FTPS**：不在本阶段实现，后续若需要单独做 SFTP 适配器与安全评审。
- 不做 FTP 上传、删除远端文件、移动/改名、递归目录爬取、FTP FXP、主动模式、PORT/EPRT。
- 不开放匿名访问任意私有网段/云元数据地址，不开发代理跳板、跳过证书验证、跨用户共享凭据。
- 不开发多宿主 Worker 集群、多节点租约接管，不引入 MQ、Quartz、xxl-job、通用 Redis 缓存、微服务或前端重型框架。
- 首期一条任务对应一个明确的远端普通文件；目录浏览和批量下载需要后续单独审核。
- 不顺手改造 magnet v0/v1/v2 或 ed2k 的任务引擎和数据结构。

## 2. 当前仓库事实（按当次实际读取）

- 后端 Java 21、Spring Boot 3.5.4，多模块 Maven 聚合但业务仍为单体；后端 8082。
- 前端 Vue 3/Vite 6/Tailwind、vue-router；现有路由 /magnet/:engine?；新功能适合独立 /ftp 路由。
- SQL 全在 backend/src/main/resources/mapper/*.xml；SQLite 与 MySQL 使用分别维护的数据库迁移脚本。
- **当前两库均已存在 V25__ed2k_amule.sql**，ed2k 后端包、Mapper 和基础设施也已在工作树存在；AGENTS 的“最高 V24”说明已落后于实测目录。FTP 新迁移从 **V26 候选**开始，实施前必须再查重。
- 当前工作树 ed2k 相关文件、配置、计划、规格及 HANDOFF 有未提交改动；本计划禁止覆盖、格式化或提交这些变更。
- Redis 只服务 Native v2 限频，新增 FTP 不能把 Redis 当任务队列或通用锁。
- 依 AGENTS.md，新业务必须建立 cc.ivera.ftp 的 interfaces/application/domain/infrastructure 四层；统一 JWT、user_id、ApiResponse、BusinessException、健康 200 降级、单页上限 30、逻辑删除 deleted=id。
- FTP 客户端与 HTTP 客户端不同；AGENTS 禁止引入“新 HTTP 客户端”，但允许通过审查新增专用 FTP 协议依赖。

## 3. 技术选型与关键取舍

### 3.1 FTP 协议库

优先 **Apache Commons Net 3.13.0**（2026-03-15 发布；编码实施时再次确认依赖版本与兼容性）：

- FTPClient：FTP 控制连接、被动数据连接、RETR、SIZE、MDTM、REST 偏移与最终响应；
- FTPSClient：显式 AUTH TLS、控制与数据通道 TLS、证书与主机名验证；
- 禁止默认禁用 TLS endpoint checking；显式 FTPS 必须检验服务器证书和主机名；
- 每任务创建独立 FTPClient/FTPSClient 实例，禁止跨线程复用会话；
- 使用 setFileType(BINARY_FILE_TYPE) 并 enterLocalPassiveMode()，只允许本地被动模式；
- retrieveFileStream 结束后必须 completePendingCommand 成功，才允许确认传输完成；
- 断点续传用 setRestartOffset（实际服务器支持 REST 且远端身份稳定才允许）；重连后重新验证元数据；
- 保持 remote data connection verification 开启，不能接受任意 PASV 地址、重定向或第三方端点；
- FTPS 使用 PBSZ 0 与 PROT P（数据通道加密），拒绝隐式降级为明文 PROT C；
- FTPS 隐式 990/TCP 可作为明确的后续配置/独立验收项，不与 ftps:// 语义混用。

官方参考：
- https://commons.apache.org/proper/commons-net/changes.html
- https://commons.apache.org/proper/commons-net/apidocs/org/apache/commons/net/ftp/FTPClient.html
- https://commons.apache.org/proper/commons-net/apidocs/org/apache/commons/net/ftp/FTPSClient.html

### 3.2 认证输入与凭据保存

用户提交结构化字段 **scheme/host/port/remotePath/username/password**，不接受 URL userinfo、含密码的 ftp://user:pass@host 链接。可选粘贴 ftp:// 或 ftps:// URL 仅提取无凭据 URI 并做严格验证。

- FTP 明文泄露凭据风险大：默认 **FTP_DISABLED / 只允许 FTPS** 作为安全配置，管理员显式启用明文 FTP 并清晰告知用户后才可创建。
- 匿名 FTP 视为显式选择，使用配置定义的合法匿名身份，不默认猜密码。
- 若希望后台恢复必须持久保存凭据：建立服务端 AES-256-GCM 加密封装；主密钥单独通过环境/受保护密钥文件注入，包含 key_id、独立随机 nonce 和版本；不得把密钥与密文存同表。
- 凭据不进入前端任务列表、URL、日志、异常、任务事件、审计明文、API 响应；客户端提交只走应用 TLS。
- 初期不复用 ed2k 任务或存储其秘钥；支持密钥缺失时任务进入 BLOCKED_CREDENTIALS，不伪造自动恢复。
- key rotation、凭据删除/撤销、匿名和具名身份的恢复边界都要验收。

### 3.3 禁止跨用户资源去重

即使 host/path 完全一致，不同用户 FTP 权限、文件可见性及内容都可能不同；首期 **一用户一任务一隔离存储目录**，避免不同凭据返回不同字节时发生数据串用。是否支持同用户重复 URI 幂等需要明确采用请求 Idempotency-Key，不能按 URL 全局共享。

## 4. 架构和数据流（统一 Mermaid）

~~~mermaid
flowchart LR
    U[Vue /ftp] -->|JWT| API[FTP Controller 8082]
    API --> S[FtpTaskService]
    S <--> DB[(SQLite / MySQL)]
    S --> Q[FtpTransferCoordinator]
    Q --> P[FTP/FTPS Client]
    P -->|Passive RETR + TLS 可选| R[FTP Server]
    Q --> FS[(data/ftp 受控目录)]
    API -->|授权下载| F[FtpFileService]
    F --> FS
~~~

层级建议：

~~~text
backend/src/main/java/cc/ivera/ftp/
  interfaces/      FtpController / FtpHealthController / FtpFileController / DTO
  application/     FtpTaskService / FtpTransferCoordinator / FtpRecoveryService / FtpFileService
  domain/          FtpDownloadTask / FtpCredential / FtpTransferCheckpoint / Mapper / StatePolicy
  infrastructure/  CommonsNetFtpClient / FtpProperties / CredentialCipher / FtpPathPolicy
backend/src/main/resources/mapper/Ftp*.xml
backend/src/main/resources/db/migration/sqlite/V26__ftp_download.sql
backend/src/main/resources/db/migration/mysql/V26__ftp_download.sql
frontend/src/api/ftp.js
frontend/src/components/FtpDownloader.vue
frontend/src/router/index.js
frontend/src/components/AppHeader.vue
data/ftp/    # 保证被 .gitignore 拦截；与 data/magnet-v2、ed2k 分开
~~~

内存中的协调器使用有界 ExecutorService（可配置）、状态队列、并发 permit 与取消 token；**不是新的常驻调度服务或 MQ**。应用启动时按 DB 状态和上次 checkpoint **有界恢复**；其余状态通过应用事件/用户请求推进，不另加 cron/Quartz。

## 5. 数据模型与并发不变量

### 5.1 建议新增三张表（双库 V26）

| 表 | 核心字段 | 安全约束 |
|---|---|---|
| ftp_download_task | id, task_uuid, user_id, scheme, host, port, remote_path, display_name, status, total_bytes, downloaded_bytes, revision, retry_count, last_error_code, created_at, updated_at, completed_at, deleted | user_id 必选；唯一 task_uuid+deleted；逻辑删除 deleted=id |
| ftp_task_credential | id, task_id, user_id, encrypted_username, encrypted_password, key_id, nonce, cipher_version, created_at, updated_at, deleted | 独立密文；查询带 user_id/task_id；不进入 JSON |
| ftp_transfer_checkpoint | id, task_id, user_id, local_key, remote_size, remote_mtime, remote_unique_id, committed_bytes, checksum_sha256, revision, updated_at, deleted | 与 .part 实际长度交叉校验，不能盲信 DB 偏移 |

补充：控制台和文件保存不允许直接使用 host+remotePath 拼成宿主目录；独立本地路径由 user_id + task_uuid 生成，存储 root 配置化。若技术实现确认 checkpoint 足以放在任务表，可在 SPEC 阶段评审合并，但必须保留可重启审计与 CAS 不变量。

索引要求：任务 (user_id,status,deleted)、(task_uuid,deleted) 唯一、(user_id,id,deleted)；credential/checkpoint 对 task_id 唯一；所有用户数据访问与写入必须 user_id + deleted=0。

### 5.2 状态机

~~~mermaid
stateDiagram-v2
    [*] --> QUEUED: 创建
    QUEUED --> CONNECTING: 获得并发许可
    CONNECTING --> DOWNLOADING: 登录与远端检查通过
    DOWNLOADING --> VERIFYING: 数据流结束
    VERIFYING --> COMPLETED: 回复码 + 大小 + 本地持久性校验
    QUEUED --> PAUSED: 用户暂停
    CONNECTING --> PAUSING: 暂停意图
    DOWNLOADING --> PAUSING: 暂停意图
    PAUSING --> PAUSED: 流关闭与检查点落盘
    PAUSED --> QUEUED: 用户恢复
    CONNECTING --> RETRY_WAIT: 临时故障
    DOWNLOADING --> RETRY_WAIT: 临时故障
    RETRY_WAIT --> QUEUED: 有界重试
    CONNECTING --> FAILED: 永久失败
    DOWNLOADING --> FAILED: 永久失败
    QUEUED --> DELETED: 逻辑删除
    PAUSED --> DELETED: 逻辑删除
    COMPLETED --> DELETED: 逻辑删除
~~~

- 所有控制操作通过 task_uuid + user_id + expected_revision CAS；旧回调/已取消传输不得把 PAUSED、DELETED 写回 DOWNLOADING。
- 删除：先标记删除意图，取消正在进行的本任务连接，逻辑删除并在安全时机执行临时文件清理；删除不能影响其他用户存储。
- 任务列表/健康即使 FTP 服务器离线也从 DB 安全返回，含 stale/lastCheckedAt；**不能用登录失败 401 影响用户 ClipFetch JWT 状态**。
- 任务完成需要传输端正确返回 + 校验本地文件长度/预期远端长度 + fsync/原子 rename；服务器不支持 SIZE 时应明确“仅在终止确认与快照安全条件下完成”，不能凭 TCP 断开判完成。
- 文件下载前再次验证 task 归属、状态和 realpath，在登录态下走后端流式传输，严禁裸白名单。

### 5.3 断点续传与远端变化

- 初次连接询问 FEAT、SIZE、MDTM / MLST (modify/unique 等)，记录服务器有实际提供的字段；unsupported 不等于 0。
- 本地检查点偏移从 **已 fsync 到磁盘的 part 字节数**取得，与 DB committed_bytes 相互比对；数据库绝对不能大于文件实际可靠长度。
- REST resume 仅在服务器确实支持、SIZE 与上次一致，且有可信可比对的 mtime/unique ID/预期摘要等身份标志时启用；只有相同 SIZE 没有额外身份信息时不保证安全续传。
- 若远端改变或无足够证据，**拒绝静默续传**：置 NEEDS_RESTART / FAILED_REMOTE_CHANGED，要求明确重新下载或重新建立任务；不把错误字节续接到旧 part。
- FTPClient 的 retrieveFileStream 消费完毕后调用 completePendingCommand 并检查 2xx 完成响应；服务器返回错误、数据长度不足、控制连接丢失时不得 COMPLETED。
- 重试不能再次从偏移 0 追加到现有 .part；需要从头时新建/截断受控临时文件并更新版本，杜绝文件污染。
- 可配置速度采样、最大文件大小、总任务数、重试退避；默认单连接顺序下载，首期不采用多个 REST 分块并行，避免服务器行为差异。

## 6. SSRF 与 FTP 数据连接专项安全（P0）

服务器代表登录用户连接外部主机，必须视为 SSRF 风险。不可仅验证用户输入的 URL 字符串。

1. host/port/remote_path 分离校验；禁止 URI userinfo、反斜杠控制序列、CRLF、控制字符、非法端口、多协议 fallback；禁止用户控制服务器可执行的原始 FTP 命令。
2. DNS 解析时检查所有 A/AAAA 记录，默认仅允许公共单播地址；禁止 loopback、link-local、private、ULA、multicast、unspecified、保留地址、云 metadata、IPv4-mapped IPv6 等。
3. 内部 FTP 服务是合理需求，但 **只有管理员配置的准确主机/IP 与端口 allowlist** 可例外访问；普通用户不能自行关闭网络限制。
4. 控制连接必须验证实际已连接 IP 仍是审查允许的地址；每次重连重新解析与校验；避免 DNS rebinding/TOCTOU。
5. 仅 PASV/EPSV；**拒绝 Active PORT/EPRT、第三方数据转移 FXP**。EPSV 优先，PASV 数据地址不能导致任意第三方连接；维持 remote host verification，检查实际数据 socket 地址与策略，必要时在适配器层加强校验。
6. PASV 可能报告私网地址的 NAT 兼容处理必须受限：只能使用已核准的控制连接远端地址及服务器返回的有效端口，不能无条件信任不受限的地址；禁止自动跟随任何重定向。
7. Explicit FTPS 强制证书链及主机名校验、控制/数据通道 TLS、PBSZ 0、PROT P；认证或 TLS 校验失败不得降级为 FTP。
8. 添加负面用例：DNS 从公网跳私网、EPSV/PASV 攻击地址、重定向、IPv6 ULA、内网网段、错误证书、证书过期、FTPS 数据连接降级、恶意远端多行回复。

## 7. 对外 API 合同（拟定）

所有路径默认需要 ClipFetch JWT，Controller 使用 ApiResponse.ok 与 GlobalExceptionHandler；分页 size 上限 30。健康端点始终返回 HTTP 200 + configured/connected/version/message，其中 connected 表示 **本地 FTP 下载模块已就绪**，不是“全球 FTP 服务器均在线”。

| 方法 | 路径 | 功能 |
|---|---|---|
| GET | /api/ftp/engine/health | 本地模块健康、功能开关、可用状态 |
| POST | /api/ftp/connections/test | 当前用户带授权的目标连接探测；限频、禁止暴露凭据 |
| POST | /api/ftp/tasks | 创建单文件任务；显式凭据字段、可选 Idempotency-Key |
| GET | /api/ftp/tasks?page=1&size=10 | 当前用户分页列表，允许断线降级 |
| GET | /api/ftp/tasks/{taskUuid} | 当前用户详情、进度、可信远端元数据 |
| POST | /api/ftp/tasks/{taskUuid}/pause | CAS 暂停 |
| POST | /api/ftp/tasks/{taskUuid}/resume | CAS 恢复 |
| POST | /api/ftp/tasks/{taskUuid}/restart | 远端已变化时从零重下；二次确认 |
| DELETE | /api/ftp/tasks/{taskUuid} | 逻辑删除当前用户任务 |
| GET | /api/ftp/tasks/{taskUuid}/files/0/content | 登录态完成文件 GET/Range，索引固定 0 |

- 请求字段：protocol=FTP/FTPS_EXPLICIT、host、port、remotePath、username、password（或匿名模式）、可选 displayName；禁止原始带用户名密码的 URI、不可控服务器地址。
- 请求连接探测要有严格超时、并发限制及用户维度的应用内限频，不能成为扫描服务工具；只对准许的 FTP 主机执行所需最少命令。
- 401 仅 ClipFetch JWT 失效；FTP 530 认证失败映射受控业务错误（如 422），不是 401；403 策略拒绝，409 revision，416 Range，429 用户配额，503 服务功能关闭或数据库/执行器不可用。
- 远端服务器错误日志可带经过脱敏的 FTP reply code 与摘要；原始 reply 中可能含凭据/路径，不直接返回前端。
- 下载响应可复用现有 Native v1/v2 登录态 + axios Blob 模式（浏览器大文件内存边界需说明）；未来低内存票据下载另立安全设计。

## 8. 前端交互设计

- 新增独立 /ftp 页面，不混入 magnet 引擎选择器；组件路由懒加载。
- 桌面/移动 AppHeader 均增加 FTP 下载入口，移动端汉堡菜单自动收起。
- 表单：协议、域名/端口、文件远端路径、匿名/账号密码；对明文 FTP 显示明确风险提醒。
- 任务表：名称、大小、总进度、速度、状态、上次刷新、FTPS 安全状态；支持开始/暂停/恢复/删除/重新下载/已完成文件领取。
- 按 AGENTS.md：请求走 api/request.js，中文错误使用 errMsg；删除和从零重下二次确认；轮询先确认启用与就绪，报错立即熔断，组件卸载清 interval，避免同时重叠请求。
- 任何凭据输入仅用于当前创建/测试请求；任务列表不回显 password、明文 username（如需用户名展示，只能经受控部分脱敏的字段）。

## 9. 可执行实施阶段（仅规划，不在本轮执行）

### Task 0 — 基线与风险门槛

**调查/预计文件**：backend/pom.xml、现有配置、FTP 集成测试与本 Plan；检查已跟踪工作树。

- [ ] 锁定 Commons Net 经安全审查版本，确认 FTPClient/FTPSClient 的连接、TLS 验证、PASV/EPSV、REST、completePendingCommand 真正行为。
- [ ] 建立两个只用于测试的 FTP/FTPS 服务器 fixture（可选容器），不连接不受控第三方主机。
- [ ] 检查现有 Flyway 已到 V25、确认 V26 两库仍未占用；记录 git status，不覆盖未提交的 ed2k 变更。
- [ ] 先写可重复的“FTP server 假地址不能访问内网/无法绕过策略”的安全测试。
- [ ] 门槛：若无法对控制和被动数据连接都可靠执行地址策略，**不得上线任意用户可输入主机的 FTP 下载功能**。

### Task 1 — 领域模型与状态机

**预计文件**：ftp/domain/FtpDownloadTask、FtpTaskState、FtpRemoteIdentity、FtpRequestValidator 和相关 JUnit 5。

- [ ] RED：FTP/FTPS scheme、host/IP/port/remotePath、控制字符、URI userinfo、危险文件名、目录路径、合法单文件输入。
- [ ] GREEN：领域身份、错误分类、状态机与 revision 转移规则。
- [ ] 验证：纯单测不依赖 FTP 服务；无远端操作副作用。

### Task 2 — SQLite/MySQL V26、Mapper XML、凭据封装

- [ ] 两库各新增 **V26__ftp_download.sql（仅当未占用）**；新增 ftp_download_task、ftp_task_credential、ftp_transfer_checkpoint。
- [ ] RED：user_id 串户、CAS、逻辑删除写 id、唯一索引、删除后重建、密钥错误/密文损坏不能解密。
- [ ] GREEN：Mapper 接口 domain，SQL XML；AES-GCM + 唯一 nonce/key_id，加密后的凭据字段 @JsonIgnore。
- [ ] 验收：SQLite/MySQL 两库迁移及权限集成测试，原 V25/BT 数据不变。

### Task 3 — FTP/FTPS 适配器（先协议合同与安全）

- [ ] 增加经审批的 Commons Net 依赖及 FTP/FTPS 独立适配；配置 FTPS 的 hostname/证书与数据通道 TLS。
- [ ] RED：控制连接/数据连接 SSRF、PASV 伪造、IPv6、AUTH TLS 失败不得回退、FTP 530、425、426、450、550、421、断连与超时。
- [ ] GREEN：被动模式、BINARY、FEAT/SIZE/MDTM、RETR、REST、completePendingCommand 和会话退出。
- [ ] 验收：真实本地 fixture 的 FTP 和 FTPS 能传完整文件；恶意数据端点无法触达禁用地址。

### Task 4 — 可恢复的顺序下载与流量配额

- [ ] 下载根下每用户/每任务独立 part，专用有界 ExecutorService 与取消 token。
- [ ] RED：暂停时 checkpoint 文件和 DB 一致、恢复 offset、服务器不支持 REST、同 SIZE 不同内容、远端文件变化、零字节文件、磁盘耗尽、重试不会误追加。
- [ ] GREEN：fsync + 原子 rename、保留已完成文件、缺失 metadata 强制从头确认；速度/大小/并发限制。
- [ ] 验收：FTP fixture 强制断流后恢复，最终文件与原受控测试数据 SHA-256 一致。

### Task 5 — 任务 Controller 与权限

- [ ] 后端控制接口：health / connection test / create / list / detail / pause / resume / restart / delete。
- [ ] RED：JWT 未登录、user_id 越权、不同用户同 URL 完全隔离、旧 revision、限频/过载、FTP 认证失败不冒充 401。
- [ ] GREEN：ApiResponse、BusinessException、jakarta.validation、分页 ≤30、健康 200、离线列表可读。
- [ ] 验收：MockMvc / IT 测试不泄露主机凭据。

### Task 6 — 安全完成文件导出

- [ ] 完成文件仅存本任务 UUID 的受控目录；文件真实路径必须在存储根内。
- [ ] RED：跨用户 UUID、未完成文件、symlink / junction、目录穿越、Range 206/416、读取时文件被替换。
- [ ] GREEN：GET/Range 后端低内存流式读取；前端 axios Blob 由 AGENTS.md 约束。
- [ ] 验收：授权单文件字节及 Range 正确，不可访问其他用户数据。

### Task 7 — Vue FTP 工作区

- [ ] 新增 frontend/src/api/ftp.js、FtpDownloader.vue；修改 router/index.js、AppHeader.vue 桌面/移动导航。
- [ ] RED/GREEN：表单、鉴权、连接状态、暂停/恢复/删除、进度轮询熔断、组件销毁、移动窄屏、错误文案。
- [ ] 验收：前端构建通过，未影响 /magnet 与视频/字幕页面。

### Task 8 — 故障恢复与双平台回归

- [ ] Windows/Linux 路径和文件锁行为；服务重启中断后的 part/DB 校验与重连。
- [ ] 故障注入：FTP 控制/数据通道突然关闭、DNS 变化、服务器不支持 SIZE/MDTM/REST、磁盘只读、凭据吊销、TLS 证书变化。
- [ ] 压力测试：并发/队列/限速、恶意请求限频、上下文退出释放 socket/线程、DB 与文件回收一致性。
- [ ] 验收：无丢任务、无跨用户字节泄漏；FTP 禁止绕到内部网络。

### Task 9 — 综合验收与交接

- [ ] 后端按仓库规范执行 mvn clean compile -DskipTests、mvn test；前端 npm run build。
- [ ] 双库 Flyway 集成、FTP/FTPS 受控服务器端到端、暂停恢复、鉴权/文件流 E2E、现有 BT/ed2k 任务 API 冒烟。
- [ ] git diff --check、无 BOM UTF-8 检查、配置与密钥忽略检查、无意外修改与敏感日志检查。
- [ ] Controller/配置变更后重启唯一 8082 实例；记录真实测试 Exit Code，不虚报生产完成。
- [ ] 实施阶段更新 README 相关内容、HANDOFF.md 与实际 ISSUE.md；不主动创建 commit。

## 10. 关键测试清单与风险

| 场景 | 通过条件 |
|---|---|
| 同远端文件由 A、B 两用户下载 | 2 个独立 task、独立 part、各自凭据、操作互不影响 |
| 公网 host DNS 重绑定内部地址 | 创建或连接阶段拒绝，不建立受限连接 |
| 恶意 PASV 指向其他 IP | 不连接任意第三方/禁止的网络地址 |
| FTP 明文连接 | 默认禁用，管理员显式启用且 UI 告警 |
| FTPS 错误/过期证书 | 硬失败，绝不回退 FTP |
| 控制通道传输完成码失败 | 状态不能 COMPLETED |
| 远端文件被更新但 SIZE 不变 | 没有可信身份保证则禁止接旧 checkpoint |
| 非法/不支持 REST | 正确提示或明确经确认从头重下，不污染 part |
| 连接中断、服务重启 | DB 与 .part 可靠校验，未完成任务能安全恢复 |
| 未完成/越权/路径逃逸文件导出 | 不泄露任何文件字节 |
| 引擎禁用/目标 FTP 下线 | health 200 结构化，本用户列表安全返回 DB |
| 现有 magnet/ed2k | 数据库、路由、接口均无回归 |

## 11. 交付与不确定性

- 本计划**只定义后续实现步骤**；不表示 FTP/FTPS 功能已存在、凭据加密已实现、断点续传已验收。
- P0：FTP 控制和数据连接的 SSRF 防护；FTPS 主机名及数据 TLS 校验；安全恢复偏移与远端身份；多用户凭据/文件隔离；有效的本地真实协议测试。
- 若管理内网 FTP 为需求，必须由运维预置目标 allowlist，不能放开全部私有网络；如果要求 SFTP，需独立 SPEC/Plan，不得按 FTP 库实现。
- 所有配置必须走 backend/.env → application.yml 占位符及 .env.example，且所有文本 UTF-8 无 BOM；当前仅写 Plan、不触碰运行参数。
- 每轮处理后依据 AGENTS.md 将本轮改动文件、真正执行的验证及 Exit Code、未完成事项写入 HANDOFF.md；不得顺带提交/推送现有未提交代码。
