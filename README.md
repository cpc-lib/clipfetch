# ClipFetch

一站式多平台视频解析与下载 Web 应用：粘贴链接即可解析 YouTube / 抖音 / Twitter / TikTok / Bilibili / Instagram / CCTV / 腾讯 / 优酷 / 爱奇艺 / 芒果TV 等平台的视频与图文，支持服务端代理下载、无水印下载、多连接加速、实时下载进度。

## 功能特性

- **多平台解析**：YouTube、抖音、Twitter、TikTok、Bilibili、Instagram（视频 / 图文 / 轮播）、CCTV、BBC、CGTN、Amasian TV、Tubi、Xvideos、Pornhub、Missav、SpankBang
- **VIP 视频解析**：腾讯 / 优酷 / 爱奇艺 / 芒果TV 走 VipParser（解析站官方源优先，避免第三方中转水印）；腾讯另有纯 Java getinfo/getkey 直解通道（TencentParser）
- **两种下载方式**：可直连的直链浏览器下载；被墙 CDN / 需合并的格式走服务端代理下载
- **无水印下载**：抖音无水印直链优先，失败自动回退 yt-dlp
- **下载加速**：aria2c 多连接分片（默认 16 连接），ffmpeg 自动合并音视频
- **实时下载进度**：WebSocket 推送 yt-dlp / aria2c / 直链流的下载进度与速度
- **用户 Cookies 管理**：五平台 cookies 登录后上传入库，上传即校验，失效自动标记并引导更新；抖音必须配置 cookies 后使用
- **CCTV h5e 解密**：Node.js + Playwright-core 注入浏览器 WASM 批量解密 TS 段，速度约 20x 实时播放；打包运行自动解压脚本
- **Instagram 轮播展示**：视频 + 图片混合轮播分两栏预览，支持 ZIP 打包下载
- **YouTube 多语言字幕下载**：解析列出全部字幕轨道（中文优先），三级兜底「Invidious 镜像 → 云端转录服务 → yt-dlp(cookies)」

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 21、Spring Boot 3.5.x（Web / WebSocket / Validation）、MyBatis-Plus、JJWT、BCrypt |
| 数据库 | SQLite（默认，零依赖）/ MySQL 可选，Flyway 统一迁移（`db/migration/sqlite|mysql`） |
| 下载引擎 | yt-dlp（外部进程）、aria2c（可选加速）、ffmpeg（合并）、deno（YouTube PO Token）、Node.js + Playwright-core（CCTV 解密） |
| 前端 | Vue 3 + Vite + Tailwind CSS + axios，无路由单页 |
| 部署 | 后端单 jar（`app.jar`）+ 前端 Nginx 托管，详见 [docs/部署运行指南.md](docs/部署运行指南.md) |

## 架构说明

```
┌─────────────┐   /api/**、/ws/**    ┌──────────────────────────────────┐
│  Vue3 SPA   │ ───────────────────▶ │        Spring Boot 后端           │
│ (Nginx/5173)│ ◀─────────────────── │  REST + WebSocket                 │
└─────────────┘                      └───────┬──────────────────────────┘
                                             │
                    ┌────────────────────────┼─────────────────────────┐
                    ▼                        ▼                         ▼
              SQLite/MySQL              yt-dlp 进程              外部 HTTP/代理
              用户/Cookie           (aria2c/ffmpeg/deno)      IG/腾讯/VIP 解析、
                                                              CDN 直链流式转发
```

后端按限界上下文分包，每个上下文内部分 `interfaces / application / domain / infrastructure` 四层：

