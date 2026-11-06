# ClipFetch FTP / FTPS 下载集成技术规格（SPEC）

> 日期：2026-11-05（America/Los_Angeles）
> 状态：**设计待实施，未完成实际 FTP/FTPS 协议联调**
> 仓库：D:\release\clipfetch
> 实施 Plan：docs/superpowers/plans/2026-11-05-ftp-ftps-download-integration.md
> 项目约束：AGENTS.md、HANDOFF.md、ISSUE.md
> 实施纪律：该设计是后续编码与验收合同，不是完成声明；本轮只新增 SPEC 与更新 HANDOFF，不改代码、不执行构建、不创建 commit。

## 1. 目标、边界与完成标准

### 1.1 业务目标

让 ClipFetch 登录用户创建自己的单文件 FTP 下载任务，通过服务器端 FTP/FTPS 客户端把远端文件写入受控目录，管理任务状态、进度、暂停/继续、故障恢复、逻辑删除和完成文件获取。

一期验收必须证明：

1. 普通 FTP（管理员显式开启）和显式 FTPS（AUTH TLS，默认允许）的单文件 RETR 可用；
2. 默认被动 EPSV/PASV、二进制传输、断点续传条件检查和有限重试；
3. 两用户使用相同 host/path 也有各自任务、凭据和物理 .part；不会相互影响；
4. 服务器异常、网络断开、Java 重启后按可靠 checkpoint 恢复，远端不确定时拒绝拼接；
5. 控制连接及被动数据连接均受 SSRF、地址与 TLS 安全策略约束；
6. 使用 ClipFetch JWT，按 user_id 授权完成文件 GET/单段 Range；
7. SQLite 和 MySQL 独立迁移、前端路由、测试和磁力/电驴回归无故障。

### 1.2 非目标

- 不包含 SFTP。SSH File Transfer Protocol 与 FTP/FTPS 是不同的协议。
- 不做远端上传、改名、删除、目录递归、FTP FXP、主动 PORT/EPRT 或远程 FTP 代理中继。
- 不做匿名开放任意私网目标、不支持通过用户输入绕过证书校验。
- 不做多节点 Worker、跨用户资源复用、MQ、Quartz、通用 Redis 缓存、独立微服务。
- 不修改已有 magnet v0/v1/v2、ed2k 限界上下文和已发布 Flyway 脚本。
- 不承诺所有 FTP 服务器都支持 REST、SIZE、MLST/MDTM 或可恢复的大文件。

### 1.3 已核对仓库状态

