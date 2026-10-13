# ClipFetch

一站式多平台媒体解析与下载 Web 应用：粘贴链接即可解析 YouTube / 抖音 / Twitter / TikTok / Bilibili / Instagram / 央视 / BBC / CGTN / 腾讯 / 优酷 / 爱奇艺 / 芒果TV / NetMirror 等视频图文，以及 QQ 音乐 / 网易云 / 酷狗音乐；支持服务端代理下载、无水印下载、多连接加速、WebSocket 实时进度；内置磁力下载（qBittorrent）、文件库、预解析库与字幕转换工作台（对接第三方字幕服务完成 SRT / VTT / ASS 上传、LLM 翻译、在线校对与下载）。

## 功能特性

- **多平台视频解析**：YouTube、抖音、Twitter/X、TikTok、Bilibili、Instagram（视频 / 图文 / 轮播）、央视网、BBC、CGTN、Amasian TV、Tubi、微博、小红书、NetMirror（多剧集 / 多清晰度）
- **VIP 视频解析**：腾讯 / 优酷 / 爱奇艺 / 芒果TV 走 VipParser（解析站官方源优先，避免第三方中转水印；腾讯走 TencentBrowserSidecar）
- **音乐解析与下载**：QQ 音乐、网易云音乐、酷狗音乐，歌单 / 专辑 / 搜索结果自动入库「文件库」，FLAC/320k 等品质按平台权益返回
- **磁力下载**：qBittorrent Web API 驱动，磁力链接元数据解析、按文件勾选下载、UUID 独立目录、任务暂停 / 恢复 / 删除，下载完成后经文件网关在浏览器取文件
- **两种下载方式**：可直连的直链浏览器下载；被墙 CDN / 需合并的格式走服务端代理下载
- **无水印下载**：抖音无水印直链优先，失败自动回退 yt-dlp
- **下载加速**：aria2c 多连接分片（默认 16 连接），ffmpeg 自动合并音视频
- **实时下载进度**：WebSocket 推送 yt-dlp / aria2c / 直链流的下载进度与速度
- **文件库 / 预解析库**：音乐与解析结果按用户隔离入库，支持分页（单页上限 30）、搜索、重命名、逻辑删除
- **用户 Cookies 管理**：各平台 cookies 上传入库，上传即校验，失效自动标记并引导更新；抖音必须配置 cookies 后使用
- **音乐平台扫码登录**：QQ 音乐 / 酷狗 / 腾讯视频通过本地 Playwright sidecar 拉起系统 Chrome 扫码，登录态持久化在独立 profile 目录
- **央视 h5e 解密**：Node.js + Playwright-core 注入浏览器 WASM 批量解密 TS 段，NAL 单元逐级对齐重试，零掉帧
- **Instagram 轮播展示**：视频 + 图片混合轮播分两栏预览，支持 ZIP 打包下载
- **YouTube 多语言字幕下载**：解析列出全部字幕轨道（中文优先），三级兜底「Invidious 镜像 → 云端转录服务 → yt-dlp(cookies)」
- **字幕转换工作台**：用户用自己的第三方字幕服务账号（用户名/密码/租户编码）连接，上传字幕自动 SHA-256 秒传去重，选择目标语言 LLM 翻译，双栏在线校对后下载 SRT；ClipFetch 仅做登录态网关，字幕数据不本地落库

## 技术栈

| 层 | 技术 | 备注 |
|---|---|---|
| 后端 | Java 21、Spring Boot 3.5.4、MyBatis-Plus 3.5.7、JJWT 0.12.6、BCrypt、Caffeine、Playwright Java 1.63 | Web / WebSocket / Validation starter；仅用 spring-security-crypto 做 BCrypt，不启用 Security 过滤链；Caffeine 做 5 分钟解析结果缓存 |
| 数据库 | SQLite（默认，零依赖）/ MySQL 可选 | Flyway 统一建表迁移，脚本按 `db/migration/sqlite` 与 `db/migration/mysql` 两个目录分别维护，当前版本 V1–V11 |
| 下载引擎 | yt-dlp、aria2c、ffmpeg、deno、Node.js + Playwright-core、Python + Playwright、qBittorrent Web API | 各工具用途见下方「各平台与外部工具对应关系」表；aria2c / deno 可选；qBittorrent 仅服务磁力下载 |
| 前端 | Vue 3.5 + Vite 6 + Tailwind CSS 3 + axios | 无路由单页；开发端口 5173，`/api` 与 `/ws` 代理到后端 8082 |
| 部署 | 后端单 jar（`app.jar`）+ 前端 Nginx 托管 + Windows 批处理（install / start / uninstall） | 跨机取磁力文件走独立 HMAC 文件网关 |

