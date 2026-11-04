# ClipFetch 自研磁力引擎 v2 集成设计

日期：2026-11-02  
状态：待用户书面审阅  
目标仓库：`D:\release\clipfetch`  
能力来源：`D:\release\magnet`

## 1. 目标

把 Magnet 平台已经落地的 C++ libtorrent 下载核心及其 Java 调度、恢复、逐文件校验能力合并进 ClipFetch，形成独立的自研引擎 v2。

v2 使用 ClipFetch 现有注册、登录、JWT 刷新和 `long user_id`，不迁移 Magnet 自己的用户、管理员或 JWT 体系。ClipFetch 继续只有一个 Spring Boot API 进程；C++ libtorrent Worker 是受控的本地或内网执行进程。

首个里程碑形成一条可真实验收的单用户端到端链路：

- 解析 V1、V2、Hybrid Magnet 和 `.torrent`；
- 选择文件并创建任务；
- 使用真实 libtorrent 下载；
- 暂停、恢复以及 API/Worker 重启恢复；
- 使用逐文件已校验字节展示进度；
- 连续两轮快照确认完成；
- 使用 ClipFetch 短时 ticket 下载已完成文件。

## 2. 已确定的取舍

### 2.1 采用

- 根 Maven parent/aggregator 下的多模块 Java 结构；
- 现有 `backend` 作为唯一 Spring Boot API，端口仍为 8082；
- 独立 C++20 + libtorrent 2.0.8 Worker，默认 gRPC 端口 50051；
- MySQL 为 v2 唯一持久化事实源；
- Redis 仅承担限频、并发配额和短时幂等；
- MySQL 任务表作为可靠工作队列，可配置 fixed-delay 调度器推进状态；
- qBittorrent、Java Native v1、Native v2 三条引擎路径并存。

### 2.2 不采用

- 不迁移 Magnet 的独立认证、用户表、管理员权限和 API 应用；
- 不迁移 Magnet P6 HTTP/aria2 能力，ClipFetch 现有媒体 aria2c 行为不变；
- 不把 Java API 拆成微服务；
- 不迁移正在运行的 Java Native v1 Piece、Checkpoint 或 Resume；
- 不在首期实现跨用户共享 Session、引用 GC、双 Worker HA、选主、Prometheus/Grafana或性能压测；
- 不在首期增加任务删除或物理数据清理接口；
- 不以公网种子或吞吐速度作为验收依赖。

## 3. 总体架构

### 3.1 目录与模块

```text
clipfetch/
├── pom.xml
├── backend/                              # 唯一 Spring Boot API
├── server/
│   ├── native-v2-domain/
│   ├── native-v2-engine-spi/
│   ├── native-v2-application/
│   ├── native-v2-infrastructure/
│   ├── native-v2-engine-libtorrent/
│   └── native-v2-scheduler/
├── native/
│   ├── proto/
│   │   └── download_engine_v2.proto
│   └── libtorrent-worker/
└── frontend/
```

根 `pom.xml` 统一管理 Java 21、Spring Boot 3.5.4、gRPC、protobuf、MyBatis、Redis和测试依赖版本。`backend` 改为根项目子模块，但仍是唯一可执行 Spring Boot jar。

### 3.2 Java 依赖方向

```text
native-v2-domain
       ↑
native-v2-engine-spi
       ↑
native-v2-application
       ↑
├── native-v2-infrastructure
├── native-v2-engine-libtorrent
└── native-v2-scheduler
       ↑
     backend
```

- `native-v2-domain`：领域对象、状态机、哈希身份、路径规则、错误分类和 Mapper 接口；为遵守 ClipFetch 实体约定，只允许 Lombok 与 MyBatis-Plus 实体注解依赖，不依赖 Spring 服务、文件系统、Redis、gRPC 或具体数据库实现。
- `native-v2-engine-spi`：Worker 无关的 Engine 接口、命令、快照和恢复数据类型。
- `native-v2-application`：解析、任务、恢复、ticket 和端口编排；只依赖领域及端口。
- `native-v2-infrastructure`：MyBatis XML、MySQL/SQLite 适配、Redis 配额和文件存储。
- `native-v2-engine-libtorrent`：protobuf 生成、gRPC client 和 libtorrent Engine 适配。
- `native-v2-scheduler`：解析领取、任务调度、进度刷新、重试和恢复 tick。
- `backend`：Controller、ClipFetch 登录上下文、响应包装和模块装配。