- Java 21、Spring Boot 3.5.4，唯一后端 8082；Vue 3/Vite 6/Tailwind/vue-router，前端 5173。
- 新业务遵循 cc.ivera.ftp 的 interfaces/application/domain/infrastructure；Mapper 接口在 domain，SQL 只在 resources/mapper/*.xml。
- SQLite/MySQL 当前目录已有 V25__ed2k_amule.sql，且暂未看到 V26；FTP 预计新增双库 V26__ftp_download.sql，真正实施前必须再次查重。
- HANDOFF 的当前内容比旧 Plan 更近：ed2k 代码、测试及前端已在工作树出现，部分仍未提交；本设计不可覆盖这些既有改动。
- 所有 API 按 AGENTS.md 使用 JWT、currentUserId(request)、ApiResponse.ok 和 BusinessException，分页上限 30；文件下载不新增裸白名单。
- 本轮只读文件/编写规格，未运行真实 FTP 服务器、Maven、npm、Docker 或协议测试。

## 2. 技术架构与职责

### 2.1 组件关系

~~~mermaid
flowchart LR
    V[Vue /ftp] -->|JWT HTTP| C[FtpController]
    C --> S[FtpTaskService]
    S <--> DB[(SQLite / MySQL)]
    S --> Q[FtpTransferCoordinator]
    Q --> E[FTP Client Adapter]
    E -->|FTP 或 Explicit FTPS| R[FTP Server]
    Q --> K[Checkpoint Store]
    K --> DB
    Q --> F[(data/ftp 用户任务隔离)]
    C --> G[FtpFileService]
    G -->|权限和路径验证| F
~~~

### 2.2 分层与预计文件

~~~text
backend/src/main/java/cc/ivera/ftp/
  interfaces/
    FtpController.java
    FtpHealthController.java
    FtpFileController.java
    FtpDtos.java
  application/
    FtpTaskService.java
    FtpTransferCoordinator.java
    FtpRecoveryService.java
    FtpFileService.java
  domain/
    FtpDownloadTask.java
    FtpTaskCredential.java
    FtpTransferCheckpoint.java
    FtpTaskState.java
    FtpRequestValidator.java
    FtpDownloadTaskMapper.java
    FtpTaskCredentialMapper.java
    FtpTransferCheckpointMapper.java
  infrastructure/
    CommonsNetFtpClient.java
    FtpProperties.java
    FtpCredentialCipher.java
    FtpPathPolicy.java
    FtpTargetPolicy.java

backend/src/main/resources/mapper/Ftp*.xml
backend/src/main/resources/db/migration/sqlite/V26__ftp_download.sql
backend/src/main/resources/db/migration/mysql/V26__ftp_download.sql
frontend/src/api/ftp.js
frontend/src/components/FtpDownloader.vue
frontend/src/router/index.js
frontend/src/components/AppHeader.vue
~~~

- Controller 只做输入校验、当前用户识别与调用，不持有下载线程。
- Application 负责任务状态、单任务执行权、并发配额、重试、恢复和删除。
- Domain 负责输入契约、状态机和不可变远端身份快照。
- Infrastructure 对 FTP/FTPS、磁盘、加密、地址策略进行实现；不能从响应 DTO 访问任何密钥。
- 执行器是进程内有界 ExecutorService，不新增消息队列或 @Scheduled 轮询器；启动与用户触发的恢复由现有 Spring 生命周期和应用操作推动。

## 3. 协议选型与连接合同

### 3.1 版本及对象模型

- 候选 Apache Commons Net 3.13.0，实施 Task 0 重新核实版本、安全公告和 Java 21 兼容性，不把候选当已验证依赖。
- FTPClient 用于普通 FTP；FTPSClient 仅用于明确选择的 Explicit FTPS。
- 每项正在执行的任务独立创建 FTPClient/FTPSClient；控制连接和数据流不可跨线程/跨用户共享。
- 仅使用 PASV/EPSV 被动模式，不下发 PORT/EPRT，不使用 FTP FXP。
- 强制 BINARY 模式，禁止文件内容自动文本编码转换。
- 显式 FTPS 顺序：建立 TCP → AUTH TLS → 验证控制 TLS/主机名 → USER/PASS → PBSZ 0 → PROT P → 被动 RETR；不同服务器回复码按实际协议验证。
- FTPS 控制与数据都必须加密，TLS 或证书链/域名验证失败直接终止；不自动回退普通 FTP 或 PROT C。
- REST 断点恢复必须在发起 RETR 前设置偏移，并实际确认服务器支持和接受该偏移；setRestartOffset 仅设置客户端意图，不代表已经完成校验。
- 下载数据流结束后仍要读取并确认 completePendingCommand 的最终 FTP 完成回复；不能仅以 socket EOF 作为成功。
- 控制连接 connect、数据 socket connect、read、overall transfer、空闲时间、重试和取消的超时均必须有限且可配置。
- 控制/数据会话释放必须放 finally，包括超时、异常、取消和 Spring shutdown；不会泄漏端口和线程。
- Explicit FTPS 默认控制端口 21；隐式 FTPS 990 首期不支持，后续单独评审。SFTP 不归 FTPSClient 实现。

### 3.2 连接与服务器能力快照

每次开始或恢复时只查询必要特性：

| 目的 | 能力与判定 |
|---|---|
| 登录 | 成功认证或 FTP 530 受控失败；不能映射成 ClipFetch 401 |
| 类型 | TYPE I 成功后才开始 |
| 被动数据连接 | EPSV 优先，必要时安全 PASV 回退 |
| 文件长度 | SIZE 若真实支持记录正整数或零；未知明确记为 UNKNOWN |
| 远端修改时间 | MDTM 若真实支持记录标准化精度和原值 |
| 唯一文件身份 | MLST/MLSD 中 unique 只在服务器真实支持、稳定且可验证时使用 |
| REST 能力 | 必须通过实际 REST/RETR 测试确认，不只检查 FEAT 是否列出 |
| RETR 完成 | 最终 2xx 回复和预期字节长度一致 |
| 服务器错误 | 记录清洗后的 FTP reply code、error_code，不回显原始敏感回复 |

注意：FTP 没有统一强制支持的远端内容哈希。服务器提供的 SIZE + MDTM/unique 只是可比较的远端身份依据，不自动证明内容未被恶意替换；需要严格内容真实性时应额外要求可信 SHA-256 期望值。

## 4. FTP 输入契约与主机安全

### 4.1 客户端创建参数

| 字段 | 规则 |
|---|---|
| protocol | 仅 FTP / FTPS_EXPLICIT |
| host | 独立 DNS 名称或经明确批准的 IP，不带 scheme、userinfo、路径 |
| port | 1–65535 且通过管理员允许端口策略；默认协议端口 21 |
| remotePath | 非空 FTP 服务器路径，禁止 CRLF/NUL/控制字符/FTP 命令注入 |
| authMode | CREDENTIALS 或 ANONYMOUS |
| username/password | 仅具名模式使用，禁止在 URL、日志或 GET 参数出现 |
| displayName | 可选受控名称，拒绝路径分隔符/控制字符/保留设备名 |
| expectedSha256 | 可选用户可信期望摘要，64 位十六进制 |
| idempotencyKey | 可选用户级高熵请求幂等键，过期及作用域受配置控制 |

- 允许将无用户信息的 ftp:// 或 ftps:// 文件 URL 作为输入辅助解析，但服务端必须先拆成明确字段再次校验。
- 禁止 URL userinfo、密码嵌入 URL、FTP 原始命令输入、换行注入、非标准协议自动回退和协议混淆。
- 不接受目录递归或 wildcard 文件名；远端文件名不能成为本地文件系统路径片段。
- 返回 DTO 不含 username/password、完整有敏感性的 remotePath、登录会话和密钥标识；必要时只返回 host、脱敏用户名和安全展示名。

### 4.2 SSRF：两条连接都要限制

该功能让服务器替用户主动连接 FTP 主机；因此是高风险 SSRF 接口。仅 URL 校验是不够的。

1. 优先按管理员批准的准确目标 host/IP+port allowlist 管理访问；启用公共目标时仍必须校验解析出的全部 A/AAAA 地址。
2. 默认拒绝 loopback、private、link-local、carrier-grade NAT、ULA、multicast、unspecified、reserved、云元数据及 IPv4-mapped IPv6 目标；拒绝含多地址其中一个非法的 DNS 结果。
3. 任何内网 FTP 目标只能由运维批准精确 IP/主机+端口，不能让普通用户自由扩大网段或修改 allowlist。
4. 域名解析、连接前及连接后实际对端 IP 校验；防 DNS rebinding。重试和续传重连时重复校验。若第三方 FTP 库不能绑定并核对受审 IP，应停止开放任意主机功能并评审连接适配方式。
5. FTP PASV 服务端声明地址和 EPSV 数据端口也属于另一条外部连接；保持数据主机/地址验证，不允许 PASV 任意第三方地址，不能走通 FXP。
6. 若服务器 NAT 公告了错误私网 PASV 地址，只允许使用已通过同一授权的控制远端地址与经检查的端口，不无条件信任 PASV 公告地址。
7. 以主机防火墙/容器 egress 策略阻止对管理网段及元数据端点访问，作为 Java 检查之外的第二道防线。不能仅靠 Java DNS 检查宣称已抵御一切重绑定。
8. 连接测试 API 用相同策略执行，严格限制用户频率、并发、总超时和回复长度；不能成为端口扫描器。

~~~mermaid
flowchart TD
    A[创建或测试 FTP 目标] --> B{协议 输入 合法}
    B -->|否| R[拒绝]
    B -->|是| C{管理员网络策略通过}
    C -->|否| R
    C -->|是| D[解析并核查所有 IP]
    D --> E{全部准许}
    E -->|否| R
    E -->|是| F[建立 FTP 控制连接并校验远端]
    F --> G{Passive 数据对端合规}
    G -->|否| R
    G -->|是| H[才允许 RETR]
~~~

### 4.3 FTPS 安全不变量

- 验证可信 CA、有效期、主机名及证书链；数据通道要求 PROT P，按实现验证会话安全策略。
- 一律禁止接受所有证书、忽略主机名、FTPS 失败回退明文 FTP、日志记录原始口令。
- 不默认通过任意自签发证书；如果企业 CA 确有需求，由管理员配置专用 truststore，绝非用户请求开关。
- 普通 FTP 默认关闭，部署管理员明确允许后才可创建，UI 明确警告明文口令和传输安全风险。

## 5. SQLite/MySQL 物理数据模型（V26 候选）

### 5.1 ftp_download_task

| 字段 | 类型与语义 |
|---|---|
| id | BIGINT / SQLite INTEGER 自增 PK |
| task_uuid | VARCHAR(36)，对外不可猜测 UUID |
| user_id | BIGINT，非空 |
| protocol | VARCHAR(20)，FTP / FTPS_EXPLICIT |
| host | VARCHAR(253)，经过规范化的目标 |
| port | INT，正端口 |
| remote_path | 受控字段，只供服务端使用；不以原文回显 |
| display_name | VARCHAR(255)，只供用户展示和 Content-Disposition |
| status | VARCHAR(32)，当前持久状态 |
| total_bytes | BIGINT 可空，未知不等于 0 |
| downloaded_bytes | BIGINT，已报告且可靠的字节 |
| expected_sha256 | CHAR(64) 可空，需是可信预期 |
| revision | BIGINT，CAS 版本 |
| execution_epoch | BIGINT，工作者认领/恢复代数 |
| retry_count / next_retry_at | 受控重试 |
| last_error_code / last_error_message | 清洗后的错误 |
| completed_at / created_at / updated_at | LocalDateTime |
| deleted | BIGINT 默认 0，删除写本行 id |

索引：UNIQUE(task_uuid,deleted)，INDEX(user_id,status,deleted)，INDEX(user_id,created_at,id)；如支持用户级 Idempotency-Key，另加 (user_id,idempotency_key,deleted) 唯一键（仅在键非空且实际方言语义已验证后实施）。

### 5.2 ftp_task_credential

| 字段 | 类型与语义 |
|---|---|
| id / task_id / user_id | 主键、所属任务、当前用户 |
| auth_mode | ANONYMOUS 或 CREDENTIALS |
| encrypted_username / encrypted_password | 两个敏感字段密文，匿名时可空 |
| username_nonce / password_nonce | 每字段独立随机 GCM nonce |
| key_id / cipher_version | 解密主密钥引用和格式版本 |
| created_at / updated_at / deleted | 时间、deleted=id |

UNIQUE(task_id,deleted)，INDEX(user_id,task_id,deleted)；实体敏感字段 @JsonIgnore。即使数据库泄漏也不应直接拿到 FTP 口令，主密钥绝不能和数据库一起存放。

### 5.3 ftp_transfer_checkpoint

| 字段 | 类型与语义 |
|---|---|
| id / task_id / user_id | 主键与归属 |
| local_key | 仅存相对的安全 .part 存储 key |
| remote_size | 上次 SIZE，可空 |
| remote_mtime | 上次有效 MDTM/MLST modify，可空 |
| remote_unique_id | 已验证可靠的远端唯一标识，可空 |
| committed_bytes | 已持久落盘的可信字节数 |
| local_sha256 | 本地检查点/完成文件内部校验摘要；不是远端真实性证明 |
| revision | CAS |
| last_verified_at / updated_at / deleted | 时间和逻辑删除 |

UNIQUE(task_id,deleted)，INDEX(user_id,task_id,deleted)；存储结构不得保存前端可选绝对路径。

### 5.4 持久层约束

1. Sqlite 与 MySQL migration 脚本必须同版本且名字一致，实施前确认 V26 未占用；绝不改 V1–V25。
2. SQL 只能在 backend/src/main/resources/mapper/Ftp*.xml；Mapper 接口位于 cc.ivera.ftp.domain，禁止 @Select/@Update 等 SQL 注解。
3. 含用户身份的查询/更新/删除一律 WHERE user_id=? AND deleted=0，不得仅凭 task_uuid 定位数据。
4. 按 AGENTS.md 实体注解规范、LocalDateTime、逻辑删除 deleted=id；凭据和 checkpoint 数据应按任务所有权清理或不可用标记，不得物理删除敏感审计数据，除非单独合规评审。
5. 每次用户操作必须 CAS 校验 expectedRevision；线程回调写状态附加 execution_epoch 和 revision，不得让旧 worker 结果反向覆盖新意图。
6. 数据库不可用时禁止开启无持久状态的新下载，以免产生孤儿文件；不会把内存 Map 作为唯一真相。

## 6. 凭据加密、轮换与安全保密

- 持久化具名身份需 AES-256-GCM；每字段生成唯一加密 nonce，并把 user_id、task_uuid、字段名、cipher_version 放入 GCM AAD，防止密文跨用户/任务/字段互换。
- FTP_CREDENTIAL_KEY / KEY_ID 等主密钥由保护的 backend/.env 或独立密钥挂载注入，严格校验解码长度和密钥版本；密钥不存表中，也不输出至构建日志。
- 创建任务先验证字段、权限和网络目标，再加密入库；错误路径回滚，不落残缺凭据。
- 解密仅在执行指定用户拥有的任务时进行，内存中最短持有、不可写入 exception message、HTTP Response、审计日志。
- 密钥丢失、密文认证失败或版本无法处理时任务转 BLOCKED_CREDENTIALS（或明确等价错误态），禁止错误密码重试风暴。
- 轮换需读取 old key id，重新加密为 new key，测试失败回滚和 old key 移除时机；不要求首期在线无停机大规模轮换，但不得设计为无法轮换。
- 删除用户任务时，凭据不可继续被执行线程使用；应在安全停用后清理或以受控保留政策处理密文。

## 7. 任务状态机、并发和停止语义

### 7.1 用户任务状态机

~~~mermaid
stateDiagram-v2
    [*] --> QUEUED: 创建
    QUEUED --> CONNECTING: worker 领取
    CONNECTING --> DOWNLOADING: 已认证且文件检查通过
    DOWNLOADING --> VERIFYING: RETR 和 FTP 最终响应成功
    VERIFYING --> COMPLETED: 磁盘与大小及可选 hash 验证
    QUEUED --> PAUSED: 暂停
    CONNECTING --> PAUSING: 暂停
    DOWNLOADING --> PAUSING: 暂停
    PAUSING --> PAUSED: 流停用并提交检查点
    PAUSED --> QUEUED: 恢复
    CONNECTING --> RETRY_WAIT: 临时失败
    DOWNLOADING --> RETRY_WAIT: 临时失败
    RETRY_WAIT --> QUEUED: 受控重试
    CONNECTING --> FAILED: 永久失败
    DOWNLOADING --> FAILED: 永久失败
    DOWNLOADING --> NEEDS_RESTART: 远端身份变化
    PAUSED --> NEEDS_RESTART: 恢复时身份变化
    NEEDS_RESTART --> QUEUED: 用户确认清空并重下
    QUEUED --> DELETING: 用户删除
    PAUSED --> DELETING: 用户删除
    COMPLETED --> DELETING: 用户删除
    DELETING --> DELETED: 终止执行和逻辑删除
~~~

- BLOCKED_CREDENTIALS 可由等待恢复/连接状态进入，重新提供正确合法凭据需另行鉴权设计，不能自动把旧错误凭据继续使用。
- 删除、暂停也必须从 RETRY_WAIT、VERIFYING 和失败状态正确处理；正式代码可把多条合法事件写成 transition table，不是图上没有画就允许直接改任意状态。
- COMPLETED 不会由旧异步回调回滚为 DOWNLOADING，DELETED 永不自动复活。
- 如果用户点击暂停时 FTP 数据 socket 正在读取，必须先设置取消意图、主动中止本任务 socket、等待执行租约结束，再持久 checkpoint；不让暂停按钮返回“已暂停”却仍长期写盘。
- 如果用户删除，先禁止任务继续执行，再按安全路径清理自有文件；不能删除其他任务目录。清理失败记受控诊断，不反向恢复任务。

### 7.2 单任务执行权与公平性

- 使用有界 worker pool 和队列，最大并发、每用户并发、全局待处理队列长度均由配置限定。
- 每任务唯一执行实例使用 DB revision + execution_epoch fencing；队列里的重复任务先核查后去重。
- worker 领取时 CAS 更新 QUEUED → CONNECTING 并递增 execution_epoch；只有当前 epoch 可报告字节和完成；旧任务应被取消并释放 socket。
- 不使用 Redis 锁，不新增 MQ 和 Quartz；单 Spring Boot 进程内执行，启动时检查遗留 CONNECTING/DOWNLOADING，作为异常中断恢复评估。
- 稳定的错误分类 PERMANENT（530 错密码、策略拒绝、路径不存在）/ TRANSIENT（暂时断线、421 等）/ UNKNOWN（状态不明），重试次数和指数退避有界。
- 每次 Retry 必须重新鉴别目标 IP、FTPS 证书、远端 SIZE/身份和 .part checkpoint。
- 对于用户请求并发限制，超出队列上限返回明确的 429/503；不能无界积压线程或 socket。

## 8. 断点续传与完整性合同

### 8.1 检查点持久化

建议磁盘布局：

~~~text
FTP_STORAGE_ROOT/
  users/{userId}/tasks/{taskUuid}/
    download.part
    completed.bin
~~~

目录名只能使用内部 ID/UUID，不拼接用户 host、remotePath、displayName。

- 写入 download.part 时按可配置 buffer streaming；每次 checkpoint 先 flush/fsync 文件，再记录已落盘 committed_bytes；确保不会出现 DB 已提交高于磁盘可靠长度的错配。
- 重启后首先检查真实 .part 路径和长度；若数据库已确认长度大于磁盘长度，不能按数据库偏移续传；若磁盘长于已确认 checkpoint，必须安全截断到已确认边界或重新验证，不能把未落库尾部视为可靠数据。
- checkpoint 的 local SHA-256 用于校验本地数据、与磁盘篡改诊断；不能替代远端内容身份检查。

### 8.2 自动恢复准入

恢复必须同时满足：

1. 相同协议与授权的服务器目标、控制连接与被动数据地址策略通过；
2. 有受控 .part 和有效 committed_bytes；
3. 服务器确实接受 REST offset（并返回规范的 RETR 过程）；
4. 远端 SIZE 与历史相同；若历史/当前 SIZE 缺失，不能安全自动续传；
5. 远端身份可比较且未发生变化，例如可靠 MLST unique、可靠时间戳和预期 digest 组合；仅相同 SIZE 不足以保证同一文件；
6. 当前执行 epoch/用户状态仍允许 ACTIVE 下载。

任一不满足，进入 NEEDS_RESTART / FAILED_REMOTE_CHANGED，用户只能明确确认从头重下；禁止在旧 part 上直接从 0 追加。

### 8.3 完成判定

必须同时满足：数据流正常消费、FTP 结束控制回复码成功、已下载长度符合可信 SIZE（如果 SIZE 不可获得则另有经过评审的完整性保证）、文件已 fsync、可选 expectedSha256 完整匹配、原子从 .part 转为完成文件、DB CAS 正确标记 COMPLETED。

FTP 并不标准化保证下载后的内容 SHA-256；当缺少 SIZE 和可信预期摘要时，一期应保守标记 NEEDS_VERIFICATION 而非自动 COMPLETED，具体边界要用真实 FTP fixture 验收。

### 8.4 远端文件变化策略

| 观测 | 行为 |
|---|---|
| 文件 SIZE 变了 | 拒绝续传，NEEDS_RESTART |
| SIZE 相同但可信 modify/unique 变了 | 拒绝续传，NEEDS_RESTART |
| SIZE 相同但不能校验远端身份 | 不静默续传，需从零开始 |
| REST 不支持或拒绝 offset | 不继续拼接，从零需用户确认 |
| socket EOF 但 FTP 未成功最终回复 | 不完成，记录可重试或待核验 |
| sha256 与可信 expected 不一致 | 校验失败，禁止导出 |
| 全部校验通过 | 原子落盘、COMPLETED |

## 9. 对外 HTTP API

所有 FTP API 默认由现有 ClipFetch JwtInterceptor 保护；Controller 路径统一 /api/ftp，返回 ApiResponse.ok(data)，错误 BusinessException + GlobalExceptionHandler；不把 FTP 管理失败作为用户 JWT 失效。

| Method | URI | 用途 |
|---|---|---|
| GET | /api/ftp/engine/health | 模块健康，HTTP 200 |
| POST | /api/ftp/connections/test | 受限、需登录的连接探测 |
| POST | /api/ftp/tasks | 创建单文件任务 |
| GET | /api/ftp/tasks?page=1&size=10 | 本用户任务分页，size ≤30 |
| GET | /api/ftp/tasks/{taskUuid} | 本用户详情 |
| POST | /api/ftp/tasks/{taskUuid}/pause | CAS 暂停 |
| POST | /api/ftp/tasks/{taskUuid}/resume | CAS 恢复 |
| POST | /api/ftp/tasks/{taskUuid}/restart | 确认清空旧片段，从 0 重下 |
| DELETE | /api/ftp/tasks/{taskUuid} | 逻辑删除和本任务清理 |
| GET | /api/ftp/tasks/{taskUuid}/files/0/content | 登录态完成文件读取与单段 Range |

### 9.1 请求与响应示例（示意，不代表真实服务）

创建请求：

~~~json
{
  "protocol": "FTPS_EXPLICIT",
  "host": "ftp.example.org",
  "port": 21,
  "remotePath": "/public/sample.bin",
  "authMode": "CREDENTIALS",
  "username": "sample-user",
  "password": "submitted-only-in-request",
  "displayName": "sample.bin",
  "expectedSha256": null
}
~~~

上述样例的用户名、密码仅为字段说明，不是实际登录凭据；该字符串不能被日志打印或持久明文存储。

~~~json
{
  "success": true,
  "data": {
    "taskUuid": "e01e3592-cf9a-409c-9b72-1bf224bc94a5",
    "fileName": "sample.bin",
    "protocol": "FTPS_EXPLICIT",
    "status": "QUEUED",
    "revision": 1,
    "downloadedBytes": 0,
    "totalBytes": null,
    "speedBps": 0,
    "stale": true,
    "resumable": false,
    "lastErrorCode": null
  }
}
~~~

控制请求：以 expectedRevision 表示 CAS，例如：

~~~json
{"expectedRevision":1}
~~~

- 健康 API 统一 HTTP 200 + 四个字段：configured、connected、version、message。这里 connected 只表示本机 FTP 下载模块已启用且执行器/存储就绪，不表示特定远程 FTP 服务器在线。
- GET /tasks 与 GET /tasks/{uuid} 首先来自当前用户 DB 快照，服务器离线不阻断历史查看；可附 stale / checkedAt，但不得假装远端在线。
- 错误建议：400 非法参数、401 ClipFetch JWT 失效、403 网络策略拒绝、404 非本用户任务或文件、409 revision/远端变化、416 Range 不满足、422 FTP 登录或路径失败、429 配额/探测限频、503 模块关闭或执行器不可用。
- FTP 530、TLS 错误绝不能直接映射为用户 JWT 401；底层 FTP 服务器返回可能包含敏感信息，错误 DTO 只给简明中文及清洗后的分类码。
- 成功响应不回显 FTP 密码、完整敏感路径、私有证书和本地绝对存储路径。

## 10. 授权文件交付与 Range

准入前必须：

1. JWT 成功，任务通过 task_uuid + user_id + deleted=0 查询；跨用户 UUID 统一 404。
2. 任务为 COMPLETED，完成文件已持久化，结果无未解决的 SHA-256/大小/回复码疑点。
3. 受控存储 key 归属此任务；realpath 位于 FTP_STORAGE_ROOT 内，拒绝软链接、Windows junction/reparse point 和路径穿越，读取时防 TOCTOU。
4. 确认磁盘文件大小与任务完成快照匹配；不能接受用户自定义绝对文件路径。
5. Content-Disposition 只用经过净化的个人 display_name；Content-Type 默认 application/octet-stream。

- 无 Range 返回 HTTP 200 与 Content-Length；合法单段 Range 返回 206、Accept-Ranges、Content-Range。
- 超界 Range 返回 416 与 Content-Range: bytes */total；多段 Range 首期明确拒绝或给出规范受限响应，不能返回错误字节。
- 服务器端有界 buffer 流式输出，不把完整文件加载到 JVM；浏览器继续使用 request.js + axios responseType:'blob' 携带 JWT，**Blob 大文件内存开销是首期已知限制**。
- 不给此 API 增加免登录白名单，不在 URL 写 JWT。未来如需要大文件低内存浏览器保存，另做短时票据与二次授权设计。