## 架构说明

```mermaid
flowchart TB
    FE["Vue3 SPA<br/>(Nginx / 5173)"] <-->|"/api/** · /ws/**"| BE["Spring Boot 后端<br/>REST + WebSocket"]
    BE --> DB["SQLite / MySQL<br/>用户 · Cookie · 文件库 · 磁力任务"]
    BE --> TOOLS["外部下载工具<br/>yt-dlp / aria2c / ffmpeg / deno<br/>Node 央视解密"]
    BE --> QBT["qBittorrent Web API<br/>磁力元数据 / 下载"]
    BE --> SIDECAR["浏览器 sidecar<br/>QQ音乐 / 酷狗 / 腾讯（系统 Chrome）"]
    QBT --> GW["文件网关（HMAC + Range）<br/>qBittorrent 主机文件 → 浏览器"]
```

后端根包 `cc.ivera`，按限界上下文拆分一级包（每个包内部再按 interfaces / application / domain / infrastructure 分层）：

```
cc.ivera/
├── ClipFetchApplication.java
├── shared/
├── auth/
├── cookie/
├── parser/
├── temp/
├── preparse/
├── magnet/
├── thirdparty/
└── subtitle/
```

## 各平台与外部工具对应关系

| 工具 | 用途 | 覆盖平台 |
|------|------|---------|
| yt-dlp | 解析/下载主管 | YouTube、Twitter、TikTok、抖音、Tubi、AmasianTV（字幕）等 |
| ffmpeg | ① yt-dlp 音视频合并 ② HLS 分片合并 ③ Instagram 首帧封面 | 全部走 yt-dlp 的平台 + 央视、BBC、CGTN、AmasianTV |
| aria2c | yt-dlp 外部下载器，多连接加速渐进式直链 | 抖音、YouTube 渐进式流、NetMirror 等 |
| deno | YouTube BotGuard 挑战 / PO Token | 仅 YouTube |
| Node.js + Playwright | 央视 h5e 批量解密 sidecar | 仅央视网 |
| Python + Playwright sidecar | 真实系统 Chrome 页面签名 / 登录态提取 | QQ音乐（8095）、酷狗（8096）、腾讯视频（8097） |
| 纯 Java 自研 | 逆向站点签名、剧集/清晰度解析、VIP 解析站抓流 | NetMirror、VipParser（腾讯/优酷/爱奇艺/芒果）、网易云/酷狗部分接口 |
| qBittorrent Web API | 磁力元数据与 BT 下载 | 磁力链接 |

## 主要流程

### 1. 解析与下载（核心链路）

```mermaid
flowchart TD
    A["用户粘贴链接 · POST /api/parse"] --> B["ParserRegistry 按域名路由"]
    B --> C["平台自研 Parser<br/>纯 Java HTTP / 页面内嵌 JSON"]
    B --> D["YtDlpService.dumpInfo（yt-dlp -J）"]
    C --> E["返回 VideoInfo<br/>标题 / 封面 / 格式列表 / 轮播明细"]
    D --> E
    E --> F{"下载方式"}
    F -->|"单流可直连"| G["POST /api/direct-url → 浏览器直接下载"]
    F -->|"serverOnly / 需合并"| H["POST /api/download"]
    H --> I["yt-dlp 下载至临时目录<br/>aria2c 加速 · ffmpeg 合并"]
    I --> J["流式回写浏览器 → finally 清理临时目录"]
```

- 解析结果经 Caffeine 按「用户 ID + 链接」缓存 5 分钟；音乐歌单/搜索/专辑等需写入文件库的解析绕过缓存
- 除登录/注册/刷新及少数由 HTML 标签发起的接口外，全部 `/api/**` 强制登录；音乐、文件库、磁力数据均按 `user_id` 隔离
- 使用用户 cookies 下载报鉴权类错误时，自动把该用户对应平台 cookie 标记为失效

### 2. 下载进度实时推送（WebSocket）

```mermaid
sequenceDiagram
    participant FE as 前端
    participant BE as 后端
    FE->>FE: 生成 taskId
    FE->>BE: 建立 WebSocket：/ws/download-progress?taskId=xxx
    FE->>BE: POST /api/download（携带 taskId）
    BE->>BE: 逐行解析 yt-dlp / aria2c 进度行<br/>或直链流字节计数
    BE-->>FE: 节流推送 progress / done / error
```

### 3. 磁力下载（qBittorrent）