所有 v2 Java 包使用 `cc.ivera.magnet.v2`。不得把 Magnet 的 `download-api`、认证或用户包复制进 ClipFetch。

### 3.3 运行进程

首期运行单元为：

1. Vue/Vite 前端，开发端口 5173；
2. ClipFetch Spring Boot API，端口 8082；
3. MySQL；
4. Redis；
5. 单个 libtorrent Worker，默认 gRPC 端口 50051；
6. API 与 Worker 映射到同一逻辑存储根。

首期只支持单 API、单 Worker。protobuf 保留 `owner_epoch`，其值固定为协议不变量 `1`，不作为运行调参；`lease_until` 由调度器按配置租期持续刷新。租期过期后 Worker 必须停止该资源的写入，避免 API 长时间离线时继续失控下载，但这不宣称具备 P5 多 Worker 选主或接管能力。

## 4. 与现有 ClipFetch 的边界

### 4.1 认证

- 所有 `/api/magnet/v2/**` 业务端点使用 ClipFetch 现有 `AuthInterceptor`。
- Controller 使用现有 `currentUserId(request)` 取得 `long user_id`。
- 401 只用于 JWT 无效或过期；任务状态、Worker 或配置问题不得伪装成 401。
- 所有用户数据查询和更新必须同时带业务主键与 `user_id`。
- 跨用户 UUID 查询统一返回“任务不存在”，避免泄露资源归属。

### 4.2 引擎共存

- qBittorrent 既有 Controller、表、API 和前端组件不改语义。
- Java Native v1 继续管理既有任务，并作为回滚路径。
- 新建 v2 任务使用独立 API、表、存储根和引擎标识 `NATIVE_V2`。
- v1 与 v2 不共享 Piece、Checkpoint、Resume 或运行中任务状态。

### 4.3 SQLite

v2 只在 MySQL 模式运行。SQLite 模式下：

- qBittorrent 和 Native v1 保持可用；
- v2 健康端点返回 HTTP 200、`configured=false`；
- v2 不创建解析或下载任务；
- V24 SQLite 脚本只提供模式兼容，避免切库、备份或初始化工具无法识别新表。

## 5. 领域身份与数据模型

### 5.1 身份

- 数据库内部主键：`BIGINT AUTO_INCREMENT`。
- 对外解析 ID：`parse_uuid`。
- 对外资源 ID：`resource_uuid`。
- 对外任务 ID：`task_uuid`。
- Worker `engineSessionId` 恒等于 `resource_uuid`。
- BTIH 使用 20 字节二进制；BTMH 保存规范化的 32 字节 SHA-256 digest，不以显示字符串作为唯一真相。

### 5.2 表

#### `magnet_v2_resource`

公共种子的不可变资源身份：

- `id`、`resource_uuid`、`canonical_key`；
- `btih`、`btmh`、`version_type`；
- `name`、`piece_length`、`total_bytes`、`file_count`；
- `private_flag`、`metadata_key`、`metadata_status`；
- `created_at`、`updated_at`。

`resource_uuid` 和 `canonical_key` 唯一。资源首期只增不删。

#### `magnet_v2_resource_hash_alias`

- `id`、`resource_id`、`hash_type`、`hash_bytes`、`created_at`；
- 唯一键 `(hash_type, hash_bytes)`。

它负责把 V1-only、V2-only 和 Hybrid 的不同哈希原子归并到一个资源。

#### `magnet_v2_resource_file`

- `id`、`resource_id`、`file_index`；
- 已验证的规范相对路径 `relative_path`；
- `declared_bytes`；
- 可空的 `pieces_root`；
- `created_at`；
- 唯一键 `(resource_id, file_index)`。

资源文件首期只增不删。

#### `magnet_v2_parse_job`

- `id`、`parse_uuid`、`user_id`；
- `input_type`、`input_hash`、规范 BTIH/BTMH、受控 Tracker JSON；
- 上传 torrent 的 `input_storage_key`；
- 可空 `resource_id`；
- `status`、`revision`、`retry_count`、`next_retry_at`；
- `error_code`、`error_message`；
- `created_at`、`updated_at`、`deleted`。

`parse_uuid` 唯一；用户查询均带 `user_id` 和 `deleted=0`。

#### `magnet_v2_download_task`

- `id`、`task_uuid`、`user_id`、`resource_id`；
- `status`、`selected_bytes`、`verified_bytes`；
- `priority`、`speed_limit`；
- `revision`、`retry_count`、`next_retry_at`；
- `error_code`、`error_message`；
- `created_at`、`updated_at`、`deleted`。