## 11. 配置、运行与安全边界

所有可变参数放 backend/.env 与 application.yml 占位符、.env.example 示例；真实值不纳入版本控制。建议配置：

| 环境变量 | 说明 |
|---|---|
| FTP_ENABLED | FTP 模块总开关，默认 false |
| FTP_ALLOW_PLAINTEXT | 明文 FTP 单独开关，默认 false |
| FTP_ALLOWED_TARGETS | 管理员配置准确主机/IP+port 白名单或严格公共目标策略 |
| FTP_STORAGE_ROOT | 受控文件保存根 |
| FTP_MAX_FILE_BYTES / FTP_MAX_ACTIVE_TASKS_PER_USER | 单文件大小/用户配额 |
| FTP_MAX_CONCURRENT / FTP_MAX_CONCURRENT_PER_USER | 并发阈值 |
| FTP_QUEUE_CAPACITY | 有界待处理队列 |
| FTP_CONNECT_TIMEOUT_MS / FTP_READ_TIMEOUT_MS | socket 超时 |
| FTP_TRANSFER_IDLE_TIMEOUT_MS / FTP_TRANSFER_DEADLINE_MS | 空闲及总时长限制 |
| FTP_RETRY_MAX / FTP_RETRY_INITIAL_MS / FTP_RETRY_MAX_MS | 最大重试和退避 |
| FTP_CHECKPOINT_BYTES / FTP_CHECKPOINT_INTERVAL_MS | fsync/检查点频率 |
| FTP_CREDENTIAL_KEY_ID / FTP_CREDENTIAL_KEY | 凭据加密主密钥配置，不可打印 |
| FTP_TRUSTSTORE_PATH / FTP_TRUSTSTORE_PASSWORD | FTPS 自定义企业 CA 时才需要 |
| FTP_PROBE_RATE_LIMIT / FTP_PROBE_TIMEOUT_MS | 测试连接限频及超时 |
| FTP_FILE_BUFFER_BYTES | 文件响应流 buffer 上限 |
| FTP_OBSERVATION_STALE_MS | 任务状态显示时效 |

