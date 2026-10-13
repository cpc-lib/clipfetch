# AGENTS.md

本文件面向在本仓库工作的 AI 编码代理。请先读完本文件再改代码；所有规则均为项目已落地的硬约定，不要自行引入替代方案。

## 1. 项目概览

ClipFetch 是多平台媒体解析/下载 Web 应用：视频与音乐解析下载、磁力下载（qBittorrent）、文件库、预解析库、字幕转换网关。单体架构，**不做微服务/多模块拆分**。

- 后端：Java 21 + Spring Boot 3.5.4，根包 `cc.ivera`，启动类 `cc.ivera.ClipFetchApplication`
- 前端：Vue 3 + Vite 6 + Tailwind CSS 3 + axios，**无 vue-router 的单页应用**
- 数据库：SQLite 默认（零安装），可切 MySQL；Flyway 管迁移
- 运行环境：Windows + PowerShell；外部进程 yt-dlp / ffmpeg / aria2c / deno / Node / Python sidecar / qBittorrent

## 2. 常用命令

```powershell
# 后端（命令行启动必须显式指定 8082，避免与其他服务冲突）
cd backend; $env:SERVER_PORT='8082'; mvn spring-boot:run
mvn -q clean compile -DskipTests          # 编译校验
mvn test                                  # 跑测试

# 前端（5173，/api 与 /ws 代理到 8082）
cd frontend; npm install; npm run dev
npm run build                             # 构建校验
```

改动后端后优先 `mvn clean compile`（IDEA 增量编译可能残留坏 class）。不要为「验证文档/配置改动」执行构建。

## 3. 目录结构

```
backend/src/main/java/cc/ivera/
├── ClipFetchApplication.java   # 启动类：加载 .env、%VAR% 展开、SQLite 建目录、按 DB_URL 切 Flyway 目录
├── shared/        # WebConfig（CORS + 认证白名单）、WebSocketConfig、ApiResponse、BusinessException、全局异常
├── auth/          # 注册/登录/刷新/JWT 拦截器
├── cookie/        # 各平台 cookies 上传、探活、失效标记
├── parser/        # 核心：解析、下载编排、yt-dlp/HLS、全部 Parser 与浏览器 sidecar、WS 进度
├── temp/          # 文件库
├── preparse/      # 预解析库
├── magnet/        # 磁力下载（qBittorrent Web API + 文件网关）
├── thirdparty/    # 第三方令牌会话
└── subtitle/      # 字幕 RAG API 透传网关
backend/src/main/resources/
├── application.yml              # 配置占位符，真实值走 backend/.env
├── db/migration/{sqlite,mysql}/ # Flyway 双库脚本（同名版本号，内容按方言写）
├── mapper/*.xml                 # MyBatis XML 映射
└── cctv|qqmusic|kugou|tencent|spankbang/  # 外部脚本（Node/Python）
frontend/src/{components,api,stores}/      # 无路由；api/ 每模块一个 js，components/ 扁平放 .vue
infra/qbt-file-gateway/{windows,linux}/    # 磁力文件跨机取流网关
```

## 4. 后端硬约定

### 分层与包

- 新业务建限界上下文包 `cc.ivera.<name>`，内部分 `interfaces / application / domain / infrastructure`。
- Mapper 接口放 `<context>.domain` 包（`@MapperScan("cc.ivera.**.domain")` 自动扫描），**无需手写 @Mapper 或改扫描配置**。
- Controller 用 `@RestController` + 类级 `@RequestMapping("/api/<name>")`，构造注入用 Lombok `@RequiredArgsConstructor`，日志用 `@Slf4j`。

### 响应与异常

- 所有接口返回 `ApiResponse.ok(data)`；错误直接 `throw new BusinessException("中文错误信息")`，由 `GlobalExceptionHandler` 统一包装为 `{success:false,error}`，**不要在 Controller 手写 try/catch 兜底或返回错误 Map**。
- 业务错误提示面向最终用户，用中文，说清原因与动作（如「qBittorrent 连接失败，请确认 Web UI 已开启」）；外部命令失败要带上游响应体。
- 参数校验用 `jakarta.validation`；分页单页上限 30。