`task_uuid` 唯一。任务状态变化使用 `id + user_id + expected_revision` CAS。

#### `magnet_v2_download_task_file`

- `id`、`user_id`、`task_id`、`file_index`；
- `selected`、`declared_bytes`、`verified_bytes`、`state`；
- `created_at`、`updated_at`、`deleted`；
- 唯一键 `(task_id, file_index, deleted)`。

#### `magnet_v2_engine_session`

- `id`、`resource_id`、`engine_session_id`；
- `worker_id`、`state`；
- 可空 `active_task_id`、`owner_user_id`；
- `owner_epoch`、`resume_version`、`resume_key`；
- `revision`、`created_at`、`updated_at`；
- `resource_id` 和 `engine_session_id` 分别唯一。

任务创建时先确保 Session 行存在，再使用 `SELECT ... FOR UPDATE` 锁定。若 `active_task_id` 指向非终态任务，则拒绝新建；否则原子占用该槽位。任务进入终态后清空槽位。这一机制落实首期“同一资源全局最多一个非终态 v2 任务”，不依赖仅在单 JVM 生效的内存锁。

#### `magnet_v2_task_event`

- `id`、`user_id`、`task_id`、`sequence`；
- `event_type`、经过清洗的 `payload_json`、`created_at`；
- 唯一键 `(task_id, sequence)`。

事件用于审计、轮询补拉和诊断，不替代任务表的当前状态。

### 5.3 逻辑删除

`parse_job`、`download_task` 和 `download_task_file` 遵循 ClipFetch 规则：

- 未删除为 `deleted=0`；
- 删除时执行 `SET deleted=id`；
- SQL 写在 MyBatis XML；
- 所有删除更新带 `user_id`；
- 唯一索引包含 `deleted`。

资源、别名、资源文件和首期 Session 不提供用户物理删除。GC 作为后续独立子项目设计。

## 6. 状态机

### 6.1 解析

```text
PENDING → RESOLVING → READY
                   ├→ FAILED
                   └→ TIMEOUT
```

只有 `TIMEOUT` 或带可重试错误码且原始输入仍可用的 `FAILED` 可以显式重试。重试增加 revision 和 attempt，不复用过期 Worker 结果；上传 torrent 的永久格式错误要求用户重新上传。

### 6.2 下载任务

```text
CREATED → QUEUED → STARTING → DOWNLOADING → VERIFYING → COMPLETED
                       │             │
                       └→ PAUSING → PAUSED → QUEUED
STARTING/DOWNLOADING/VERIFYING → RETRY_WAIT → QUEUED
任意非终态 → FAILED
```

首期不暴露删除 API；`DELETING/DELETED` 保留为后续 GC/删除扩展状态，不参与首期 UI。

### 6.3 Engine Session

```text
DISCOVERING → METADATA_READY → ACTIVE → IDLE
                         └────→ RECOVERING
任意非终态 ──────────────────→ FAILED / STOPPED
```

任务状态由 Java/MySQL 驱动。Worker 快照是观测事实，不直接绕过状态机写任务终态。

## 7. 存储布局

```text
${MAGNET_V2_STORAGE_ROOT}/
├── parse-input/{parseUuid}.torrent
├── metadata/{resourceUuid}.torrent
├── resources/{resourceUuid}/...
└── resume/{resourceUuid}.dat
```

- 上传的 torrent 先写临时文件、flush/关闭、原子 rename，再提交 `PENDING` 任务所引用的 storage key。
- READY 或永久失败后 best-effort 删除对应 `parse-input`；临时失败保留输入供有界重试，重试耗尽后再清理。清理失败只记录受控错误，不改变解析状态。
- metadata 和 resume 均使用临时文件、flush/关闭、原子 rename。
- 数据库只保存相对 storage key，不保存用户提交的绝对路径。
- Worker 只允许写 `resources/{resourceUuid}`。
- 首期不制作用户导出副本；ticket 从已完成资源文件直接流式读取。

## 8. Worker 协议

### 8.1 RPC

从 Magnet 合并并保留：

- `ResolveMetadata`
- `AddTorrent`
- `UpdateFilePriorities`
- `Pause`
- `Resume`
- `GetSnapshot`
- `SaveResumeData`
- `RemoveTorrent`（首期仅用于解析临时 Session 和测试清理，不作为用户删除）
- `StreamAlerts`（首期不是状态真相）
- `Health`

`GetSnapshot` 必须返回 Session 状态、metadata ready、总速率、Peer 数和按 torrent 文件下标排列的 `file_verified_bytes`。

