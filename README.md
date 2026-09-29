# ClipFetch

一站式多平台视频解析与下载 Web 应用：粘贴链接即可解析 YouTube / 抖音 / Twitter / TikTok / Bilibili / Instagram 的视频与图文，支持服务端代理下载、无水印下载、多连接加速、实时下载进度，以及基于字幕的 AI 总结与问答。

## 功能特性

- **多平台解析**：YouTube、抖音、Twitter、TikTok、Bilibili、Instagram（视频 / 图文 / 轮播）、CCTV（多清晰度）
- **两种下载方式**：可直连的直链浏览器下载；被墙 CDN / 需合并的格式走服务端代理下载
- **无水印下载**：抖音无水印直链优先，失败自动回退 yt-dlp
- **下载加速**：aria2c 多连接分片（默认 16 连接），ffmpeg 自动合并音视频
- **实时下载进度**：WebSocket 推送 yt-dlp / aria2c / 直链流的下载进度与速度
- **用户 Cookies 管理**：六平台 cookies 登录后上传入库，上传即校验，失效自动标记并引导更新；抖音 / Instagram 必须配置 cookies 后使用
- **CCTV h5e 解密**：Node.js + Playwright-core 注入浏览器 WASM 批量解密 TS 段，速度约 20x 实时播放，自动处理多清晰度合并
- **Instagram 轮播展示**：视频 + 图片混合轮播分两栏预览（视频封面帧 / 图片网格可放大），支持 ZIP 打包下载
- **AI 字幕总结与问答**：自动提取视频字幕，通义千问流式生成总结 / 思维导图 / 问答，免费用户每日限次

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 21、Spring Boot 3.5.x（Web / WebSocket / Validation / Data JPA）、JJWT、spring-security-crypto（BCrypt） |
| 数据库 | MySQL（JPA `ddl-auto: update` 自动建表） |
| 下载引擎 | yt-dlp（外部进程）、aria2c（可选加速）、ffmpeg（合并）、deno（YouTube PO Token 必需）、Node.js + Playwright-core（CCTV h5e 解密） |
| AI | 通义千问（OpenAI 兼容端点），SSE 流式输出 |
| 前端 | Vue 3 + Vite + Tailwind CSS + axios，无路由单页（分区切换） |

## 架构说明

系统由一个 Spring Boot 单体后端和一个 Vue3 SPA 前端组成，前端经 Vite 代理访问后端 `/api/**` 与 `/ws/**`。视频下载能力不直接实现协议，而是编排外部 yt-dlp 进程；Instagram 与抖音分享页为纯 Java 解析（HTTP + 页面内嵌 JSON）。

```
┌─────────────┐   /api/**、/ws/**    ┌──────────────────────────────────┐
│  Vue3 SPA   │ ───────────────────▶ │        Spring Boot 后端           │
│  (Vite 5173)│ ◀─────────────────── │  REST + SSE + WebSocket           │
└─────────────┘                      └───────┬──────────────────────────┘
                                             │
                    ┌────────────────────────┼─────────────────────────┐
                    ▼                        ▼                         ▼
              MySQL (fvd)             yt-dlp 进程              外部 HTTP/代理
          用户/Cookie/AI配额       (aria2c/ffmpeg/deno)        IG 页面解析、AI 网关、
                                                                CDN 直链流式转发
```

后端按 DDD 限界上下文分包，每个上下文内部分 `interfaces / application / domain / infrastructure` 四层：

```
com.fvd
├── VideoDownloaderApplication        # 启动类
├── shared
│   ├── config        # WebConfig（CORS/拦截器）、WebSocketConfig
│   └── web           # ApiResponse 统一响应、BusinessException、全局异常处理
├── auth              # 账号与配额
│   ├── interfaces    # AuthController（注册/登录/刷新/登出/me）、AuthInterceptor（JWT 强/可选认证）
│   ├── application   # JwtService、AiQuotaService（免费每日限次）
│   ├── domain        # User、RefreshToken、AiUsage + Repository
│   └── (infrastructure 复用 spring-security-crypto)
├── cookie            # 用户 Cookies 管理
│   ├── interfaces    # CookieController（状态查询/上传/删除，强认证）
│   ├── application   # CookieService：Netscape 解析校验、按平台探活、临时文件落地、失效标记
│   ├── domain        # UserCookie（user_id+platform 唯一）
│   └── infrastructure# InstagramCookieVerifier（真实探活，走代理）
├── video             # 解析与下载（核心上下文）
│   ├── interfaces    # VideoController：/parse、/direct-url、/download
│   ├── application   # DownloadService：yt-dlp 进程编排、进度解析、直链流式转发、ZIP/临时目录清理
│   ├── domain        # Platform、VideoInfo、FormatInfo、MediaItem
│   └── infrastructure# YtDlpService（命令构建/JSON 解析）、DouyinParser、InstagramParser、
│                     # DownloadProgressHandler（WebSocket 推送）
└── ai                # 字幕 AI
    ├── interfaces    # SummaryController：/summarize、/chat（SSE）
    ├── application   # SubtitleExtractor（yt-dlp 拉字幕 + VTT 解析）
    ├── domain        # SubtitleData
    └── infrastructure# DeepSeekClient（OpenAI 兼容，通配千问）、VttParser
```