所有数值必须做边界/非负校验；未配置凭据主密钥时拒绝具名用户可恢复任务，不得明文回退。配置里包含真实身份的内容必须被 gitignore 忽略，日志只能输出脱敏配置键和值的检查结果。

不新增 FTP 服务器容器作为产品强制依赖。受控测试 FTP/FTPS fixture 可在 infra 测试目录放独立配置，并使用本地非公网端口、固定测试数据；不得覆盖现有 native-v2 Compose 和 8082/5173 端口。

## 12. 前端状态与轮询

- 新建独立 /ftp 路由，router/index.js 通过动态 import 加载 FtpDownloader.vue，AppHeader.vue 桌面/移动导航都加入入口，视口小于 md 时不溢出。
- 所有 API 经 frontend/src/api/request.js，登录过期自动 refresh；errMsg 显示后端中文错误。
- 表单展示 FTP/FTPS 协议、主机、端口、文件路径、匿名或具名身份和密码明文切换按钮；密码仅保存在本次输入状态，提交后清空，不记 localStorage。
- 默认 FTPS；选择 FTP 时明确警示明文风险且只能在管理员允许时使用。
- 健康未就绪允许只读本用户历史，但不启动 1 秒高频进度轮询；引擎 ready 才启动轮询，失败时熔断、卸载时 clearInterval 并取消未结束请求；禁止请求重叠。
- 暂停、恢复、删除和重下操作均禁重复点击，expectedRevision 冲突时刷新详情；删除/从零重下需确认。
- 下载文件用 Blob JWT 方案，展示已知大文件限制，不输出任何登录密钥或其他用户路径。