```mermaid
flowchart TD
    A["POST /api/magnet/parse<br/>提取 btih Info Hash，同用户同 hash 去重"] --> B["异步 addTorrent（启动态）<br/>必须启动才能通过 DHT/Tracker 取元数据"]
    B --> C["轮询 has_metadata（默认 60s）"]
    C --> D["取文件清单 → 暂停种子<br/>持久化解析历史与 .torrent"]
    D --> E["前端勾选文件 · POST /api/magnet/downloads"]
    E --> F{"种子是否已存在"}
    F -->|"新种子"| G["保存到 QBT_SAVEPATH/uuid<br/>未选文件优先级 0 · 设置不做种后启动"]
    F -->|"已存在"| H["只改文件优先级并启动<br/>不改路径/上传限速，避免破坏他人做种"]
    G --> I["qBittorrent 下载<br/>GET /api/magnet/downloads 轮询实时进度"]
    H --> I
    I --> J["POST /file-ticket（Bearer 认证换短时票据）"]
    J --> K["GET /downloads/stream/ticket<br/>票据即凭证 · 免 JWT · 支持 Range"]
    K --> L["RemoteTorrentFileGateway<br/>HMAC 签名 → qBittorrent 主机文件网关取流"]
```

- qBittorrent 为下载状态唯一事实来源（lazy sync）；删除任务默认保留服务器文件，勾选「同时删除文件」才通知 qBittorrent 删数据
- 不设定时任务：元数据轮询封闭在请求线程/异步池内，进度靠前端轮询时按需回写

### 4. YouTube 字幕三级兜底

出口 IP 被风控时 yt-dlp 各 player client 均可能返回 LOGIN_REQUIRED：

```mermaid
flowchart TD
    A["请求字幕"] --> B["① Invidious 镜像字幕直链"]
    B -->|失败| C["② 云端转录服务 youtube-transcript.ai<br/>markdown → 标准 WebVTT，校验语言防静默回退"]
    C -->|失败| D["③ yt-dlp 字幕轨道直链<br/>需用户 cookies 或干净节点"]
```

镜像/云端请求先直连，失败且配置了 `PROXY_URL` 时自动经代理重试。

### 5. 字幕转换（第三方登录态网关）

ClipFetch 不保存第三方账号密码，也不在本地落字幕数据：

```mermaid
flowchart TD
    A["用户填写第三方账号<br/>用户名 / 密码 / 租户编码"] -->|"POST /api/subtitles/session/connect"| B["SubtitleGatewayService<br/>登录 RAG 换取 JWT"]
    B --> C["令牌存入 third_party_session<br/>user_id + platform 唯一，账号密码不落库"]
    C --> D["上传 / 列表 / 翻译 / 编辑 / 下载 / 删除<br/>令牌透传 RAG，data 原样回传"]
    D --> E{"RAG 返回 401？"}
    E -->|"是"| F["删除会话 · 返回 428<br/>前端自动回到连接表单"]
    E -->|"否"| G["正常返回；上传附 SHA-256 供租户内秒传去重"]
```

翻译为 LLM 长耗时操作，网关超时 10 分钟；上传 5 分钟，其余 2 分钟。

## 快速开始（开发）

### 环境依赖