分层职责：`interfaces` 只做协议与认证；`application` 编排用例与外部进程；`domain` 放实体与值对象；`infrastructure` 封装 HTTP 客户端、外部命令与第三方协议。

## 主要流程

### 1. 解析与下载（核心链路）

```
用户粘贴链接
   │  POST /api/parse {url}
   ▼
VideoController ──按域名路由──▶ InstagramParser（纯 Java：抓帖子页 data-sjs JSON）
   │                              或 DouyinParser（分享页 API）
   │                              或 YtDlpService.dumpInfo（yt-dlp -J，覆盖其余平台）
   ▼
返回 VideoInfo（标题/封面/格式列表/轮播 media 明细）
   │
   │  用户选择格式后：
   │  ├─ 单流可直连 ─▶ POST /api/direct-url ─▶ 返回直链 ─▶ 浏览器直接下载
   │  └─ serverOnly / 需合并 ─▶ POST /api/download
   ▼
DownloadService：
  yt-dlp 下载到临时目录（aria2c 加速、ffmpeg 合并）
  → 流式回写浏览器（Content-Disposition 原始文件名）
  → finally 清理临时目录与 cookies 临时文件
```

- 解析与下载均为「可选认证」：携带 token 则注入当前用户；未登录仅对抖音 / Instagram 拒绝（401 引导登录配置 cookies）
- 使用用户 cookies 下载报鉴权类错误时，自动把该用户对应平台 cookie 标记为失效

### 2. 用户 Cookies 管理

```
登录用户 →「我的 Cookies」弹窗
   │  GET  /api/cookies            六平台状态（未配置/有效/失效）
   │  POST /api/cookies/{platform} 上传浏览器导出的 cookies.txt（multipart）
   │  DELETE /api/cookies/{platform}
   ▼
CookieService：
  Netscape 格式解析 → 平台关键 cookie 校验（sessionid / auth_token / SESSDATA / SID …）
  → Instagram 额外真实探活（302 跳登录即判失效）
  → upsert 入库（MEDIUMTEXT 保存原文）
  使用时：按需读取 → 写临时文件 → yt-dlp --cookies → 用完即删
```

- 保存时校验 + 下载失败自动标记（valid=false），前端失效状态引导重新上传
- 抖音登录态无法从服务器探活（服务器 IP 触发验证码页），故仅格式校验，有效性由下载反馈

### 3. 下载进度实时推送（WebSocket）

```
前端生成 taskId ──▶ 建立 ws://…/ws/download-progress?taskId=xxx
        └─▶ POST /api/download {…, taskId}
后端：逐行解析 yt-dlp --progress-template 输出（FVDPROG|已下载|总量|速度）
      或 aria2c 进度行（[#id 1.5MiB/10MiB(15%) … DL:…]）
      或直链流字节计数 ──▶ 300ms 节流推送 {type:progress|done|error}
前端：进度条显示百分比 / 已下载 / 总量 / 速度
```

### 4. AI 字幕总结与问答

```
POST /api/summarize、/api/chat（登录 + 每日配额，SSE 流式返回）
  → SubtitleExtractor：yt-dlp --write-auto-subs 拉取字幕 → VttParser 解析为纯文本
  → DeepSeekClient（OpenAI 兼容）调用通义千问流式生成
  → AiQuotaService 按用户按天计数（免费额度 AI_FREE_DAILY_QUOTA）
```

## 快速开始

### 环境依赖