## 13. 验收与测试矩阵

| 范畴 | 验收场景 |
|---|---|
| 输入单元 | host/IP、scheme、URI userinfo、CRLF、remotePath、保留名称、文件大小边界 |
| SSRF | loopback/private/ULA、IPv4 mapped、DNS rebinding、控制 IP 与被动数据 IP 不同 |
| FTPS | 正确 TLS、错误 CA、主机名不一致、过期证书、PROT P、失败无明文回退 |
| FTP | TYPE I、EPSV/PASV、RETR、REST、completePendingCommand 失败时不标完成 |
| 数据库 | SQLite/MySQL V26、空库及 V25 升级、唯一索引、CAS、user_id、deleted=id |
| 凭据 | AES-GCM nonce、AAD 防替换、损坏密文、缺密钥、轮换、日志脱敏 |
| 状态机 | 暂停/删除竞态、重复队列、旧 epoch 回调、失败/重试/远端变化 |
| 恢复 | 网络中断、Java 重启、文件长度与 checkpoint 不符、同 SIZE 内容变化 |
| 文件 | 越权、未完成、symlink/junction、Range 200/206/416、文件被替换 |
| 前端 | 登录门禁、轮询熔断、组件卸载、移动导航、确认框、重复点击 |
| 回归 | qBittorrent v0、Java Native v1、Native v2、ed2k API 和前端路由 |