```
com.fvd
├── VideoDownloaderApplication        # 启动类（.env 加载 %VAR% 展开、SQLite 建目录、Flyway 目录切换）
├── shared
│   ├── config        # WebConfig（CORS/拦截器）、WebSocketConfig
│   └── web           # ApiResponse 统一响应、BusinessException、全局异常处理
├── auth              # 账号
│   ├── interfaces    # AuthController（注册/登录/刷新/登出/me）、AuthInterceptor（JWT）
│   ├── application   # JwtService
│   └── domain        # User、RefreshToken + Mapper
├── cookie            # 用户 Cookies 管理
│   ├── interfaces    # CookieController（状态查询/上传/删除）
│   ├── application   # CookieService：Netscape 解析校验、探活、临时文件、失效标记
│   └── domain        # UserCookie（user_id+platform 唯一）
└── video             # 解析与下载（核心上下文）
    ├── interfaces    # VideoController：/parse、/direct-url、/download、/download-subtitle、/wallpaper
    ├── application   # DownloadService：yt-dlp 进程编排、进度解析、直链流式转发、临时目录清理
    ├── domain        # Platform、VideoInfo、FormatInfo、MediaItem
    └── infrastructure# YtDlpService（命令构建/JSON 解析）、各平台 Parser、
                      # YouTubeMirrorService（风控兜底）、CctvNodeDecryptSidecar（h5e 解密）、
                      # HlsClient（m3u8 分片下载+ffmpeg 合并）、DownloadProgressHandler（WS 推送）
```

## 各平台与外部工具对应关系

| 工具 | 用途 | 覆盖平台 |
|------|------|---------|
| yt-dlp | 解析/下载主管 | YouTube、Twitter、TikTok、抖音、Pornhub、Missav、SpankBang、Tubi、Xvideos（下载）、AmasianTV（字幕） |
| ffmpeg | ① yt-dlp 音视频合并 ② HLS 分片合并（HlsClient）③ Instagram 首帧封面 | 全部走 yt-dlp 的平台 + CCTV、BBC、CGTN、AmasianTV、Xvideos |
| aria2c | yt-dlp 外部下载器，仅加速渐进式 MP4 直链 | 抖音、Pornhub、YouTube 渐进式流等 |
| deno | YouTube BotGuard 挑战 / PO token | 仅 YouTube |
| Node.js | CCTV h5e 批量解密 sidecar | 仅 CCTV |
| 纯 Java | getinfo/getkey + 直链转发，无外部工具 | TencentParser、VipParser（Playwright 抓流） |

## 主要流程

### 1. 解析与下载（核心链路）

```
用户粘贴链接
   │  POST /api/parse {url}
   ▼
VideoController ──按域名路由──▶ 各平台 Parser（纯 Java HTTP/页面内嵌 JSON）
   │                              或 YtDlpService.dumpInfo（yt-dlp -J）
   ▼
返回 VideoInfo（标题/封面/格式列表/轮播 media 明细）
   │
   │  ├─ 单流可直连 ─▶ POST /api/direct-url ─▶ 浏览器直接下载
   │  └─ serverOnly / 需合并 ─▶ POST /api/download
   ▼
DownloadService：yt-dlp 下载到临时目录（aria2c 加速、ffmpeg 合并）
  → 流式回写浏览器 → finally 清理临时目录与 cookies 临时文件
```

- 解析与下载均为「可选认证」：携带 token 则注入当前用户；抖音必须登录+cookies
- 使用用户 cookies 下载报鉴权类错误时，自动把该用户对应平台 cookie 标记为失效

### 2. 下载进度实时推送（WebSocket）

```
前端生成 taskId ──▶ 建立 ws://…/ws/download-progress?taskId=xxx
        └─▶ POST /api/download {…, taskId}
后端：逐行解析 yt-dlp --progress-template / aria2c 进度行 / 直链流字节计数
      ──▶ 节流推送 {type:progress|done|error}
```

### 3. YouTube 字幕解析与下载（三级兜底）

2026 反爬机制下出口 IP 被标记后 yt-dlp 全部 player client 均返回 LOGIN_REQUIRED，核心兜底实现见 `YouTubeMirrorService`：

```
① Invidious 镜像字幕内容
        ↓ 失败
② 云端转录服务 youtube-transcript.ai（与本机 IP 无关、免认证，需代理）
   [m:ss] 文本 markdown → 转标准 WebVTT（校验 Language 响应头防静默回退英语）
        ↓ 失败
③ yt-dlp 字幕轨道直链（需用户 cookies 或干净节点）
```

- 解析结果 `subtitles` 数组人工+自动合并去重、简体中文优先，前端下拉框选语言
- 镜像/云端请求先直连，失败且配置了 `PROXY_URL` 时自动经代理重试

## 快速开始（开发）

### 环境依赖