### 8.2 命令信封与幂等

每条修改命令携带：

- `command_id`
- `resource_id`
- `owner_epoch`
- `deadline`
- `lease_until`

`command_id` 不随机生成后即丢弃，而是由业务操作稳定生成：

- 解析：`parse_uuid + revision + RESOLVE`
- 任务：`task_uuid + revision + operation`

同一 revision 的超时重试复用相同 command id；用户新意图必须先增加 revision，因此形成新 command id。Worker 缓存有界的近期 command id，重复命令返回原结果或等价成功。

首期每个数据 Session 使用 `owner_epoch=1`。调度器在每个有效命令中把 `lease_until` 刷新为“数据库当前时间 + 配置租期”；Worker 不得自行延长租期。API 停止续租并超过租期后，Worker 停止资源写入，等待 API 恢复后以 metadata、磁盘数据和 resume 重新挂载。

### 8.3 结构化错误

各响应在现有 `accepted/error` 之外增加稳定的 `error_code`。至少区分：

- `INVALID_ARGUMENT`
- `HASH_MISMATCH`
- `UNSAFE_PATH`
- `RESOURCE_BUSY`
- `STORAGE_UNAVAILABLE`
- `TEMPORARY_UNAVAILABLE`
- `INTERNAL`

Java 不根据英文错误文本判断重试。用户错误可以包含清洗后的 Worker 正文，但不得返回绝对路径、证书信息或内部堆栈。

## 9. API

所有分页接口单页上限 30，所有响应使用 `ApiResponse.ok(data)`。

### 9.1 健康

`GET /api/magnet/v2/engine/health`

始终 HTTP 200：

```json
{
  "configured": true,
  "connected": true,
  "version": "libtorrent/2.0.8",
  "message": "自研引擎 v2 已连接"
}
```

- `configured`：MySQL 模式、v2 已启用且必要配置存在。
- `connected`：Redis、共享存储探测和 Worker Health 均可用。
- 离线、未配置或 SQLite 模式不得抛业务异常。

### 9.2 解析

- `POST /api/magnet/v2/parses`：提交 Magnet。
- `POST /api/magnet/v2/parses/torrent`：上传 `.torrent`。
- `GET /api/magnet/v2/parses/{parseUuid}`：当前用户解析详情和文件列表。
- `GET /api/magnet/v2/parses?page=&size=`：当前用户解析历史。

创建接口返回 `parse_uuid` 和当前状态，不在请求线程等待 DHT 元数据。

### 9.3 任务

- `POST /api/magnet/v2/tasks`：以 `parseUuid + selectedFileIndexes` 创建任务。
- `GET /api/magnet/v2/tasks?page=&size=`：当前用户任务列表。
- `GET /api/magnet/v2/tasks/{taskUuid}`：任务、选中文件、逐文件进度和近期事件。
- `POST /api/magnet/v2/tasks/{taskUuid}/pause`：记录暂停意图。
- `POST /api/magnet/v2/tasks/{taskUuid}/resume`：记录恢复意图。
- `POST /api/magnet/v2/tasks/{taskUuid}/files/{fileIndex}/ticket`：签发文件 ticket。
- `GET /api/magnet/v2/downloads/stream/{ticket}`：浏览器 GET/Range 流。

只有最后一个 stream 端点加入认证白名单。它不接受 JWT URL 参数，只接受高熵 ticket。

## 10. 端到端数据流

### 10.1 解析

1. API 认证用户，校验 Magnet 或上传文件格式、大小并执行 Redis 配额。
2. Magnet 在请求线程只解析 BTIH、BTMH和受控 Tracker，不保存或记录原始 URI；上传文件原子暂存。
3. MySQL 事务写 `PENDING` job 并立即返回。
4. 调度器使用 CAS 把 job 领取为 `RESOLVING`。
5. 若公共资源 metadata 已 READY，直接把用户 job 绑定到该资源；否则建立 provisional resource 并调用 `ResolveMetadata`。
6. Worker 返回 torrent bytes 后，Java 再次解析并校验：
   - V1/V2/Hybrid 哈希；
   - metadata 大小和文件数量；
   - private flag；
   - 每个文件的规范相对路径；
   - 总大小和配置上限。
7. 哈希别名发生并发冲突时，以数据库中已存在资源为 winner；parse job 重新绑定 winner，临时 metadata-only Session 被移除。
8. Java 以最终 winner UUID 原子保存 metadata，在事务内写资源、别名、文件并把 job 置为 `READY`。
9. 超时进入 `TIMEOUT`，永久错误进入 `FAILED`；不得留下用户可见的半 READY 资源。