### 认证与数据隔离

- 除白名单外全部 `/api/**` 强制登录。白名单只在 `shared/config/WebConfig.java` 维护，目前仅：`/api/auth/register|login|refresh`、`/api/wallpaper`、`/api/image-proxy`、`/api/magnet/downloads/stream/*`（短时签名票据）。**新增免登录接口前必须确认无法携带 Authorization 头（如 `<img>`/浏览器原生下载），并改用票据方案而非裸白名单。**
- Controller 内用 `currentUserId(request)` 取用户 ID；凡是用户产生的数据（temp / pre_parse / magnet_history / magnet_download_task / cookies / 会话）查询与写操作都必须带 `user_id` 条件，跨用户访问要拒绝。
- 前端 axios 对 401 会自动 refresh 并重试一次。需要让前端「清除登录态回表单」的业务失效不要返回 401——参照字幕网关返回 **428**。
- 浏览器原生下载（不带 JWT）一律走「Bearer 换短时一次性 ticket → 免登录 stream 端点」模式，ticket 绑定 用户/任务/文件，每次请求重校归属、路径与文件完成状态。

### 持久层

- **SQL 只能写在 `resources/mapper/*.xml`，禁止 `@Select/@Update/@Insert/@Delete` 注解。**
- 实体风格：`@TableName` + `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`，主键 `@TableId(type = IdType.AUTO)`，时间字段 `LocalDateTime`，敏感字段 `@JsonIgnore`。
- 删除一律**逻辑删除**：字段 `deleted` 配 `@TableLogic`，实际删除值写**记录 id**（不是 1），用自定义 XML `UPDATE t SET deleted = id WHERE id=? AND user_id=?`；配套唯一索引为 `(user_id, biz_key, deleted)`，靠 deleted=id 保证多条删除不冲突。
- 不引入 Redis / RabbitMQ / 定时任务（全项目无 `@Scheduled`、`@EnableScheduling`、cron、quartz、xxl-job）。需要「实时性」时用前端轮询时 lazy 回写或 WebSocket 推送。
- 解析结果缓存用 Caffeine，键「用户ID+链接」、TTL 5 分钟；**凡要写文件库（temp 表）的解析（歌单/搜索/专辑等）必须绕过缓存**，避免漏写入库逻辑。

### Flyway 迁移

- 任何表结构变更同时新增 `db/migration/sqlite/Vxx__xxx.sql` 与 `db/migration/mysql/Vxx__xxx.sql`，**版本号两边一致、全项目唯一**（当前已用到 V11，新脚本从 V12 起；先 `ls` 两个目录确认未被占用）。
- SQLite 与 MySQL 方言差异各自处理（如自增、索引语法）；不要改动已发布的历史脚本。

### 配置

- 所有可变参数走 `backend/.env` → `application.yml` 占位符，不要硬编码路径/端口/账号。路径配置支持相对（以启动目录为基准）与绝对路径。
- `.env`、cookies、各 sidecar 的 `*-profile/` 目录含登录态，禁止打印内容、禁止提交（已在 .gitignore）。
- 外部命令（yt-dlp、ffmpeg、node、python 等）执行前在控制台打印完整命令行，便于排障。

## 5. 前端硬约定

- 新页面 = `components/` 下新增 `.vue` + 在 `AppHeader.vue` 加导航按钮 + `App.vue` 用 `active` 状态切换（**不装路由**）。
- 所有请求走 `api/request.js` 的默认实例（自动带 token、401 自动刷新）；错误文案用 `errMsg(error)` 提取后端 `error` 字段，不要直接显示 axios 原始 message。
- 文件下载若需要鉴权，用 axios `responseType:'blob'` 拿 Blob 再触发 `<a download>`，不要把 token 拼 URL，也不要把接口加白名单。
- 样式用 Tailwind class，保持与现有组件一致的简洁明亮风格；删除/危险操作需二次确认；密码框要有明文切换眼睛图标。
- 轮询类页面（磁力解析 2s、下载进度 1s）在组件卸载时 `clearInterval`；列表筛选条件与每页条数切换后需保持。
- Vue 3 模板注意：模板中对 ref 的 Set/Map 不要直接调方法（this 绑定丢失），封装成普通函数；`v-for` 的关联兄弟元素用 `<template v-for>` 包裹共享循环变量。