- JDK 21+、Maven 3.9+
- Node.js 18+（前端 + CCTV 解密）
- [yt-dlp](https://github.com/yt-dlp/yt-dlp/releases)（必需）、[ffmpeg](https://ffmpeg.org/download.html)（合并必需）
- 可选：[aria2c](https://github.com/aria2/aria2/releases)（多连接加速）、[deno](https://github.com/denoland/deno/releases)（YouTube 反爬）、出站代理

```bash
# 后端（SQLite 默认零依赖，首次启动 Flyway 自动建表）
cd backend
mvn spring-boot:run

# 前端
cd frontend
npm install
npm run dev                  # 5173，/api 与 /ws 代理到 8081
```

### CCTV 解密依赖（首次使用必须）

```bash
cd backend/src/main/resources/cctv
npm install
# 浏览器缓存缺失时执行：npx playwright-core install chromium
```

### VipParser 依赖（腾讯/优酷/爱奇艺/芒果 VIP）

```bash
cd backend
mvn exec:java "-Dexec.mainClass=com.microsoft.playwright.CLI" "-Dexec.args=install chromium"
```

## 部署

生产部署（单 jar + Nginx + 环境变量脚本 install.bat/start.bat）详见 **[docs/部署运行指南.md](docs/部署运行指南.md)**。

## API 一览

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| POST | `/api/auth/register` / `login` / `refresh` / `logout` | - | 注册、登录（JWT）、刷新、登出 |
| GET | `/api/auth/me` | 需要 | 当前用户信息 |
| POST | `/api/parse` | 可选 | 解析视频信息（抖音需登录+cookies） |
| POST | `/api/direct-url` | 可选 | 获取直链（直链下载模式） |
| POST | `/api/download` | 可选 | 服务端代理下载（`taskId` 用于进度推送） |
| POST | `/api/download-subtitle` | 可选 | 独立字幕下载（Amasian TV / YouTube，返回 `.vtt`） |
| GET | `/api/wallpaper` | - | 随机 Bing 每日壁纸（解析结果默认封面） |
| GET | `/api/cookies` | 需要 | 各平台 cookies 状态 |
| POST/DELETE | `/api/cookies/{platform}` | 需要 | 上传（multipart `file`）/ 删除 cookies |
| WS | `/ws/download-progress?taskId=` | - | 下载进度推送（无需认证） |

统一响应：`{ "success": true, "data": … }` / `{ "success": false, "error": "…" }`。

## 配置项（.env）

`.env` 优先级高于 `application.yml`；工具路径支持 `%VAR%` 引用系统环境变量（由 install.bat 写入）。

| 变量 | 说明 |
|---|---|
| `DB_URL` / `DB_DRIVER` / `DB_USER` / `DB_PASSWORD` | 数据库连接，默认嵌入式 SQLite `jdbc:sqlite:data/fvd.db`，改 MySQL 取消注释即可（Flyway 自动建表） |
| `JWT_SECRET`、`JWT_ACCESS_EXPIRE_MINUTES`、`JWT_REFRESH_EXPIRE_DAYS` | JWT 密钥与有效期（生产务必改密钥） |
| `SERVER_PORT` | 后端端口（默认 8080） |
| `YTDLP_PATH` / `FFMPEG_LOCATION` / `ARIA2C_PATH` / `JS_RUNTIME_PATH` | 各工具路径（deno 对应 JS_RUNTIME_PATH） |
| `ARIA2C_CONNECTIONS` | aria2 单服务器连接数（默认 16） |
| `PROXY_URL` | 出站代理（如 `http://127.0.0.1:10808`），被墙平台必需 |
| `YOUTUBE_MIRROR_APIS` | YouTube 兜底镜像实例（Invidious，逗号分隔），留空用内置默认 |
| `DOWNLOADS_DIR` / `LOGS_DIR` / `PARSE_TIMEOUT` | 临时下载目录 / 日志目录（logback 按天滚动） / 解析超时秒数 |
| `NODE_EXE` | Node.js 可执行文件路径（CCTV 解密必需），默认 `node` 走 PATH |
| `CCTV_DECRYPT_SCRIPT` / `CCTV_DECRYPT_TIMEOUT_MS` | CCTV 解密脚本路径（jar 运行自动解压，一般无需配置）/ 解密超时 |

## 安全注意事项

- `.env`、cookies 文件均已列入 `.gitignore`，严禁提交（cookies 含登录态）
- 生产部署请更换 `JWT_SECRET`、限制 CORS 来源
