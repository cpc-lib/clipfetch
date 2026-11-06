# ClipFetch 电驴（eD2k / Kad）aMule 集成实施计划

> 日期：2026-11-04（America/Los_Angeles）  
> 状态：**仅规划，尚未实施**  
> 适用仓库：D:\release\clipfetch  
> 开工依据：根 AGENTS.md、HANDOFF.md、ISSUE.md、docs/superpowers/plans/ 与 specs/ 既有文档，以及当前代码结构。  
> 实施纪律：按本计划逐项执行、先测试后实现；**本轮不修改业务代码、不启动容器、不执行构建**；不得自行创建 commit。

## 一、目标、定位与边界

### 1.1 目标

在现有 ClipFetch 单体 Spring Boot 业务中新增 **独立的电驴下载能力**，使用部署在 Docker 内的官方 **aMule 3.2.0** 运行 eD2k / Kad 下载协议，并由 Java 21 后端统一控制；Vue 3 前端提供创建任务、查询、暂停、恢复、逻辑删除、下载已完成文件和健康状态展示。Windows + Docker Desktop 与 Linux + Docker Engine 共用一套源码和配置约定。

**关键业务约束：同一条 eD2k 文件可以由多个 ClipFetch 用户分别建立任务，底层 aMule 只下载一份；任一用户的暂停/删除不得打断其他仍需下载的用户。** 每个用户只能看到和操作自己的数据库任务与授权文件。

### 1.2 明确非目标

- 不重新用 Java 实现 eD2k/Kad 协议，不复用 BT 的 Piece Scheduler、libtorrent Worker 或 qBittorrent API。
- 不重构现有 magnet/v0、v1、v2，不改变其 Flyway 历史版本与容器栈。
- 首期只支持合法的 **ed2k://|file|NAME|SIZE|MD4|/** 文件链接；不接收 server/serverlist 外部链接、不支持 BT magnet 与 eD2k 混淆、不做全网搜索与 .emulecollection 批量导入（另行立项）。
- eD2k 一条文件链接表示一个文件，不设计 BT 式多文件列表/文件勾选；若后续支持批量链接，可在多个独立任务上扩展。
- 首期仅支持 **单宿主机 Spring Boot + 单 aMule 容器**；不引入 MQ、微服务、额外 Java HTTP 客户端、通用 Redis 缓存或新 cron/Quartz 调度。
- 共享下载仅是应用层任务隔离和内容复用，不承诺 eD2k 网络匿名性或 aMule 进程/网络层用户隔离。

## 二、已核对项目事实