- JDK 21+、Maven 3.9+
- Node.js 18+（前端 + CCTV 解密）
- MySQL 5.7+（库可自动创建）
- [yt-dlp](https://github.com/yt-dlp/yt-dlp/releases)（必需，视频解析下载核心）
- [ffmpeg](https://ffmpeg.org/download.html)（合并音视频必需，Windows 下载 [gyan.dev](https://www.gyan.dev/ffmpeg/builds/) 或 [BtbN](https://github.com/BtbN/FFmpeg-Builds/releases) 构建版）
- 可选：[aria2c](https://github.com/aria2/aria2/releases)（多连接加速，Windows 下载 `aria2-*-win-64bit-build1.zip`）
- 可选：[deno](https://github.com/denoland/deno/releases)（YouTube 反爬必需，解 BotGuard 挑战）
- 可选：出站代理（如 `http://127.0.0.1:10808`，访问 YouTube/Twitter/Instagram/TikTok/BBC 等被墙平台时必需）

### CCTV 解密依赖（首次使用必须）

CCTV h5e 加密视频采用 Node.js 批量解密方案，运行前需安装 `playwright-core`：

```bash
cd backend/src/main/resources/cctv
npm install
```

脚本依赖系统全局 Playwright 浏览器缓存（`%USERPROFILE%\AppData\Local\ms-playwright`）。若提示浏览器未找到，执行 `npx playwright-core install chromium`。

### 1. 数据库

默认连接 `192.168.1.200:3308` 的 `fvd` 库（不存在会自动创建），通过 `.env` 覆盖：

```ini
DB_HOST=127.0.0.1
DB_PORT=3308
DB_NAME=fvd
DB_USER=root
DB_PASSWORD=yourpassword
```

表结构参考 [backend/sql/schema.sql](backend/sql/schema.sql)，JPA 启动时也会自动建表。

### 2. 后端配置与启动

复制 `backend/.env`（参考下方配置项表）后：

```bash
cd backend
mvn spring-boot:run          # 默认 8080
```

或在 IDEA 中直接运行 `VideoDownloaderApplication`。

### 3. 前端启动

```bash
cd frontend
npm install
npm run dev                  # 5173，/api 与 /ws 代理到 8080
npm run build                # 产物在 dist/
```

## API 一览

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| POST | `/api/auth/register` / `login` / `refresh` / `logout` | - | 注册、登录（JWT）、刷新、登出 |
| GET | `/api/auth/me` | 需要 | 当前用户信息与会员/配额状态 |
| POST | `/api/parse` | 可选 | 解析视频信息（抖音/Instagram 需登录+cookies） |
| POST | `/api/direct-url` | 可选 | 获取直链（直链下载模式） |
| POST | `/api/download` | 可选 | 服务端代理下载（`taskId` 选填，用于进度推送） |
| GET | `/api/cookies` | 需要 | 六平台 cookies 状态 |
| POST/DELETE | `/api/cookies/{platform}` | 需要 | 上传（multipart `file`）/ 删除 cookies |
| POST | `/api/summarize`、`/api/chat` | 需要 | AI 总结 / 问答（SSE 流式） |
| WS | `/ws/download-progress?taskId=` | - | 下载进度推送（无需认证） |

统一响应：`{ "success": true, "data": … }` / `{ "success": false, "error": "…" }`。

## 配置项（backend/.env）

| 变量 | 说明 |
|---|---|
| `DB_*` | MySQL 连接 |
| `JWT_SECRET`、`JWT_ACCESS_EXPIRE_MINUTES`、`JWT_REFRESH_EXPIRE_DAYS` | JWT 密钥与有效期（生产务必改密钥） |
| `YTDLP_PATH` | yt-dlp 可执行文件，默认取 PATH，[下载地址](https://github.com/yt-dlp/yt-dlp/releases) |
| `FFMPEG_LOCATION` | ffmpeg 路径（目录或 exe），合并必需，[下载地址](https://ffmpeg.org/download.html)（Windows 推荐 [gyan.dev](https://www.gyan.dev/ffmpeg/builds/) 或 [BtbN](https://github.com/BtbN/FFmpeg-Builds/releases) 构建版） |
| `ARIA2C_PATH`、`ARIA2C_CONNECTIONS` | aria2c 路径与单服务器连接数（默认 16），[下载地址](https://github.com/aria2/aria2/releases) |
| `JS_RUNTIME_PATH` | deno 路径，YouTube 反爬必需，[下载地址](https://github.com/denoland/deno/releases) |
| `PROXY_URL` | 出站代理（如 `http://127.0.0.1:10808`），被墙平台必需 |
| `AI_BASE_URL`、`AI_API_KEY`、`AI_MODEL` | OpenAI 兼容 AI 网关（默认阿里云百炼 qwen-plus） |
| `AI_FREE_DAILY_QUOTA` | 免费用户每日 AI 次数（默认 3） |
| `DOWNLOADS_DIR`、`PARSE_TIMEOUT` | 服务端临时下载目录、解析超时秒数 |
| `NODE_EXE` | Node.js 可执行文件路径（CCTV 解密必需），[下载地址](https://nodejs.org/en/download) |
| `CCTV_DECRYPT_SCRIPT` | CCTV 解密脚本路径（默认 `src/main/resources/cctv/decrypt_browser.js`） |
| `CCTV_DECRYPT_TIMEOUT_MS` | CCTV 解密超时（默认 10 分钟，45 分钟视频约 3 分钟完成） |

## 安全注意事项

- `.env`、cookies 文件均已列入 `.gitignore`，严禁提交（cookies 含登录态）
- 生产部署请更换 `JWT_SECRET`、限制 CORS 来源、关闭 `ddl-auto: update` 改用迁移脚本