### 10.2 创建任务

1. 读取当前用户 READY parse job。
2. 校验文件索引非空、唯一、存在，并从资源文件表重新计算 selected bytes。
3. 确保 Session 行存在并 `SELECT FOR UPDATE`。
4. 若 Session 已被非终态任务占用，返回 409 和不泄露其他用户信息的中文错误。
5. 同一事务写 task、task files、首条 event 并占用 `active_task_id`。
6. 事务提交后才允许调度器产生 Worker 副作用。

### 10.3 调度和进度

1. 调度器从 MySQL CAS 领取到期的 `QUEUED/RETRY_WAIT/PAUSING` 任务。
2. 对启动任务调用 `AddTorrent(metadata, resume, storage_path)`。
3. 全量下发文件优先级：选中文件大于零，未选文件为零。
4. 调用 `GetSnapshot`；确认 Session 可用后进入 `DOWNLOADING`。
5. 每个 tick 只使用 `file_verified_bytes` 更新任务文件。
6. task verified bytes 是选中文件 verified bytes 之和，并被每个文件 declared bytes 截断。
7. 旧 revision 的快照不写回，记录 `STALE_RESULT_IGNORED`。

### 10.4 两轮完成

- 第一个 metadata ready 且所有选中文件 verified bytes 达到 declared bytes 的新快照：`DOWNLOADING → VERIFYING`。
- 后续独立 tick 的新快照仍完整：`VERIFYING → COMPLETED`。
- 若文件进度回退、Session 是 `RECOVERING` 或 metadata 未就绪，则回到/保持 `DOWNLOADING`。
- 数据库旧计数永远不能单独推进到 COMPLETED。

### 10.5 暂停

暂停请求首先 CAS 写 `PAUSING` 和新 revision。调度器：

1. 调用 `SaveResumeData`；
2. 原子保存 resume 并更新 resume version；
3. 调用 `Pause`；
4. 新快照确认后写 `PAUSED`。

Worker 离线时仍接受暂停意图。页面显示“暂停请求已记录，等待引擎恢复后执行”；重连后的第一条任务命令必须落实暂停，不能先恢复下载。

### 10.6 恢复与重启

恢复请求把 `PAUSED` CAS 为 `QUEUED`。调度器加载 metadata、已有资源目录和可用 resume，调用 `AddTorrent/Resume`，重新下发文件优先级。

API 启动或调度 tick 发现可恢复 Session 时：

1. 检查 metadata 和受控资源目录；
2. 读取 resume；
3. resume 缺失、损坏或版本不匹配时以空 resume 重建；
4. Worker 对已有文件执行 libtorrent 重校验；
5. 重校验结束前 Session 保持 `RECOVERING`，任务不能依据旧进度完成；
6. 重新读取逐文件快照并覆盖数据库观测进度。

Worker/gRPC 暂时离线时，任务进入 `RETRY_WAIT`，而不是伪装成永久失败。

### 10.7 ticket 下载

Bearer 鉴权接口只为当前用户的 `COMPLETED` 任务和已选择文件签发 ticket。ticket 绑定：

- `user_id`
- `task_uuid`
- `file_index`
- 过期时间

stream 端点每次请求重新校验 ticket、用户任务归属、任务状态、文件选择、real path 和文件存在性。ticket 是单任务、单文件、短时能力；为支持浏览器 Range/续传，它在 TTL 内允许同一文件的多个 Range 请求，而不是首个分片后立即销毁。任务状态回退或 ticket 过期后立即拒绝。

首期 ticket 保存在 API 进程内，符合单 API 边界；API 重启后旧 ticket 自动失效，用户可重新签发。Redis 只负责签发速率配额，不保存 ticket 授权真相，也不宣称支持多实例共享。

## 11. 错误、重试与降级

### 11.1 HTTP

| HTTP | 场景 |
|---|---|
| 400 | 请求、Magnet、torrent、文件选择或路径非法 |
| 404 | 当前用户数据不存在，包括跨用户访问 |
| 409 | 状态冲突或同资源已有活动任务 |
| 429 | 解析、任务或 ticket 配额超限 |
| 503 | Worker、Redis 或共享存储暂不可用 |
| 500 | 未处理异常 |

健康端点例外：依赖未配置或离线仍返回 HTTP 200。

### 11.2 重试分类