## 6. 外部进程与 sidecar 约定

- 新增对外部 HTTP 服务的调用用**纯 JDK HttpClient**（参照 `QBittorrentClient`、`SubtitleGatewayService`），不引入新 HTTP 客户端依赖；Cookie 会话用 `CookieManager`。
- sidecar（Python + Playwright 驱动**系统 Chrome**）：端口、profile 目录、启用开关全部走 `.env`；profile 是 Chrome 用户数据目录，**禁止改名/移动/提交**；端口已占用：QQ音乐 8095、酷狗 8096、腾讯 8097、SpankBang 8098。
- qBittorrent 对接要点（已踩坑）：每次请求新建独立 HttpClient 并强制 HTTP/1.1（共享连接池会报 "header parser received no bytes"）；登录兼容 200/204；5.x 参数 `hashes` 与 4.x `hash` 同传；磁力种子必须启动态才能取元数据；已存在的他人种子不改保存路径、不限上传速度；新种子才设 UUID 子目录与不做种。

## 7. 新增一个业务模块的 Checklist

1. 建 `cc.ivera.<name>` 包及四层目录；实体放 domain，Mapper 接口放 domain、XML 放 resources/mapper。
2. 实体含 `id / user_id / created_at / updated_at / deleted`（需要时）；逻辑删除写自定义 XML。
3. Flyway 双库各加一个同版本号新脚本（V12 起，先查占用）。
4. Controller 走 `/api/<name>`，返回 `ApiResponse`，全部操作带 user_id；`.env` 加配置项并在 application.yml 加占位符。
5. 前端：`api/<name>.js` + `components/<Name>.vue` + 头部导航；用 request.js 与 errMsg。
6. `mvn clean compile` + `npm run build` 双通过；更新 README 受影响章节。

## 8. 禁止事项

- 禁止引入 Redis / MQ / 定时调度 / 微服务组件 / 新 HTTP 客户端 / 前端路由或 UI 框架。
- 禁止用 MyBatis 注解写 SQL、用物理删除、跨用户漏加 `user_id` 过滤。
- 禁止把需要鉴权的下载接口直接加白名单；禁止在 URL 里放 JWT。
- 禁止改动已发布的 Flyway 脚本、复用已占用版本号、只写一个数据库的迁移。
- 禁止在代码里硬编码绝对路径/账号/密码；禁止日志输出 cookies、token、`.env` 密钥。
- **所有文本文件必须为无 BOM 的 UTF-8**（Java/JS/Vue/XML/yml/sql/md/ps1/conf 等全部适用），禁止写入 UTF-8/UTF-16 BOM（`EF BB BF` / `FF FE` / `FE FF`）。在 Windows PowerShell 5.1 下写文件不要用 `Set-Content/Out-File -Encoding UTF8`（会加 BOM），改用 `[System.IO.File]::WriteAllText($p,$text,[System.Text.UTF8Encoding]::new($false))`；PowerShell 7 用 `-Encoding utf8NoBOM`。新建文件后可用 `Format-Hex` 检查首三字节。
- 禁止顺手重构与任务无关的代码、改动相邻格式、删除既有死代码（可提示，不擅动）。
- 不主动创建 commit；不主动写文档（README/AGENTS 仅在被要求时改）。

## 9. 本地端口事实

| 端口 | 用途 |
|---|---|
| 5173 | Vite 前端 dev（代理 /api、/ws → 8082） |
| 8082 | 后端（命令行需 `$env:SERVER_PORT='8082'`） |
| 8095–8098 | QQ音乐 / 酷狗 / 腾讯 / SpankBang sidecar |
| 8080 / 8085 等 | 外部第三方服务（字幕 RAG 等），按 `.env` 实际配置为准 |
| 18766 | qBittorrent 文件网关 |

改端口前先核对本表与 `.env`，避免互踩。