受控可复现的本地 FTP 与 FTPS 测试服务应分别覆盖 0B、普通文件、大文件、断流、缺失 SIZE/MDTM、REST unsupported 与认证失败；若远端提供的身份信息不足，测试期望应为阻止自动续传而不是伪造“恢复成功”。

实施后才能执行并记录真实命令与 Exit Code：

~~~powershell
mvn -f backend/pom.xml clean compile -DskipTests
mvn -f backend/pom.xml test
Set-Location frontend
npm run build
Set-Location ..
git diff --check
~~~

还需运行实际双库迁移、MockMvc、受控 FTP/FTPS 集成测试、Windows/Linux 文件与进程恢复验证、BOM 检查及既有引擎冒烟；只生成本 SPEC 不执行构建和服务操作。

## 14. 实施任务映射

| Plan 阶段 | 本规格确定的产物 |
|---|---|
| Task 0 | Commons Net 版本/安全审查、FTP/FTPS fixture、控制与数据连接 SSRF P0 |
| Task 1 | 领域输入、不可变身份与状态迁移测试 |
| Task 2 | 两库 V26、Mapper XML、AES-GCM 凭据、user_id/逻辑删除 |
| Task 3 | FTPClient/FTPSClient、TLS、PASV/EPSV、REST 与回复码协议合同 |
| Task 4 | 有界 worker pool、下载流、落盘 checkpoint、断点续传和重启恢复 |
| Task 5 | /api/ftp 健康、任务、操作、连接探测 API |
| Task 6 | 本用户受限文件 GET/Range 与安全路径 |
| Task 7 | Vue /ftp 和移动导航、轮询安全 |
| Task 8 | Windows/Linux 故障注入、双库、性能配额和资源清理 |
| Task 9 | 实际编译/测试/E2E/回归、README 更新、HANDOFF 交接 |