- 永久失败：非法 Magnet/torrent、哈希不符、private torrent、危险路径、资源超限。
- 有界重试：gRPC `UNAVAILABLE`、`DEADLINE_EXCEEDED`、临时网络和普通临时 I/O。
- 立即人工处理：磁盘满、权限不足、存储根不可写、证书配置错误。
- 过期结果：不改变任务，不返回用户错误，只写审计事件。

`RETRY_WAIT` 保存 `retry_count` 和 `next_retry_at`。退避初始值、最大值、抖动和最大次数全部来自配置。

### 11.3 降级矩阵

- Worker/共享存储离线：健康、列表和详情可用；新解析、新建任务和恢复返回 503；运行中任务进入 `RETRY_WAIT`。
- Worker 离线时暂停仍记录用户意图。
- Redis 离线：拒绝需要配额门禁的新解析和 ticket 签发；已有任务查询、暂停和 MySQL 驱动的恢复不依赖 Redis。
- Worker 离线但已完成文件仍存在时，已有有效 ticket 仍可读取。
- 前端健康未就绪时不启动高频轮询；轮询请求失败即清理定时器。

## 12. 安全

### 12.1 用户隔离

- 所有用户表 Mapper SQL 均显式带 `user_id`。
- 所有控制操作同时校验 `task_uuid + user_id + expected state/revision`。
- ticket 不携带 JWT，不允许访问未选择或未完成文件。

### 12.2 Worker 暴露面

- Worker 只绑定 loopback、Unix socket 或受控容器网络。
- 跨主机连接必须 mTLS；gRPC 端口不得公网暴露。
- Worker 再次验证 `storage_path` 位于配置下载根内。

### 12.3 网络与输入

- 首期拒绝 private torrent 和需要秘密 Tracker 凭据的资源。
- Tracker URL 禁止 userinfo；不记录查询参数。
- libtorrent IP filter 默认拒绝 loopback、链路本地、RFC1918、云元数据地址和其他保留网段作为 Tracker/Peer 目标。
- acceptance profile 可以显式放行测试 Docker 网段，普通运行 profile 不得继承。

### 12.4 文件系统

Java 与 Worker 双重拒绝：

- 绝对路径、盘符、UNC、`..`；
- NUL、控制字符、Windows 保留名、结尾点/空格；
- 大小写冲突；
- symlink、junction/reparse point 越界；
- real path 不在资源根下；
- 超过配置的组件、路径或文件数量限制。

### 12.5 日志

不得记录完整 Magnet、Tracker 查询参数、JWT、Cookie、ticket、resume、证书私钥或 `.env`。日志只使用用户 ID、parse/task/resource UUID、错误分类和清洗后的摘要。

## 13. 配额与配置

MySQL 是业务真相；Redis key 都必须有 TTL。至少配置：

- `MAGNET_V2_ENABLED`
- `MAGNET_V2_STORAGE_ROOT`
- `MAGNET_V2_WORKER_HOST`
- `MAGNET_V2_WORKER_PORT`
- `MAGNET_V2_WORKER_TLS_ENABLED`
- `MAGNET_V2_WORKER_CA_CERT`
- `MAGNET_V2_WORKER_CLIENT_CERT`
- `MAGNET_V2_WORKER_CLIENT_KEY`
- `MAGNET_V2_RPC_TIMEOUT_MS`
- `MAGNET_V2_GRPC_MAX_MESSAGE_BYTES`
- `MAGNET_V2_LEASE_DURATION_MS`
- `MAGNET_V2_METADATA_TIMEOUT_MS`
- `MAGNET_V2_RESUME_SAVE_TIMEOUT_MS`
- `MAGNET_V2_RECHECK_TIMEOUT_MS`
- `MAGNET_V2_SCHEDULER_DELAY_MS`
- `MAGNET_V2_PARSE_SWEEP_DELAY_MS`
- `MAGNET_V2_PARSE_RATE_PER_MINUTE`
- `MAGNET_V2_PARSE_CONCURRENT_PER_USER`
- `MAGNET_V2_MAX_TORRENT_BYTES`
- `MAGNET_V2_MAX_TRACKERS`
- `MAGNET_V2_MAX_FILES`
- `MAGNET_V2_MAX_SELECTED_FILES`
- `MAGNET_V2_MAX_TOTAL_BYTES`
- `MAGNET_V2_MAX_ACTIVE_TASKS_PER_USER`
- `MAGNET_V2_WORKER_RPC_CONCURRENCY`
- `MAGNET_V2_WORKER_ID`
- `MAGNET_V2_WORKER_LISTEN_ADDRESS`
- `MAGNET_V2_WORKER_DOWNLOAD_PORT`
- `MAGNET_V2_WORKER_DHT_ENABLED`
- `MAGNET_V2_WORKER_MAX_CONNECTIONS`
- `MAGNET_V2_WORKER_MAX_UPLOAD_SLOTS`
- `MAGNET_V2_WORKER_DOWNLOAD_RATE_LIMIT_BPS`
- `MAGNET_V2_WORKER_UPLOAD_RATE_LIMIT_BPS`
- `MAGNET_V2_WORKER_ALERT_QUEUE_SIZE`
- `MAGNET_V2_WORKER_DISK_WRITE_BUFFER_BYTES`
- `MAGNET_V2_RETRY_MAX_ATTEMPTS`
- `MAGNET_V2_RETRY_INITIAL_MS`
- `MAGNET_V2_RETRY_MAX_MS`
- `MAGNET_V2_RETRY_JITTER_PERCENT`
- `MAGNET_V2_TICKET_TTL_SECONDS`
- `MAGNET_V2_TICKET_MAX_ACTIVE`
- `MAGNET_V2_TICKET_RATE_PER_MINUTE`
- `MAGNET_V2_BLOCK_PRIVATE_NETWORKS`
- `MAGNET_V2_REDIS_HOST`
- `MAGNET_V2_REDIS_PORT`
- `MAGNET_V2_REDIS_PASSWORD`
- `MAGNET_V2_REDIS_DATABASE`
- `MAGNET_V2_REDIS_CONNECT_TIMEOUT_MS`

