# ClipFetch 电驴（eD2k / Kad）aMule 集成设计规格（SPEC）

> 设计日期：2026-11-04（America/Los_Angeles）  
> 状态：待实施、未验收、非生产就绪  
> 仓库：D:\release\clipfetch  
> 对应计划：docs/superpowers/plans/2026-11-04-ed2k-amule-integration.md  
> 项目约束：AGENTS.md、HANDOFF.md、ISSUE.md  
> 适用范围：新电驴限界上下文、aMule Docker、Vue 页面、双库迁移。现有 BT v0/v1/v2 不改变语义。

## 1. 目标和范围

在 ClipFetch 现有 Spring Boot 单体中添加 **独立 eD2k 文件下载能力**。容器内 aMule 负责 eD2k Server 与 Kad 网络，Java 负责用户认证、任务、共享资源协调、完成文件交付，Vue 负责交互。

一期能力：单条 ed2k file 链接解析、提交、任务列表/详情、进度/速度/来源、暂停/恢复/逻辑删除、断点续传、已完成文件的登录态 GET/Range、健康检查、Windows/Linux 容器持久化。

**核心业务不变量**：相同规范 MD4 + 文件大小只产生一份受管 aMule 下载；多个用户拥有独立任务和 desired_state；用户 A 暂停或删除不能影响仍有 ACTIVE 需求的用户 B；任何数据读取必须验证当前 user_id。

非目标：Java 重写 eD2k/Kad、复用 BitTorrent Piece Scheduler、搜索/集合下载、Server/ServerList 输入、多节点 HA、多实例 API、物理资源 GC、MQ、通用 Redis 缓存、微服务或新 cron/Quartz/@Scheduled。

## 2. 技术决策

| 维度 | 选择 |
|---|---|
| 后端 | Java 21、Spring Boot 3.5.4、唯一后端端口 8082 |
| 前端 | Vue 3 / Vite / Tailwind / vue-router，独立 /ed2k |
| DB | SQLite + MySQL，Flyway 双库同版本新增迁移 |
| ORM | MyBatis-Plus 实体、domain Mapper、SQL 仅 XML |
| 引擎 | 固定 aMule 3.2.0、Docker 内 amuled + amuleapi |
| HTTP | JDK java.net.http.HttpClient，不新增 HTTP 依赖 |
| 认证 | ClipFetch JWT + user_id，与上游管理 JWT 隔离 |
| 共享 | hash + size 归一化、resource revision + engine_action |
| 调度 | 启动及业务请求惰性有界对账，不新增周期调度器 |
| 删除 | 逻辑删除 deleted=id，首期不自动物理删除共享文件 |
| 现有引擎 | qBittorrent v0、Native BT v1、Native v2 保持原行为 |

aMule 3.2.0 于 2026-10-28 发布，3.1.0 起存在 amuleapi REST /api/v1。**必须先验证固定镜像及 3.2.0 源码 docs/api/REFERENCE.md 的实际下载 API**，不凭文档猜接口。原生 amuleapi 默认 HTTP 4713、EC 4712；第三方 ngosang/amule 镜像默认 REST/Web UI **4711**，且该镜像不是官方 aMule 发行包。锁定版本、镜像 digest 和端口后再开发。

依据：
- https://amule-org.github.io/changelog/3.2.0
- https://amule-org.github.io/docs/manual/interfaces/amuleapi
- https://amule-org.github.io/docs/manual/installation
- https://github.com/amule-org/amule/blob/master/docs/api/REFERENCE.md
- https://github.com/ngosang/docker-amule

## 3. 系统架构

~~~mermaid
flowchart LR
    FE[Vue 3 /ed2k] -->|ClipFetch JWT| C[Ed2kController]
    C --> S[Ed2kTaskService]
    S --> R[Ed2kResourceCoordinator]
    R --> DB[(SQLite / MySQL)]
    R --> J[Ed2kReconcileService]
    J --> H[JDK HttpClient]
    H -->|loopback REST| API[amuleapi]
    API -->|EC 容器内| AM[amuled]
    AM <--> NET[eD2k / Kad]
    AM --> DISK[(Temp / Incoming)]
    S --> F[Ed2kFileService]
    F -->|校验授权和路径| DISK