## 15. 关键未决项与实施阻断

1. **P0：控制和被动数据连接 SSRF**。必须有可复现测试和网络级出口限制。若库无法准确控制连接 IP，则默认禁止用户任意主机。
2. **P0：FTPS 证书、域名及数据通道加密**。必须验证失败不降级。
3. **P0：可靠远端身份与续传**。SIZE 相同不能证明文件未变化，缺失充分证据必须重新下载。
4. **P0：任务和凭据多用户隔离**。相同 URI 不共享个人凭据、part 文件或服务端记录。
5. **P0：检查点与最后 FTP 回复**。必须 fsync/长度校验，并以完整 RETR 成功回复判定完成。
6. **P1：远端不支持 SIZE/MDTM/REST**。明确当前能力与失败策略，禁止将不支持能力当作“大小为 0”。
7. **P1：无法独立验证 FTP 服务器的文件真实性**。仅本地 SHA-256 与远端 SIZE/mtime 不构成可验证来源的完整性证明；必要时要求用户可信预期摘要。
8. **P1：浏览器大文件 Blob 内存占用**。保留为首期公开限制，未来另行设计安全下载票据。
9. **P1：当前 V25 ed2k 代码未提交**。不得改写、重构或提交已有工作树内容。

完成定义：严格按 Task 0 至 Task 9 分阶段实施，全部安全 P0 和必要双库、协议、鉴权、恢复、回归测试有真实通过证据才可声明完成。任何未来修改遵循 AGENTS.md 全部约束：UTF-8 无 BOM、SQL XML、同版本双库迁移、不加入无认证白名单、不新增 MQ/微服务/HTTP 客户端、不主动 commit，每轮真实更新 HANDOFF.md。