- JDK 21+、Maven 3.9+
- Node.js 18+（前端构建 + 央视解密）
- Python 3.10+（QQ 音乐 / 酷狗 / 腾讯 sidecar）：`pip install playwright`（复用系统 Chrome，无需下载浏览器内核）
- [yt-dlp](https://github.com/yt-dlp/yt-dlp/releases)（必需）、[ffmpeg](https://ffmpeg.org/download.html)（合并必需）
- 可选：[aria2c](https://github.com/aria2/aria2/releases)（多连接加速）、[deno](https://github.com/denoland/deno/releases)（YouTube 反爬）、出站代理、qBittorrent（磁力下载）

```bash
# 后端（默认端口 8082；SQLite 零依赖，首次启动 Flyway 自动建表）
cd backend
mvn spring-boot:run

# 前端
cd frontend
npm install
npm run dev                  # 5173，/api 与 /ws 代理到 8082
```

配置文件：开发期读取 `backend/.env`（同时兼容启动目录 `.env` 与上级目录 `.env`）；根目录提供 `.env.example` 模板。`.env` 优先级高于 `application.yml`，工具路径支持 `%VAR%` 引用系统环境变量。

### 央视解密依赖（首次使用必须）

```bash
cd backend/src/main/resources/cctv
npm install
npx playwright install chromium
```

### VIP 解析依赖（腾讯/优酷/爱奇艺/芒果）

```bash
cd backend
mvn exec:java "-Dexec.mainClass=com.microsoft.playwright.CLI" "-Dexec.args=install chromium"
```

### 音乐 sidecar 的 profile 目录（请勿改名/移动）

QQ 音乐 / 酷狗 / 腾讯首次解析时自动拉起 Python sidecar，在「我的 Cookies」弹窗内扫码登录，登录态持久化在独立 profile 目录（如 `D:/data/qqmusic-profile`，可经 `.env` 配置）。该目录是 Chrome 用户数据目录（含登录 cookie），其中所有文件名由 Chromium 固定，**改名或移动会导致登录态立即失效**；请把整个目录当作黑盒，且不要提交到版本库。

VIP 专属或需单独购买的歌曲，无对应会员账号时服务端返回「无播放权益」，属平台版权限制。

### 磁力下载依赖

1. 安装并启动 qBittorrent，在「选项 → Web UI」中启用 Web UI（建议仅监听 `127.0.0.1` 或内网）
2. 在 `backend/.env` 配置 `qbt-host` / `qbt-user` / `qbt-pass` / `QBT_SAVEPATH`（Docker 部署时填容器内路径）
3. qBittorrent 与后端不在同一台机器时，在 qBittorrent 主机部署文件网关（见 [infra/qbt-file-gateway/windows/README.md](infra/qbt-file-gateway/windows/README.md) 或 [linux 版](infra/qbt-file-gateway/linux/)），并配置 `QBT_FILE_GATEWAY_URL` / `QBT_FILE_GATEWAY_SECRET`

## 部署

- **Windows 单机**：`install.bat` 写入工具环境变量 → 将 `backend/target/app.jar` 放到项目根目录 → `start.bat` 启动（从根目录启动以保证相对路径一致）；`uninstall.bat` 清理环境变量
- 前端执行 `npm run build` 后由 Nginx 托管静态产物，`/api/` 与 `/ws` 反代到后端；长耗时接口建议 `proxy_read_timeout 3600s`、关闭 `proxy_buffering`
- **跨机磁力文件下载**：qBittorrent 主机部署独立 HTTP 文件网关（HMAC-SHA256 鉴权 + IP 白名单 + Range，支持超 2 GiB 文件），部署说明见 [infra/qbt-file-gateway](infra/qbt-file-gateway/windows/README.md)

## API 一览

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| POST | `/api/auth/register` / `login` / `refresh` / `logout` | - | 注册、登录（JWT）、刷新、登出 |
| GET | `/api/auth/me` | 需要 | 当前用户信息 |
| POST | `/api/parse` | 需要 | 解析视频/音乐链接 |
| POST | `/api/direct-url` | 需要 | 获取直链（直链下载模式） |
| POST | `/api/download` | 需要 | 服务端代理下载（`taskId` 用于进度推送） |
| POST | `/api/download-subtitle` | 需要 | 独立字幕下载（Amasian TV / YouTube，返回 `.vtt`） |
| GET | `/api/wallpaper` | - | 随机 Bing 每日壁纸（解析结果默认封面） |
| GET | `/api/image-proxy` | - | 图片防盗链代理（如微博图床） |
| GET/POST/DELETE | `/api/cookies`、`/api/cookies/{platform}` | 需要 | cookies 状态 / 上传 / 删除 |
| POST | `/api/qqmusic/login`、`/api/kugou/login`、`/api/tencent/login` | 需要 | sidecar 扫码登录触发 |
| GET | `/api/temp/list`、POST `/api/temp/delete` | 需要 | 文件库分页 / 删除 |
| GET/POST | `/api/pre-parse/list`、`/add`、`/delete`、`/rename`、`/mark-parsed` | 需要 | 预解析库管理 |
| POST/GET | `/api/magnet/parse`、`/api/magnet/parse/{id}` | 需要 | 提交磁力解析（异步）/ 轮询解析结果 |
| GET/DELETE | `/api/magnet/history`、`/api/magnet/history/{id}` | 需要 | 解析历史分页 / 逻辑删除 |
| POST/GET | `/api/magnet/downloads` | 需要 | 创建下载任务（勾选文件）/ 任务进度列表 |
| POST/DELETE | `/api/magnet/downloads/{hash}/start`、`/pause`、`/api/magnet/downloads/{hash}` | 需要 | 开始 / 暂停 / 删除（`deleteFiles` 可选） |
| GET/POST | `/api/magnet/downloads/{hash}/files`、`/file-ticket` | 需要 | 已完成文件清单 / 换取短时下载票据 |
| GET | `/api/magnet/downloads/stream/{ticket}` | 票据 | 浏览器原生下载（免 JWT，支持 Range） |
| GET | `/api/magnet/engine/health` | 需要 | qBittorrent Web API 版本与保存路径 |
| GET/POST/DELETE | `/api/subtitles/session(/connect)` | 需要 | 第三方字幕服务连接管理 |
| GET/POST/PUT/DELETE | `/api/subtitles/**` | 需要 | 字幕上传 / 历史 / 详情 / 翻译 / 校对 / 删除 / 下载 |
| WS | `/ws/download-progress?taskId=` | - | 下载进度推送 |

统一响应：`{ "success": true, "data": … }` / `{ "success": false, "error": "…" }`。

## 配置项（.env）

| 变量 | 说明 |
|---|---|
| `DB_URL` / `DB_DRIVER` / `DB_USER` / `DB_PASSWORD` | 默认嵌入式 SQLite `jdbc:sqlite:data/fvd.db`（支持相对/绝对路径，目录自动创建）；取消注释 MySQL 段即可切换 |
| `DB_POOL_MAX` / `DB_POOL_MIN` | 连接池大小（SQLite 保持默认，MySQL 建议调大） |
| `JWT_SECRET`、`JWT_ACCESS_EXPIRE_MINUTES`、`JWT_REFRESH_EXPIRE_DAYS` | JWT 密钥与有效期（生产务必改密钥） |
| `SERVER_PORT` | 后端端口（默认 8082） |
| `YTDLP_PATH` / `FFMPEG_LOCATION` / `ARIA2C_PATH` / `JS_RUNTIME_PATH` | yt-dlp / ffmpeg（目录或 exe）/ aria2c / deno 路径 |
| `ARIA2C_CONNECTIONS` | aria2 单服务器连接数（默认 16） |
| `PROXY_URL` | 出站代理（如 `http://127.0.0.1:10808`），被墙平台必需 |
| `YOUTUBE_MIRROR_APIS` | YouTube 兜底 Invidious 镜像（逗号分隔），留空用内置默认 |
| `DOWNLOADS_DIR` / `LOGS_DIR` / `PARSE_TIMEOUT` | 临时下载目录 / 日志目录（按天滚动）/ 解析超时秒数 |
| `NODE_EXE` / `CCTV_DECRYPT_SCRIPT` / `CCTV_DECRYPT_TIMEOUT_MS` | 央视解密 Node 运行时、脚本、超时 |
| `PYTHON_EXE` | sidecar 使用的 Python（默认 `python`） |
| `QQMUSIC_SIDECAR_PORT` / `QQMUSIC_PROFILE_DIR` | QQ 音乐 sidecar 端口（8095）/ Chrome profile 目录 |
| `KUGOU_SIDECAR_PORT` / `KUGOU_PROFILE_DIR` | 酷狗 sidecar（8096）/ profile 目录 |
| `TENCENT_SIDECAR_PORT` / `TENCENT_PROFILE_DIR` | 腾讯视频 sidecar（8097）/ profile 目录 |
| `SUBTITLE_API_BASE_URL` | 第三方字幕转换服务（RAG API）地址；用户在页面上自行连接，无需在此配置账号 |
| `qbt-host` / `qbt-user` / `qbt-pass` | qBittorrent Web UI 地址与账号（端口避开 8082 后端与字幕 RAG） |
| `QBT_SAVEPATH` / `metadata-timeout` | qBittorrent 保存根目录（Docker 内填容器路径）/ 元数据超时秒数（默认 60） |
| `QBT_DOWNLOADS_HOST_DIR` | qBittorrent 下载目录在后端主机上的对应路径（同机部署时使用） |
| `QBT_FILE_GATEWAY_URL` / `QBT_FILE_GATEWAY_SECRET` / `QBT_FILE_GATEWAY_ALLOW_HTTP` | 跨机文件网关地址 / HMAC 共享密钥 / 是否允许 HTTP（仅本机测试可 true） |

## 安全注意事项

- `.env`、cookies 文件、各 sidecar 的 profile 目录均已列入 `.gitignore`，严禁提交（含登录态与密钥）
- 生产部署请更换 `JWT_SECRET` 与文件网关 `QBT_FILE_GATEWAY_SECRET`（32 字符以上随机串）、限制 CORS 来源
- 磁力浏览器下载走「短时一次性票据」而非 URL 带 JWT：票据绑定用户 / 任务 / 文件，每次请求重新校验任务归属、保存路径与文件完成状态；文件网关端口不要对整个公网开放，跨机部署务必配置 IP 白名单
- qBittorrent Web UI 建议仅监听 `127.0.0.1` 或内网，不对公网开放