~~~

### 3.1 Java 限界上下文

~~~text
backend/src/main/java/cc/ivera/ed2k/
  interfaces/
    Ed2kController, Ed2kHealthController, Ed2kFileController, dto/*
  application/
    Ed2kTaskService, Ed2kResourceCoordinator,
    Ed2kReconcileService, Ed2kFileService
  domain/
    Ed2kLinkParser, Ed2kIdentity,
    Ed2kResource, Ed2kDownloadTask, Ed2kEngineAction,
    Ed2kResourceMapper, Ed2kDownloadTaskMapper, Ed2kEngineActionMapper
  infrastructure/
    AmuleApiClient, AmuleProperties, FilePathPolicy

backend/src/main/resources/mapper/Ed2k*.xml
backend/src/main/resources/db/migration/sqlite/V25__ed2k_amule.sql
backend/src/main/resources/db/migration/mysql/V25__ed2k_amule.sql
frontend/src/api/ed2k.js
frontend/src/components/Ed2kDownloader.vue
frontend/src/router/index.js
frontend/src/components/AppHeader.vue
infra/amule/docker-compose.yml
~~~

V25 只是候选；实施前再次检查两库是否空闲。禁止改历史 V1–V24。Mapper 放 domain、SQL 全部写 XML，实体沿用 @TableName、@TableId(type=IdType.AUTO)、Lombok、LocalDateTime，禁止注解 SQL。

### 3.2 网络安全边界

- 管理端口仅发布到宿主 127.0.0.1，浏览器绝不直接访问 amuleapi；EC 默认 4712 只在容器内，不发布公网。
- 镜像数据端口可按需发布 4662/TCP、4665/UDP、4672/UDP；防火墙策略明确，不能自动转发管理端口。
- 不占用仓库已有 8082、5173、3306、6379、50051、18766、8095–8098 端口。
- 不引入 Docker Socket 挂载，且不把敏感配置或 ClipFetch 文件库挂到 aMule 共享目录。
- 单 API + 单 aMule 为一期边界；跨机器的明文 HTTP 管理访问禁止直接套用该架构。

## 4. 链接格式与身份安全

只接受规范格式：

~~~text
ed2k://|file|<encoded-name>|<positive-size>|<32-hex-md4>|/
~~~

解析规则：

1. 单链接；拒绝多个链接、换行、server/serverlist、未验证扩展参数及非法百分号编码；
2. URL 解码一次，不能二次解码后造成路径穿越；
3. NAME 拒绝空名、控制字符、NUL、路径分隔符、Windows 保留设备名、点路径、尾随空格或句点、超长名称；
4. SIZE 为正 long，非溢出且不超过配置的上限；实际大文件能力以锁定版 aMule 实测为准；
5. MD4 必须 32 hex，统一小写；不同 size 即使 hash 相同也不是同一资源；
6. 不记录原始 link 和管理凭据到日志，不允许用户文件名直接决定磁盘位置。

共享身份为 (md4_hash,file_size)，**用户显示名必须来自自己的任务 requested_name**，不可把用户 A 的文件名通过公共资源表返回给 B。同资源不同显示名允许共享底层数据。

## 5. 数据库结构（V25 候选）

### 5.1 ed2k_resource：共享的服务端记录

| 字段 | 语义 |
|---|---|
| id | bigint/integer 自增 PK |
| md4_hash, file_size | 不可变身份，UNIQUE(md4_hash,file_size) |
| internal_name | 内部安全名称，不跨用户公开 |
| engine_state | 最近一次上游观测状态 |
| engine_managed | 是否 ClipFetch 创建并受管；默认 false |
| engine_ref | 上游经实际 API 确认的稳定文件标识，可空 |
| completed_path | 可信映射产生的相对存储 key，可空 |
| completed_verified_at | 受控文件身份确认时间，可空 |
| observed_bytes, last_snapshot_at | 上游最近可靠快照 |
| revision | 资源状态目标代数，单调递增 |
| created_at, updated_at | 创建与更新时间 |

未受管（engine_managed=false）的上游既有项目不能由本服务暂停、恢复或取消；相同 hash 冲突应标记并交由人工处理，不能私自接管。

### 5.2 ed2k_download_task：用户私有记录

| 字段 | 语义 |
|---|---|
| id, task_uuid | 自增主键与对外 UUID |
| user_id, resource_id | 用户和共享资源关联 |
| requested_name | 当前用户提交的安全显示文件名 |
| desired_state | ACTIVE / PAUSED / DELETED |
| task_state | 当前用户的任务呈现状态 |
| progress_bytes | 最近可靠快照，可能 stale |
| revision | 用户任务 CAS 代数 |
| error_code, error_message | 安全错误信息 |
| completed_at, created_at, updated_at | 时间 |
| deleted | 默认 0，删除写本行 id |

必须创建 UNIQUE(task_uuid,deleted)、UNIQUE(user_id,resource_id,deleted)、INDEX(user_id,desired_state,deleted) 和 INDEX(resource_id,desired_state,deleted)。

每个列表/详情/操作/文件查询必须附加 user_id、deleted=0；跨用户 task_uuid 返回“不存在”，避免泄露别人的任务。同用户重复提交未删除的同一资源返回原任务；删后可重新创建。

### 5.3 ed2k_engine_action：持久化上游操作

| 字段 | 语义 |
|---|---|
| id, action_uuid | 自增主键与唯一动作 UUID |
| resource_id | 共享资源 |
| action | ADD / PAUSE / RESUME / OBSERVE |
| target_revision | 资源目标 revision |
| action_state | PENDING / APPLYING / APPLIED / RETRY_WAIT / SUPERSEDED / FAILED |
| attempt_count, next_retry_at | 有界重试与退避 |
| last_error_code, last_error | 脱敏上游错误 |
| created_at, updated_at | 时间 |

唯一约束 (action_uuid) 与 (resource_id,target_revision,action)，并建立待执行动作检索索引。首期**不提供 REMOVE 物理清理动作**；最后一个用户删除也只隐藏用户任务，不直接清除共享文件。

### 5.4 SQL 与事务合同

- 用户删除：UPDATE ed2k_download_task SET deleted=id, desired_state='DELETED' WHERE id=? AND user_id=? AND deleted=0；
- SQLite/MySQL 都使用唯一约束、事务和 revision CAS，必要时 MySQL 行级锁；不能仅靠单 JVM Map 锁保证业务一致性；
- DB 事务不跨 HTTP 调用，任何远端请求与数据库回写必须分开；
- Flyway 迁移双库同版本、表和唯一索引语义等价，不修改旧迁移。

## 6. 双层状态机与共享协调

### 6.1 用户个人状态

~~~mermaid
stateDiagram-v2
    [*] --> ACTIVE: 创建
    ACTIVE --> PAUSED: 暂停
    PAUSED --> ACTIVE: 恢复
    ACTIVE --> DELETED: 逻辑删除
    PAUSED --> DELETED: 逻辑删除
    DELETED --> [*]
~~~

DELETED 与 deleted=id 同时落库，永不自动复活。PAUSED 是个人意图，不必让共享资源停止。

### 6.2 资源观测状态

~~~mermaid
stateDiagram-v2
    [*] --> NOT_ADDED
    NOT_ADDED --> ADDING: 首次 ACTIVE
    ADDING --> WAITING: 上游受理
    WAITING --> DOWNLOADING: 有数据传输
    WAITING --> PAUSED: 无 ACTIVE
    DOWNLOADING --> PAUSED: 无 ACTIVE
    PAUSED --> WAITING: 出现 ACTIVE
    DOWNLOADING --> VERIFYING: 数据下载完成
    VERIFYING --> COMPLETED: 文件身份与路径验证
    WAITING --> ERROR: 上游异常
    DOWNLOADING --> ERROR: 上游异常
    ERROR --> WAITING: 有界恢复
~~~

资源状态来源于可靠上游快照；不能只按进度 100% 标记 COMPLETED。

### 6.3 聚合规则

~~~text
activeDemand = count(tasks where resource_id=? and deleted=0 and desired_state='ACTIVE')
activeDemand > 0:
  NOT_ADDED -> ADD
  PAUSED -> RESUME
  正在下载/已完成 -> NONE
activeDemand == 0:
  已受管且未完成 -> PAUSE
  未开始/已完成 -> NONE
~~~

| 用户 A | 用户 B | 底层引擎动作 |
|---|---|---|
| ACTIVE | ACTIVE | 只下载一次 |
| PAUSED | ACTIVE | 继续下载 |
| PAUSED | PAUSED | PAUSE |
| ACTIVE | PAUSED | RESUME |
| DELETED | ACTIVE | B 不受影响 |
| DELETED | DELETED | 保留共享数据，无物理删除 |

用户 A 暂停时，B 仍在下载，A 的可见状态继续 PAUSED，但底层 verified bytes 可能增长；前端说明“个人已暂停，共享下载可能仍在进行”，不显示其他用户身份。

### 6.4 revision fencing 与补偿

~~~mermaid
sequenceDiagram
    participant U as 用户
    participant API as Spring Boot
    participant DB as 数据库
    participant AM as aMule
    U->>API: pause(taskUuid,expectedRevision)
    API->>DB: T1 查 user_id + CAS + 锁定资源
    DB-->>API: 写 desired_state + resource revision + PENDING action
    API-->>U: 已保存操作意图
    API->>DB: 查询当前 revision 与需求
    API->>AM: 查询实际状态，执行 ADD/PAUSE/RESUME
    AM-->>API: 结果或超时
    API->>DB: T3 仅匹配 revision 才确认写回
    Note over API,DB: 旧结果无权覆盖新意图，必要时生成补偿动作
~~~

1. T1 原子写个人意图、资源 revision 和 action，提交后才发外部 HTTP；
2. 执行前核查 revision 与有效 ACTIVE 数，过时 action 标记 SUPERSEDED；
3. 每个资源一次只执行一个动作，内存锁可辅助排序，但数据库仍须做 fencing；
4. HTTP 成功但回写前崩溃：重查上游状态后幂等回放；不能盲目重复 ADD；
5. 旧 HTTP 命令在新 revision 之后才生效：禁止旧回写覆盖新意图，创建新 revision 的补偿直至收敛；
6. APPLYING 在进程崩溃后有界重新领取并查询实际结果；不能无限自旋；
7. 首期单 Spring Boot + 单 aMule，仅保证最终一致，不宣称网络动作 exactly-once。

### 6.5 离线政策

选定 **先保存用户意图、后执行远端动作**：aMule 离线时创建、恢复仍可写数据库，状态 QUEUED/WAITING_ENGINE、pendingEngine=true；健康接口 HTTP 200，列表/详情返回最近快照 stale=true。应用启动、创建/列表/详情/控制操作触发有界惰性对账，不为电驴新加 @Scheduled。长时间无请求时不保证实时继续推进，但持久化意图不可丢失。

## 7. 上游 REST 适配合同

~~~java
interface Ed2kEngineClient {
    EngineHealth health();
    EngineSnapshot findByIdentity(Ed2kIdentity identity);
    EngineResult add(Ed2kIdentity identity, String safeName);
    EngineResult pause(Ed2kIdentity identity);
    EngineResult resume(Ed2kIdentity identity);
    EngineSnapshot inspectCompleted(Ed2kIdentity identity);
}
~~~

- findByIdentity 区分 FOUND_MANAGED、FOUND_UNMANAGED、NOT_FOUND、UNAVAILABLE、UNKNOWN；超时绝不能当 NOT_FOUND。
- Java 使用 JDK HttpClient 调用 REST，不引入额外 HTTP 库；管理 JWT 与 ClipFetch JWT 独立。
- 已知 POST /api/v1/auth/login 负责登录；admin 凭据在后端受保护配置，401 最多重新认证并重试一次，403 不升级权限，429 有界遵守 Retry-After，网络超时按 UNKNOWN_OUTCOME。
- 上游结果统一转换为 hash、size、下载状态、已验证字节（如果上游有）、speed_bps、sources、完成文件 ref（如果上游有）、observed_at。
- **ADD/PAUSE/RESUME 具体上游 METHOD/URL/body/状态码必须用 Task 0 锁定版本和真实容器返回填写**，不可编造。

| 上游能力 | 合同确认状态 |
|---|---|
| REST base | /api/v1；官方说明已证实 |
| Admin 登录 | POST /api/v1/auth/login；待容器核实 body/响应 |
| Health/Version | 具体 endpoint/字段待确认 |
| 下载列表/按 hash 查找 | 具体 endpoint/字段待确认 |
| ADD、PAUSE、RESUME | 具体 endpoint/字段待确认 |
| 完成文件按 hash+size 定位 | P0 必须确认 |
| 401/403/429/5xx 映射 | Task 0 实测 |

**P0 阻断**：无法唯一可信地将 MD4+size 与一个 Incoming 文件映射时，不允许提供下载接口，不得仅凭文件名猜测归属。

## 8. ClipFetch 对外 API 合同

所有端点需 ClipFetch JWT、使用 currentUserId(request)，业务响应 ApiResponse.ok(data)，错误 BusinessException 统一由 GlobalExceptionHandler 包装。禁止直接在 Controller 捕获异常返回任意 Map。

| Method | 路径 | 含义 |
|---|---|---|
| GET | /api/ed2k/engine/health | 200 + configured/connected/version/message |
| POST | /api/ed2k/tasks | 创建或幂等获取当前用户任务 |
| GET | /api/ed2k/tasks?page=1&size=10 | 本用户分页，size ≤30 |
| GET | /api/ed2k/tasks/{taskUuid} | 本用户详情 |
| POST | /api/ed2k/tasks/{taskUuid}/pause | 个人暂停，带 expectedRevision |
| POST | /api/ed2k/tasks/{taskUuid}/resume | 个人恢复，带 expectedRevision |
| DELETE | /api/ed2k/tasks/{taskUuid} | 仅当前用户逻辑删除 |
| GET | /api/ed2k/tasks/{taskUuid}/files/0/content | 完成后授权的 GET/Range |

示例创建请求（哈希仅为格式演示，不代表真实来源）：

~~~json
{"ed2kLink":"ed2k://|file|sample.bin|1024|0123456789abcdef0123456789abcdef|/"}
~~~

示例响应：

~~~json
{
  "success": true,
  "data": {
    "taskUuid": "5ff98949-a3cd-4341-8e82-2f83e2398b70",
    "fileName": "sample.bin",
    "fileSize": 1024,
    "desiredState": "ACTIVE",
    "status": "QUEUED",
    "progressBytes": 0,
    "progressPercent": 0,
    "speedBps": 0,
    "sources": null,
    "pendingEngine": true,
    "stale": true,
    "revision": 0
  }
}
~~~

暂停/恢复请求：{"expectedRevision":0}，成功后返回新 revision；过期 revision → 409。健康返回结构：

~~~json
{"success":true,"data":{"configured":true,"connected":false,"version":"","message":"aMule 未连接，请检查容器及管理接口"}}
~~~

健康无论引擎断连/未配置均 HTTP 200。列表在引擎离线时安全降级读 DB。401 只用于 ClipFetch 用户 JWT 失效，不能把上游 admin 401 透传；跨用户任务返回 404，Range 不满足返回 416；用户输入格式 400，未托管上游资源冲突 409。

## 9. 已完成文件与 Range 安全

文件访问准入必须全部满足：

1. JWT 验证成功且 task_uuid + user_id + deleted=0 匹配当前用户；
2. 共享资源 engine_managed=true，已确认与上游 MD4 + size 一致；
3. 上游明确 COMPLETE；受控 Incoming 根中的文件存在、大小匹配且具有可信唯一映射；
4. 真实路径始终位于 Incoming 根内部，拒绝 symlink、Windows junction/reparse point、路径逃逸和任意用户绝对路径；
5. 文件名仅用作当前用户的 Content-Disposition，不能用来查找文件。

eD2k 文件 hash 不可在所有尺寸下简单使用一次整文件 MD4 代替：跨 chunk 使用特定 eD2k 分块规则；固定版的 chunk 边界及整倍数规则必须由真实 fixture 校验。上游完成事实和独立 hash 校验的边界需通过真实样本验证。

- 无 Range 返回 200，单段 bytes=start-end 返回 206 + Content-Range，非法/不可满足返回 416；服务端采用有界 buffer 流式写出。
- 已删除、未完成、文件失效、跨用户不能下载；文件读取前重验路径与当前权限，对文件被替换的 TOCTOU 情况设计回归测试。
- 前端按 AGENTS.md 使用 request.js + axios responseType:'blob' 携带 JWT 下载，浏览器 Blob 大文件占内存是明确限制，不能宣称完全低内存。
- 不新开裸白名单；后续如需真正低内存浏览器下载，单独设计一次性短时票据、用户/任务/文件绑定与重复校验。

## 10. Docker、持久目录与配置

~~~text
infra/amule/
  docker-compose.yml
  README.md
  Dockerfile              # 自建镜像需要时
  entrypoint/             # 初始化与退出，必要时

AMULE_DATA_ROOT/
  config/
  downloads/
    temp/
    incoming/
~~~

若采用 ngosang/amule，镜像配置路径为 /home/amule/.aMule、下载根 /downloads、REST 默认 4711；若自行构建需重新实测。Windows 使用 .env 指定类似 D:/release/clipfetch/data/ed2k，Linux 采用自己的绝对根。挂载持久化、非 root 运行、数据权限最小化、优雅停止、part.met 重启恢复均为验收事项。

| 配置项 | 说明 |
|---|---|
| AMULE_ENABLED | 默认 false |
| AMULE_API_BASE_URL | 后端能访问的可信本机 URL |
| AMULE_API_PASSWORD | 受保护的 admin 密码，禁止日志输出 |
| AMULE_DATA_ROOT / AMULE_CONFIG_ROOT / AMULE_INCOMING_ROOT | 持久路径和受限文件读取 |
| AMULE_API_HOST_PORT | 宿主管理端口，和镜像内部端口区分 |
| AMULE_ED2K_TCP_PORT / AMULE_ED2K_SERVER_UDP_PORT / AMULE_KAD_UDP_PORT | P2P 数据端口 |
| AMULE_HTTP_CONNECT_TIMEOUT_MS / AMULE_HTTP_REQUEST_TIMEOUT_MS | 有界 HTTP 超时 |
| AMULE_HTTP_RETRY_MAX / AMULE_HTTP_RETRY_INITIAL_MS / AMULE_HTTP_RETRY_MAX_MS | 重试与退避 |
| AMULE_MAX_LINK_LENGTH / AMULE_MAX_FILE_BYTES | 输入上限 |
| AMULE_MAX_ACTIVE_TASKS_PER_USER | 单用户任务配额 |
| AMULE_RECONCILE_BATCH_SIZE / AMULE_RECONCILE_COOLDOWN_MS | 有界惰性对账 |
| AMULE_OBSERVATION_STALE_MS / AMULE_FILE_BUFFER_BYTES | 快照时效与文件流缓冲 |

应用参数经 backend/.env → application.yml 占位符并同步 .env.example，真实密码文件不提交 Git；所有文本 UTF-8 无 BOM。配置验证不足时不能默默开放管理端口。aMule 3.2.0 的 allowed-remote-dirs.dat 应仅允许下载目录，禁止远程配置任意宿主路径；升级旧配置检查共享范围、EC 监听地址、UPnP。aMule 可能继续分享已下载资源，不能把 ClipFetch 私有文件库挂为共享目录。

## 11. 前端 UI 与轮询

- 独立 /ed2k 路由，router/index.js 使用 () => import(...)；AppHeader.vue 桌面和移动端都有入口；
- 所有接口经 api/ed2k.js 和 api/request.js，错误文案用 errMsg，沿用 JWT refresh；
- 未登录不轮询；引擎未就绪只读历史及健康，不发高频列表/进度请求；断连立刻熔断，组件卸载/路由切换清理 interval；不允许重叠轮询；
- 提交单条链接、查看个人任务、pause/resume/delete、授权文件下载；删除二次确认，按钮防重复，版本冲突时刷新；
- 显示 stale、pendingEngine 及个人暂停/共享仍运行的差异，不展示其他用户的任务或身份；
- 移动端适配小于 md 视口，不引入新重型 UI 框架。

## 12. 测试矩阵与验收

| 测试层 | 必须覆盖 |
|---|---|
| 领域单测 | 解析、大小边界、hash、文件名和路径安全 |
| DB 集成 | SQLite/MySQL V25 迁移、唯一键、CAS、deleted=id、跨用户 |
| 共享资源 | A/B 同链仅一底层下载、暂停组合、恢复、删除隔离 |
| 上游合同 | 认证失效/刷新、409/429/5xx/超时、重复 ADD、上游字段 |
| 故障注入 | DB commit 后未调用 REST、REST 成功后崩溃、旧 action 补偿 |
| 安全 | 未托管资源拒绝控制、跨用户 UUID、Range、路径逃逸、同名不同 hash |
| 持久化 | Windows/Linux container recreate、part.met 续传、磁盘不足 |
| UI | 健康门禁、轮询熔断、卸载清理、移动端布局 |
| 既有功能 | 磁力 v0/v1/v2 基本任务和健康 API 回归 |

需使用受控合法 eD2k fixture 及可控来源测试真实传输、最终 hash+size、暂停恢复与文件导出。公网无来源不能伪造成功。

仅实施代码后才执行 mvn clean compile -DskipTests、mvn test、npm run build、Docker 真实验收、git diff --check 与无 BOM 检查。文档阶段禁止把测试写为已经通过。

## 13. 计划任务与 SPEC 对应关系

| Plan 阶段 | 本规格要求 |
|---|---|
| Task 0 | 固定 3.2.0、镜像与完整 REST 合同、受控文件路径能力 |
| Task 1 | eD2k 输入解析规则及领域单测 |
| Task 2 | V25 双库、Mapper XML、事务和用户隔离 |
| Task 3 | JDK HTTP 客户端、JWT、错误与快照 DTO |
| Task 4 | 共享协调、action journal、revision、故障补偿 |
| Task 5 | /api/ed2k HTTP 与授权/分页/健康 |
| Task 6 | 完成文件映射、GET/Range、安全 |
| Task 7 | Vue /ed2k、移动导航、轮询安全 |
| Task 8 | 独立 Compose、持久化与故障注入 |
| Task 9 | 编译/测试/回归/README 影响章节/HANDOFF |

**P0 仍待验证**：上游操作 endpoint 精确字段、稳定 hash 定位、完整文件可信映射、旧动作补偿、真实 eD2k 分块 hash 及大文件边界。任一未满足不能宣称生产就绪。

本文件为设计规格，实施顺序按既有 Plan Task 0–9；遵守 AGENTS.md 的源码目录、SQL/XML、双库迁移、无 BOM、交接与禁止自动 commit 约束。