这些值必须在 `backend/.env` 完整体现，并由 `application.yml` 占位符绑定。实现若引入新的超时、并发、网络、内存或落盘调参，必须同时增加对应环境键；运行参数不得隐藏在 Java/C++ 常量中。协议固定常量（如首期 `owner_epoch=1`）和路径语法规则不属于可调运行参数。

## 14. 数据库迁移

当前 MySQL 与 SQLite 迁移最高均为 V23，因此新增：

- `backend/src/main/resources/db/migration/mysql/V24__magnet_v2_engine.sql`
- `backend/src/main/resources/db/migration/sqlite/V24__magnet_v2_engine.sql`

两边包含同一逻辑表和索引，按各自方言实现。不得修改 V1–V23。MySQL 迁移必须验证唯一别名、Session 唯一资源、逻辑删除索引和 CAS 所需索引。

实体放在 `native-v2-domain` 并遵循项目既有的 `@TableName`、`@TableId`、Lombok getter/setter/builder、`LocalDateTime` 和 `@TableLogic` 规则。Mapper 接口放在 `cc.ivera.magnet.v2.domain` 子包，由现有 `@MapperScan("cc.ivera.**.domain")` 覆盖；不添加 `@Mapper`，也不添加任何 MyBatis SQL 注解。所有 SQL 只写在 `resources/mapper/*.xml`，XML namespace 使用 Mapper 全限定名。

## 15. 前端

### 15.1 引擎入口

`MagnetEngineWorkspace.vue` 从两个引擎扩展为三个：

- `qbittorrent`
- `native`（Java Native v1）
- `native-v2`（自研引擎 v2）

路由继续使用已有 `/magnet/:engine?`，不新增页面级同步依赖。v2 重型组件使用：

```js
defineAsyncComponent(() => import('./NativeV2TorrentManager.vue'))
```

qBittorrent 和 v1 组件不改业务逻辑。

### 15.2 API 与组件

- 新增 `frontend/src/api/magnetV2.js`，统一使用 `request.js` 和 `errMsg`。
- 新增 `NativeV2TorrentManager.vue`，完成健康状态、Magnet/文件上传、解析、目录树选择、任务列表、暂停/恢复、逐文件进度和 ticket 下载。
- 桌面与移动布局沿用现有 Tailwind 风格，不新增 UI 框架。
- parse 轮询间隔 2 秒，task 轮询间隔 1 秒；只有引擎 connected 时启动。
- 任一轮询错误立即熔断；组件卸载、路由切换和登录状态变化必须清理 timer。
- Set/Map 操作封装为普通函数，模板不直接调用 ref 内部方法。

### 15.3 状态文案

至少区分：

- 未配置；
- Worker 离线；
- 解析中；
- 等待调度；
- 下载中；
- 正在校验；
- 暂停请求待执行；
- 已暂停；
- 等待重试；
- 已完成；
- 失败及中文处理动作。