1. 后端：Java 21、Spring Boot 3.5.4、MyBatis-Plus/XML、SQLite/MySQL 双 Flyway；业务 API 单体部署，根包 cc.ivera。
2. 前端：Vue 3/Vite/Tailwind/axios/vue-router，现有路由为 /magnet/:engine?；磁力引擎选择器在 frontend/src/components/MagnetEngineWorkspace.vue。
3. 下载功能：magnet/v0（qBittorrent）、v1（Java Native BT）、v2（C++ libtorrent Worker + gRPC），与电驴协议互不兼容。
4. SQLite 和 MySQL 迁移均已使用 V1–V24；新增迁移要在开工时再次查重，预计为 V25。
5. /api/** 统一要求登录，WebConfig.java 管理唯一白名单；Controller 使用 ApiResponse 和 BusinessException；查询、修改均需 user_id。
6. 所有 SQL 写入 backend/src/main/resources/mapper/*.xml；删除用 deleted=id 逻辑删除；配置走 backend/.env 与 application.yml 占位符。
7. 需要鉴权的浏览器文件下载应优先沿用 axios Blob + JWT；既有 v1/v2 有大文件内存边界，后续可设计经严格校验的短时票据，但**不得裸加白名单**。
8. 既有 HANDOFF 记录宿主唯一后端 8082、前端 5173、Native v2 Docker 只含 certs/mysql/redis/worker；电驴独立 Compose 不应夺占这些端口。

## 三、版本和对接技术决策

### 3.1 版本

- **优先固定官方 aMule 3.2.0**（2026-10-28 安全版本），不使用漂移的 latest 标签，也不选存在已披露漏洞的老版本。
- 官方 3.1.0 起提供 **amuleapi**（REST /api/v1 + SSE），3.2.0 延续；在本机 Docker 中先验证具体发行包内的 amuled + amuleapi 可运行。
- 依据： https://amule-org.github.io/changelog/3.2.0
- 官方 API 说明： https://amule-org.github.io/docs/manual/interfaces/amuleapi
- 实施以 **3.2.0 源码内 docs/api/REFERENCE.md 的准确 endpoint、认证、状态码和字段**为合同；不得凭本文猜测接口签名。
- 若镜像或 REST API 在当前 Windows/Linux 架构不可用，**先停止推进并记录阻塞**；可另开决策评审改用容器内 amulecmd/EC，不能未经批准就引入外部 REST 网关或自行实现 EC 协议。

### 3.2 调用路径

~~~mermaid
flowchart LR
    U[Vue 3 用户页面] -->|JWT /api/ed2k| B[Spring Boot 8082]
    B --> A[Auth + user_id 归属校验]
    A --> S[Ed2k Application Service]
    S <--> DB[(SQLite / MySQL)]
    S --> C[Ed2kEngineClient - JDK HttpClient]
    C -->|127.0.0.1 仅宿主可达| API[Docker: amuleapi REST]
    API -->|容器内部 EC| D[Docker: amuled]
    D <--> N[eD2k Server / Kad 网络]
    D --> V[(持久化 Temp / Incoming)]
    B -->|授权后读取已完成文件| V
~~~

- 同一 Docker 服务内运行 amuled 与 amuleapi（容器进程管理和信号转发须验证），不公开 amuleapi、EC 管理接口给公网。
- Docker 仅将 REST 管理端口默认 4713 绑定到宿主 127.0.0.1；EC 默认 4712 只在容器内通信；eD2k/Kad 数据通信端口按配置映射并检查与 6881、50051、8082 等现有端口冲突。
- Java 只用 **java.net.http.HttpClient** 对接 amuleapi。按官方认证合同登录与刷新会话，不将密码、Bearer、Cookie、完整连接头写日志。
- 本地宿主访问优先，不直接让前端访问 aMule 管理接口；禁止把 Docker socket 挂进 Spring Boot 容器。

### 3.3 运行与数据目录

拟新增 infra/amule/（独立 Docker Compose、Dockerfile 或可信固定镜像、入口初始化、README）；宿主挂载根默认由 .env 传入，示意如下：

| 用途 | 容器内示意路径 | 宿主机 |
|---|---|---|
| aMule 状态与配置 | /data/config | AMULE_DATA_ROOT/config |
| 断点续传分块 | /data/temp | AMULE_DATA_ROOT/temp |
| 完成文件 | /data/incoming | AMULE_DATA_ROOT/incoming |

- Windows：由配置将 AMULE_DATA_ROOT 指向 D:/release/clipfetch/data/ed2k；Linux：部署者指定绝对目录；不在 Java 源码中硬编码。
- 单独为配置/完成文件目录设置最小文件系统权限、非 root 用户、容量上限与保留策略。禁止将配置、EC 密钥、用户任务、Incoming 暴露为静态站点。
- 容器重启不得清除 part/met、已完成文件、用户数据库任务；首次启动的配置生成须幂等且不能覆盖既有身份/下载状态。
- aMule 完成文件可能继续参与 P2P 共享；上传限速和共享目录策略需明确配置、告知运营方，**不得把 ClipFetch 用户私有文件库挂到 aMule 的共享目录**。

## 四、数据结构与并发一致性

建议新增三张表（具体 DDL 实施时分别写 SQLite/MySQL 方言），避免复用 magnet_* 旧表：

| 表 | 核心字段 | 约束 |
|---|---|---|
| ed2k_resource | id, md4_hash, file_size, original_name, engine_state, completed_path, revision, created_at, updated_at | 共享资源唯一键 (md4_hash,file_size)；仅服务端可见 |
| ed2k_download_task | id, task_uuid, user_id, resource_id, desired_state, task_state, progress_bytes, revision, deleted, created_at, updated_at, completed_at | (task_uuid,deleted) 唯一；(user_id,resource_id,deleted) 唯一；所有用户操作 user_id 限定 |
| ed2k_engine_action | id, action_uuid, resource_id, action, expected_revision, action_state, attempt_count, last_error, created_at, updated_at | action_uuid 唯一；记录待执行/已完成/待重试的引擎副作用 |

1. 资源身份为 **规范化 MD4（32 位十六进制）+ size（long）**，不以不可信文件名为唯一标识；aMule 管理操作采用官方支持的稳定 hash，不依赖会变化的队列索引。
2. 用户创建任务：事务中查找/插入共享资源和用户任务；用唯一约束 + CAS/必要时行锁处理并发，避免双资源、双下载。
3. **desired_state 持久化**：ACTIVE / PAUSED / DELETED；engine_action 先持久化再调用 aMule，成功回写，失败待下次业务触发时补偿。不在远端 HTTP 请求期间持有数据库事务。
4. 底层决策：存在至少一个有效 ACTIVE 用户任务才允许资源下载/恢复；全部 PAUSED 时才真正暂停；最后一个用户删除时才允许取消与清理共享资源，但数据清理需额外策略与确认。
5. 若 A、B 同文件，A 暂停或删除而 B ACTIVE：A 自己任务进入 PAUSED/DELETED，**底层继续下载**；A 页面可展示“个人已暂停，共享资源仍在下载”，避免进度看似违反暂停。
6. 用户 A 完成文件后即使资源仍为其他用户服务，A 的删除也只隐藏/逻辑删除自己的记录，绝不立即物理删除共享文件。
7. 后端启动、引擎恢复、创建/查询/控制任务时进行**有界、幂等惰性对账**；不为电驴新增定时扫描器（遵循 AGENTS 对调度的限制）。进程崩溃后的 action 可重放；不以仅靠前端轮询保证恢复。
8. 外部手工通过 aMule Web UI 添加、删除任务导致的“未托管资源”需区分：绝不展示给 ClipFetch 用户；未经资源归属判定不得对其取消或删除。
9. 完成状态必须同时满足：aMule 明确完成 + 服务端映射到受限 Incoming 路径 + 文件存在/大小与期望匹配；可用官方检验结果证明完整性，必要时进行独立校验。不能仅按进度 100% 判 COMPLETED。

### 用户隔离示例

| A 的操作 | B 的操作 | 底层同一资源 |
|---|---|---|
| 创建 | 创建 | 只加一次 |
| 暂停 | ACTIVE | 继续下载 |
| 暂停 | 暂停 | 暂停 |
| 恢复 | 暂停 | 恢复 |
| 删除 | ACTIVE | 不取消、不删文件 |
| 删除 | 删除 | 按保留期/GC 策略处理，不即时误删 |

## 五、Java/API 设计

### 5.1 新模块

创建限界上下文 backend/src/main/java/cc/ivera/ed2k/：

- interfaces/: Ed2kController、Ed2kHealthController、请求/响应 DTO、文件下载接口
- application/: Ed2kTaskService、Ed2kResourceCoordinator、Ed2kReconcileService、Ed2kFileService
- domain/: Ed2kLinkParser、Ed2kResource、Ed2kDownloadTask、Ed2kEngineAction、Mapper 接口、状态转移规则
- infrastructure/: AmuleApiClient（JDK HttpClient）、AmuleProperties、文件路径边界校验与持久层适配

SQL XML 统一放 backend/src/main/resources/mapper/Ed2k*.xml；不写 SQL 注解、不修改 MapperScan，不拆新的 Maven 子工程。

### 5.2 ClipFetch API（拟定公开合同）

| Method | 路径 | 功能 |
|---|---|---|
| GET | /api/ed2k/engine/health | 200 + configured/connected/version/message，离线不抛业务异常 |
| POST | /api/ed2k/tasks | 提交 eD2k 文件链接，幂等创建当前用户任务 |
| GET | /api/ed2k/tasks | 当前用户任务分页列表，size ≤ 30，离线降级 DB |
| GET | /api/ed2k/tasks/{taskUuid} | 当前用户任务详情与来源/速度/进度 |
| POST | /api/ed2k/tasks/{taskUuid}/pause | 仅暂停当前用户意图、执行共享态协调 |
| POST | /api/ed2k/tasks/{taskUuid}/resume | 仅恢复当前用户意图、执行共享态协调 |
| DELETE | /api/ed2k/tasks/{taskUuid} | 仅逻辑删除当前用户任务 |
| GET | /api/ed2k/tasks/{taskUuid}/files/0/content | 登录态获取已完成单文件，支持 Range 优先 |

所有端点需登录，使用 currentUserId(request)，DTO 使用 jakarta.validation；响应使用 ApiResponse.ok，错误用中文 BusinessException。乐观版本更新采用请求 expectedRevision 或明确幂等约定，防止旧操作覆盖新操作。

当 aMule 离线时：health 返回 HTTP 200；列表/详情返回 DB 最近快照并标明 stale；新建/恢复按策略返回 503 或保存待执行意图并明确排队中（选择一种且测试）；不把网络异常伪装为任务被删除。

### 5.3 链接校验和文件下载安全

- 服务端只允许规范的 ed2k file 链接；NAME URL 解码时限制编码、控制字符、长度、路径分隔符、保留设备名；SIZE 正整数且不超过可配置上限；MD4 必须 32 位 hex。拒绝换行、命令注入与可疑扩展字段；不跟随任意链接 URL。
- 完成文件读取必须由服务端以 task_uuid + user_id 查询授权；不允许用户传入宿主绝对路径。规范化后路径必须在配置的 Incoming 根内，拒绝 symlink / junction / reparse point 逃逸，避免 TOCTOU。
- 采用受控流式拷贝而不是读完整文件进内存；优先支持 Content-Length、Content-Disposition、Range / 206；避免新开未授权静态资源接口。
- 不能从文件名推断权限；aMule 原始完成目录路径、内部管理凭据不返回前端。

## 六、前端方案

建议 **新增独立 /ed2k 路由及电驴工作区**，不把它伪装成 /magnet/:engine? 的第四种 BitTorrent 引擎：

- frontend/src/api/ed2k.js：全部请求经 api/request.js、errMsg 处理；
- frontend/src/components/Ed2kDownloader.vue（或 views/Ed2kView.vue，按路由懒加载）；
- frontend/src/router/index.js 注册 /ed2k 懒加载；
- frontend/src/components/AppHeader.vue 桌面和移动菜单都添加电驴入口；
- 页面展示：单条 eD2k 链接输入、解析出的文件名/大小、创建、当前用户列表、进度/速度/源数（若官方 API 提供）、暂停/恢复/删除、下载完成文件、引擎健康；
- 删除二次确认；引擎离线有明确提示；轮询只能在已登录且引擎 ready 时开始，报错熔断，组件卸载 clearInterval；严格区分“个人暂停”与“底层资源因其他用户仍运行”。

## 七、实施任务（后续逐项落地，不在本轮执行）

### Task 0 — 容器/API 可行性门槛

**计划变更文件**：infra/amule/Dockerfile、docker-compose.yml、README.md、容器入口初始化脚本（先做最小验证，再固化）。

- [ ] 锁定官方 aMule 3.2.0 下载源、校验 SHA-256 / 镜像摘要与许可；分别验证 amd64 与目标 Linux 架构。
- [ ] 在隔离环境内验证 amuled 与 amuleapi 的启动/优雅关闭、健康探测、JWT 登录、官方 /api/v1 下载 API、暂停/恢复/取消、REST 响应字段；输出接口契约测试基线。
- [ ] 验证 Windows Docker Desktop 的 host 127.0.0.1:4713 到容器、容器内部 EC、持久卷与非 root 读写；Linux 同标准。
- [ ] 验证 3.2.0 的 allowed-remote-dirs.dat / 配置文件权限限制，确保管理 API 不对公网暴露。
- [ ] **门槛**：固定版本原生 REST 完整可用才进入 Task 1；否则记录具体阻塞，严禁无证据宣称方案就绪。

### Task 1 — eD2k 领域与链接解析（TDD）

**新增**：Ed2kLinkParser、Ed2kIdentity、Ed2kTaskState、对应 JUnit 5 单测。

- [ ] RED：正常文件链接、URL 编码、32 hex、long size、重复 hash+size、错误 hash、路径穿越、控制字符、超长名称、server/serverlist 链接拒绝。
- [ ] GREEN：纯领域解析和不可变身份对象；保留原始链接的必要字段而不泄漏敏感数据。
- [ ] 验收：无需启动 aMule，单测全部通过。

### Task 2 — MySQL/SQLite V25 + Mapper XML

**新增**：两份未占用版本的 Flyway 脚本、三张表的 Mapper 接口与 XML、集成测试。

- [ ] 先列出两数据库 migration 验证 V25 未被占用；若已被占用，选新的同版本号。
- [ ] RED：同资源并发插入唯一性、两个用户各有独立任务、CAS 冲突、user_id 串户拒绝、逻辑删除值必须等于 id。
- [ ] GREEN：XML 实现查询/写入/复用计数/action 状态变更，user_id 过滤；验证 MySQL/SQLite 方言及索引。
- [ ] 验收：两库迁移在空库可运行，既有 V24 数据不变。

### Task 3 — aMule REST 适配器与配置

**新增**：AmuleApiClient、AmuleProperties、AmuleApiClientTest；**修改** application.yml、.env.example，实际密钥只放被忽略的 backend/.env。

- [ ] RED：登录过期/刷新、401/403/429/5xx、超时、引擎离线、JSON 状态解析、hash 定位、重复添加、暂停/恢复、资源完成状态。
- [ ] GREEN：JDK HttpClient；连接/读取超时与失败分类；敏感信息脱敏；不使用线程不安全的临时共享状态。
- [ ] 所有端口、路径、超时、轮询间隔、重试次数、数据上限、健康检查开关均配置化；默认 AMULE_ENABLED=false。
- [ ] 验收：与固定版本 amuleapi 的真实 JSON 契约匹配，不依赖翻译后的 amulecmd 文本。

### Task 4 — 资源协调、持久化动作与用户任务

- [ ] RED：A/B 同 hash 并发仅一个底层任务；一方暂停/删除不影响另一方；双方暂停底层暂停；一方恢复可继续；跨用户详情/控制拒绝。
- [ ] GREEN：Ed2kTaskService + Ed2kResourceCoordinator，记录 action 与 desired_state，数据库 CAS/事务控制；HTTP 引擎调用在事务外。
- [ ] RED：HTTP 成功、DB 回写前崩溃；DB 已提交、HTTP 失败；重复回放；用户连点 pause/resume；跨进程重启。
- [ ] GREEN：幂等惰性对账与有界补偿，避免双调用和错删。
- [ ] 验收：重启 Spring Boot 后数据库意图不会反向丢失，所有权限和删除语义正确。

### Task 5 — 后端 REST 接口及授权

- [ ] 为健康、创建、分页列表、详情、暂停、恢复、删除建立 MockMvc/Controller 测试。
- [ ] 全部 user_id 限定；health 200 结构化降级；离线列表可读、操作失败可理解；参数错误中文响应。
- [ ] WebConfig 白名单维持原状；不暴露容器内 JWT、管理端口和路径。
- [ ] 验收：独立用户 API 隔离，现有 magnet/v0/v1/v2 API 无回归。

### Task 6 — 已完成文件提取与路径安全

- [ ] RED：越权、文件不存在、未完成、Range 合法/非法、符号链接/目录穿越、重命名碰撞、下载中途文件变化。
- [ ] GREEN：基于任务所有权 + 可信 aMule hash/size 映射，仅从 Incoming 受控目录读取；大文件流式响应。
- [ ] 验收：完整读取与单 Range 内容一致，不能读取其他用户文件或容器配置目录。

### Task 7 — Vue 3 UI 与客户端状态管理

- [ ] 新路由、桌面/移动入口、异步加载、健康展示、创建/控制/删除/下载，复用现有 request.js。
- [ ] Vitest：首次未连接不轮询、断连熔断、组件卸载计时器清除、状态文案、权限错误、重复点击防抖。
- [ ] 验收：npm run build 通过，移动窄屏无横向溢出，BT 页面功能不变。

### Task 8 — Windows/Linux Docker 部署与故障注入

- [ ] 独立 Compose，不更改 infra/native-v2/docker-compose.yml；检查 4713、eD2k/Kad 数据端口与宿主冲突。
- [ ] 对配置目录、Temp、Incoming 分别测试持久卷；recreate/restart、断电模拟、磁盘不足、管理 API 断连恢复。
- [ ] aMule 重启后 part/met 续传不重头；ClipFetch 重启后对账恢复；健康接口离线降级。
- [ ] 验证未授权网络无法访问 4713/4712；禁不必要的 UPnP/特权/root，凭据不在日志/Compose 明文。
- [ ] 验收：真实合法测试 eD2k 文件链路 + 可控本地测试源，最终文件 hash/size 验证；公网无可用源时标为网络环境未验收，不伪造成功。

### Task 9 — 回归、交接与发布准入

- [ ] 后端 mvn clean compile -DskipTests、mvn test，前端 npm run build；仅在实施产生代码时执行。
- [ ] 现有 qBittorrent/Native v1/Native v2 健康和任务 API 冒烟；确保 8082 唯一后端，v2 原有容器不变。
- [ ] git diff --check；新增文本 UTF-8 无 BOM；双数据库迁移版本一致；敏感文件仍被 .gitignore 忽略。
- [ ] 实际执行结果（命令、退出码、浏览器/真实 API 证据）写入 HANDOFF.md、ISSUE.md（仅出现真实新问题才写）。
- [ ] 验收标准：Task 0–9 的必要测试均通过且无剩余高风险阻塞，才可以声明电驴功能完成。

## 八、风险和技术决策门槛

| 风险 | 处理原则 |
|---|---|
| 使用过旧 aMule 版本 | 固定 3.2.0 并验证校验和/镜像来源；不接收未经审查的漂移镜像 |
| 容器没有 amuleapi 或合同不匹配 | Task 0 阻塞；不使用脆弱的 HTML/文本抓取代替 REST |
| 共用进程下用户暂停/删除互相影响 | DB 持久化 desired_state + 所有有效用户需求聚合 + 引擎动作幂等 |
| aMule 资源库可被用户绕过操作 | 管理 API 仅后端可访问，不开放给用户；不自动接管不属于 ClipFetch 的资源 |
| 文件同名/大小不一致、路径逃逸 | hash+size 身份，可信映射、受限根目录、realpath、完整性验证 |
| 网络无 Kad / eD2k 来源，速度为 0 | 区分“连通”“找到源”“下载中”，不把外网资源可用性当代码测试 |
| Windows/Linux 挂载差异 | 环境变量指定绝对路径；两平台分别验收 |
| 断网/崩溃导致 DB 与引擎状态不同步 | action journal + 启动/请求惰性幂等协调，不新增定时调度 |
| 管理 API 账号和配置泄露 | host 127.0.0.1 绑定、容器内 EC、最小权限、禁止敏感日志、凭据放受保护配置 |
| 版权及用户文件公开共享 | 仅下载有权获取的文件；与普通文件库隔离，明确 P2P 上传/共享策略 |

## 九、明确验收场景

- **隔离**：用户 A 看不到、无法控制用户 B 的任务，猜测 UUID 也无法访问文件。
- **共享**：用户 A 和 B 提交同一 MD4+size；数据库 2 条个人任务、1 条共享资源、aMule 1 项下载。
- **暂停**：A 暂停时 B 继续下载；A/B 均暂停才停止；恢复任一用户继续。
- **删除**：A 删除不影响 B；仅最后引用消失且满足保留策略才允许底层清理。
- **文件**：只在已完整校验后开放，路径、Range、大小、HTTP 返回码正确。
- **恢复**：重启后端与 aMule 均能恢复任务与下载进度，历史任务列表离线可读。
- **回归**：qBittorrent v0、Native BT v1/v2、SQLite/MySQL 双库、前端其他路由均不受影响。

## 十、退出条件与实施约束

当前成果**仅为可执行 Plan**。后续用户确认实施时，从 Task 0 开始，逐项执行并记录真实 RED/GREEN、命令、Exit Code 和未验证边界。任何未跑过的构建、自动测试、容器验收均不得报告“通过”。

后续代码变更遵循仓库 AGENTS.md：无 BOM UTF-8、SQL 全在 XML、双数据库同步迁移、用户隔离、逻辑删除、不额外引入 MQ/微服务/HTTP 客户端、不主动 commit；每轮按 HANDOFF.md 的交接规范记录事实。