## 16. TDD 与测试分层

当前 ClipFetch 与 Magnet 均没有测试源码，不能把“现有测试通过”作为基线。实施必须先建立最小测试结构。

### 16.1 单元测试

每个行为先写失败测试并实际观察 RED，再写最小实现到 GREEN。覆盖：

- V1/V2/Hybrid 哈希身份和 alias 归并；
- 路径和 private torrent 拒绝；
- 解析、任务和 Session 状态机；
- CAS 与过期结果；
- 同资源 active slot；
- 重试分类和退避边界；
- 两轮完成；
- 配额计算和逐文件进度截断。

### 16.2 应用与适配器测试

- 使用 fake Engine、可控时钟、临时存储测试解析、创建、暂停、恢复和重启恢复。
- gRPC 使用进程内 fake server 测试 deadline、command id、error code、resume 和 file snapshots。
- Controller 测试 ClipFetch JWT、`user_id` 隔离、状态码和健康 200 降级。

### 16.3 集成测试

Maven integration profile 使用 Testcontainers：

- MySQL 8.4：V24、Mapper XML、唯一约束、逻辑删除、CAS、行锁 active slot。
- Redis 7：原子限频、并发计数、TTL 和离线降级。
- SQLite：应用可启动、v2 health 未配置、qBittorrent/v1 Bean 不被破坏。

### 16.4 前端测试

- Vitest/Vue Test Utils：健康门禁、轮询启动/熔断/卸载、文件选择、状态文案和 ticket URL。
- Playwright：登录、选择 v2、提交 fixture、选择文件、启动任务并看到真实状态变化的一条烟测。

## 17. 确定性真实 Worker 验收

当前机器具备 Java 21、Maven 3.9.11、Node 24.21、npm 11.19、Docker 29.8.2，但宿主机没有 CMake。Worker 的 CMake 编译、CTest 和 libtorrent 运行全部放入 Linux Docker。

acceptance compose 启动：

- MySQL 8.4；
- Redis 7；
- ClipFetch API；
- 单 libtorrent Worker；
- 本地 Seeder/Tracker；
- acceptance runner。

固定的小型多文件 payload 生成或配套 V1、V2、Hybrid fixture，不访问公网。

必须验证：

1. ClipFetch 注册/登录/JWT 可访问 v2；
2. V1、V2、Hybrid torrent 均解析；
3. 至少一个 Magnet 通过本地蜂群真实获取 metadata；
4. 部分文件选择被 Worker 正确执行；
5. 暂停后连续两个观察窗口进度不增长，恢复后继续；
6. 分别重启 API 和 Worker，任务不丢失；
7. resume 缺失或损坏时执行磁盘重校验；
8. 人为破坏已写数据块后 verified bytes 回退并重新下载；
9. 最终 payload SHA-256 与 fixture 一致；
10. 两个独立完整快照后才 COMPLETED；
11. 完整和单 Range ticket 下载内容正确；
12. 跨用户、未选择、未完成、过期 ticket 和路径逃逸被拒绝；
13. Worker 停止时 health 仍 HTTP 200、列表可读、新任务 503、暂停意图保留；
14. Worker 恢复后先落实暂停；
15. SQLite 模式 v2 未配置且 qBittorrent/v1 不受影响。

## 18. 完成标准

必须全部满足：

- 根聚合项目 `mvn clean test` 通过；
- `mvn -Pnative-v2-it verify` 通过；
- `mvn clean compile -DskipTests` 通过；
- Worker Docker test target 构建和 CTest 通过；
- 真实 acceptance compose 退出码为 0；
- 前端 `npm run test:run`、`npm run build`、Playwright 烟测通过；
- `git diff --check` 通过；
- 全部变更文本无 UTF-8/UTF-16 BOM；
- Controller/配置改动后重启 8082，确认唯一监听者；
- HANDOFF 记录真实命令、Exit Code 和未验证边界。

任何真实 Worker、重启恢复、最终哈希或跨用户验收未执行/失败，都不能宣称 v2 完成或生产就绪。首期不以吞吐速度数字作为退出条件。

## 19. 后续里程碑

首期完成后，后续能力分别设计、分别实施：

1. 跨用户共享 Session 和需求并集；
2. 引用计数、宽限期和资源 GC；
3. 双 Worker HA、真实磁盘 fencing 和接管；
4. Prometheus/Grafana；
5. 性能基准与调优。

这些能力不得提前混入首期实现。
