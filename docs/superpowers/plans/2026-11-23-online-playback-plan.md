# ClipFetch 解析结果在线播放 实施计划

> 日期：2026-11-23（Asia/Shanghai）
> 状态：Plan-only，尚未实施
> 仓库：D:\release\clipfetch
> 依据：AGENTS.md、HANDOFF.md、ISSUE.md，以及本轮对 parser / temp / 前端源码的实际阅读
> 实施纪律：本轮只写计划与规格，不修改业务代码、不执行构建、不启动服务、不创建 commit

## 1. 目标与验收标准

在现有“解析 → 选择清晰度 → 下载”链路上，新增“解析结果在线播放”能力：

1. 解析成功后，在 `VideoResult` 卡片内直接展开播放器，播放当前选中的清晰度；
2. 同时覆盖两类源：浏览器可直接播放的**渐进式单文件**（MP4/WebM/音频）和 **HLS（m3u8）**；
3. 视频格式用 `<video>`，纯音频格式自动切换 `<audio>`；
4. 播放过程支持拖动进度（依赖 HTTP Range）、切换清晰度、关闭后立即释放连接；
5. 不落盘、不依赖下载任务，服务端只做“带来源头的流式代理”；
6. 首期完成标准：
   - 渐进式：`Range: bytes=0-1023` 返回 206 且 `Content-Range` 正确；
   - HLS：改写后的 master/media playlist 能被 hls.js 播放，分片与密钥请求全部走同源代理；
   - 播放、拖动、切换、关闭、组件卸载均无连接泄漏与控制台报错；
   - 未登录用户拿不到播放票据；播放地址不含 JWT。

## 2. 首期范围与非目标

### 2.1 首期做

- 渐进式视频/音频代理播放（含 yt-dlp 解析出的单文件格式）；
- HLS 播放：后端 m3u8 与分片/密钥代理 + 前端 hls.js（按需异步加载）；
- 票据式鉴权（浏览器原生 `<video>` 无法携带 Authorization 头）；
- 平台支持矩阵中标注“首期”的解析器；
- 播放器内错误提示、清晰度切换、移动端适配。

### 2.2 首期不做

- 服务端实时转封装/合并（YouTube `bv+ba` 高清组合、MKV 等浏览器不支持的容器）；
- DRM（Widevine/FairPlay）内容；
- CCTV h5e 的 WASM 加密分片解密播放（下载链路已有 Node sidecar，播放首期不复用）；
- 边下边播、离线缓存、播放进度记忆、投屏、分享链接、字幕挂载；
- 改造下载链路、magnet/ed2k/ftp 模块；
- 新增 MQ、微服务、Redis 用途、新 HTTP 客户端或前端 UI 框架。

## 3. 本轮已核对的仓库事实

- 解析入口：`POST /api/parse` 由 [ParseController.java](../../../backend/src/main/java/cc/ivera/parser/interfaces/ParseController.java) 调用 `ParserRegistry`，返回 `VideoInfo`；解析结果 5 分钟 Caffeine 缓存（`userId|url`）。
- `VideoInfo` / `FormatInfo` 仅包含格式元数据，**不包含直链**；`FormatInfo` 已有 `needsMerge / audioOnly / serverOnly` 三个语义位。
- `YtDlpService` 把 `protocol` 含 `m3u8` 的格式与 YouTube/Twitter/TikTok/抖音统一标记为 `serverOnly`；`needsMerge` 组合格式的 `formatId` 形如 `videoId+audioId`。
- 下载侧 [DownloadController.java](../../../backend/src/main/java/cc/ivera/parser/interfaces/DownloadController.java) 已按平台硬编码了“取流地址”的私有知识：CCTV `resolveDownloadUrl`、Tubi `resolveDownloadUrl`、BBC 内存 map、CGTN 内存 map、Amasian `resolveStreamUrl`、腾讯 `directUrl`、音乐 `resolveAudioUrl`、NetMirror `resolveDownloadUrl` 等；这些可直接复用。
- CCTV h5e 分片被 WASM 加密，下载走 [CctvNodeDecryptSidecar]；浏览器 hls.js 无法直接解码。
- VIP 解析（优酷/爱奇艺/芒果 TV）、华视影院、酷播 IfyTv 在下载时才重新取流，解析结果未缓存可复用地址；小红书/微博同样在下载时解析，需要改造后才能播放。
- `HlsClient` 已具备 master playlist 解析与变体识别能力，可作为 HLS 代理的解析参考，但其现有职责是 ffmpeg 下载，不在本轮改动。
- 认证：全局 JWT 拦截器，白名单只在 [WebConfig.java](../../../backend/src/main/java/cc/ivera/shared/config/WebConfig.java) 维护；现有免登录路径仅 auth 三件套、`/api/wallpaper`、`/api/image-proxy`、`/api/magnet/downloads/stream/*`。
- 前端：Vue 3 + Vite 6 + Tailwind；`frontend/package.json` 当前**没有** hls.js；`VideoResult.vue` 是解析结果卡片，下载按钮与清晰度选择都在这里。
- 前端下载用 axios Blob；`<video>` 无法带 JWT，也不能把整片读进内存，因此必须走短时票据 + 同源代理。
- 仓库当前无单元测试源码（`backend/src/test` 已按用户要求移除），验证以编译 + 手工 E2E + curl 为准。

## 4. 方案总览

### 4.1 核心取舍

| 方案 | 结论 | 原因 |
|---|---|---|
| 浏览器直连上游直链 | 仅作兜底，不作为主路径 | Referer/Cookie/CORS、URL 时效、被墙 CDN 都会失败 |
| axios Blob 再播放 | 否决 | 整片进内存、无法 seek、长视频必然 OOM |
| 后端同源代理 + Range | 采用 | 可带平台 Referer/Cookie、支持 seek、浏览器原生播放 |
| 免登录白名单直接带 URL | 否决 | 任意 URL 转发会变成 SSRF，且无法绑定用户 |
| 短时票据 + 白名单代理端点 | 采用 | 与 qBittorrent v0 stream 票据模式一致；`<video>` 无需 JWT |
| HLS 前端直连 m3u8 | 否决 | Referer/Cookie/CDN 校验会 403/404 |
| 后端 m3u8 改写 + hls.js | 采用 | 所有请求同源，来源头由后端统一补齐 |
| 服务端 ffmpeg 实时转封装 | 首期不做 | 启动慢、seek 差、CPU 成本高，超出“在线播放”首期目标 |

### 4.2 数据流

~~~mermaid
flowchart LR
    V[VideoResult.vue] -->|POST /api/play/ticket 带 JWT| T[PlaybackController]
    T --> R[PlaybackSourceService]
    R -->|复用 parser resolve 能力 / yt-dlp -g| U[上游流地址]
    T --> K[(PlaybackTicketStore 内存票据)]
    T -->|ticket + mode + mime| V
    V -->|video.src / hls.js| P[GET /api/play/stream 或 /api/play/hls]
    P -->|校验票据、带 Referer/Cookie、转发 Range| U2[上游 CDN]
    U2 -->|206 / 200 / m3u8| P
    P -->|字节流 / 改写后的 playlist| V
~~~

- 票据只存服务端内存：服务器重启后播放失效，用户重新点击播放即可；
- 票据绑定 `userId + 原始链接 + formatId`，不透明随机串，不含 JWT；
- 上游直链在创建票据时解析一次并缓存在票据里，避免每个 Range 请求都跑一次 yt-dlp / sidecar；
- HLS 子资源（子 playlist、分片、密钥、init map）由服务端登记成子 token，用户无法拼任意 URL 让服务端代取。

## 5. 平台支持矩阵

| 平台 | 取流方式 | 模式 | 首期 | 说明 |
|---|---|---|---|---|
| 通用 yt-dlp（Twitter/TikTok/Bilibili 等） | `ytDlp.directUrl` | 渐进 / HLS | 是 | 非 `needsMerge` 格式 |
| YouTube | `ytDlp.directUrl`（googlevideo 走配置代理） | 渐进 | 是 | 仅单文件格式；`bv+ba` 组合不支持 |
| 抖音 | `douyinParser.tryDirectUrl` | 渐进 | 是 | 无水印直链 |
| Instagram 视频帖 | `ytDlp.directUrl` | 渐进 | 是 | 图文帖无播放 |
| 直链媒体文件 | 原 URL | 渐进 | 是 | 受 SSRF 校验限制 |
| QQ 音乐 / 酷狗 / 网易云 | `resolveAudioUrl` | 渐进音频 | 是 | 带平台 Referer |
| NetMirror | `resolveDownloadUrl` | 渐进 | 是 | 按 formatId 重新取 MP4 |
| Tubi | `resolveDownloadUrl` | HLS | 是 | 需把 `#EXT-X-BYTERANGE` 对应 Range 转发 |
| BBC | 已解析变体 map | HLS | 是 | 密钥请求同样走代理 |
| CGTN | 新增 `resolveStreamUrl` | HLS | 是 | 现只有下载方法 |
| Amasian TV | `resolveStreamUrl` | HLS | 是 | 已有缓存 |
| 腾讯视频 | `directUrl`（sidecar 缓存） | HLS | 是 | 代理补 `Referer: https://v.qq.com/` |
| CCTV | h5e WASM 加密 | HLS | 否 | 首期明确提示“该平台暂不支持在线播放” |
| VIP 解析（优酷/爱奇艺/芒果） | 下载时重新取流 | HLS | 二阶段 | 需新增解析期缓存 |
| 华视影院 | 一次性令牌 + 广告等待 | HLS | 否 | 播放不现实，明确不支持 |
| 酷播 IfyTv | 下载时重新取流 | HLS | 二阶段 | 需新增解析期缓存 |
| 小红书 / 微博 | 下载时解析直链 | 渐进 | 二阶段 | 需在解析期缓存直链 |

前端播放按钮的启用规则：`!needsMerge` 且 `ext` 在浏览器可播放集合内（`mp4/m4v/webm/mov` 与 `mp3/m4a/aac/flac/ogg/opus/wav`）；平台明确不支持时后端返回业务错误，前端展示中文原因。

## 6. 后端改动清单

### 6.1 新增文件

~~~text
backend/src/main/java/cc/ivera/parser/
  interfaces/PlaybackController.java          # 票据申请 + 两个免登录代理端点
  application/PlaybackSourceService.java      # 按平台解析上游流地址，复用现有 parser 方法
  application/PlaybackTicketStore.java        # 内存票据与子资源 token（Caffeine）
  application/PlaybackTicket.java             # 不可变票据记录
  infrastructure/playback/PlaybackProxyClient.java  # JDK HttpClient 流式转发 + Range
  infrastructure/playback/HlsPlaylistRewriter.java  # m3u8 改写与子资源登记
  infrastructure/playback/UpstreamTargetPolicy.java # 上游地址/重定向 SSRF 校验
backend/src/main/resources/mapper/            # 无需新增：播放不落库
~~~

### 6.2 修改文件

- [WebConfig.java](../../../backend/src/main/java/cc/ivera/shared/config/WebConfig.java)：白名单仅新增 `/api/play/stream/*`、`/api/play/hls/*`，并写清“浏览器原生媒体请求无法带 Authorization，仍通过短时票据二次校验”；
- [CgtnParser.java](../../../backend/src/main/java/cc/ivera/parser/application/parser/video/CgtnParser.java)：新增 `resolveStreamUrl(url, formatId)`，供播放复用；
- [application.yml](../../../backend/src/main/resources/application.yml)：新增 `app.play.*` 占位符；
- `backend/.env`、根 `.env`、`.env.example`：三处同步新增同一组 `PLAY_*` 键；
- 如实现中发现某 HLS 解析器缺少 `resolveStreamUrl`，只补最小方法，不重构下载逻辑。

### 6.3 播放能力实现要点

1. **票据申请**（需登录）：
   - 入参 `url + formatId`；
   - 复用现有 parser 的解析缓存/`resolve*` 方法取上游地址；缓存缺失时按现有下载逻辑“重新解析一次填充缓存”；
   - 判断模式：上游 URL 以 `.m3u8` 结尾或响应头为 `application/vnd.apple.mpegurl` → HLS；否则渐进；
   - 返回 `ticket / mode / mime / title / expiresAt`；
   - 不支持的平台/格式抛中文 `BusinessException`。
2. **渐进式代理**：
   - 校验票据存在且未过期；
   - 转发 `Range`，透传 `206 / 200 / 416` 与 `Content-Range / Content-Length / Content-Type`；
   - 客户端断开时关闭上游流（seek 会频繁中断请求，必须 try-with-resources）；
   - 上游忽略 Range 时退化为 200 全量流，前端仍可播但 seek 受限（记录日志）。
3. **HLS 代理**：
   - 入口 `master.m3u8` 拉取后判断 master / media；
   - 改写所有 URI：变体行、`#EXT-X-KEY:URI`、`#EXT-X-MAP:URI`、`#EXT-X-MEDIA:URI`、`#EXT-X-PART`、`#EXT-X-PRELOAD-HINT` 等；
   - 每个叶子 URI 生成随机子 token 并登记真实上游地址与内容类型，返回路径形如 `/api/play/hls/{ticket}/{childToken}`；
   - 相对路径按上游最终 URL（含重定向）解析；
   - playlist 强制 `application/vnd.apple.mpegurl` + `no-store`；分片透传上游 Content-Type 并转发 Range；
   - 子 token 数量与票据 TTL 均有上限，超限淘汰最旧。
4. **安全**：
   - 票据 32 字节 `SecureRandom`，不打印、不入日志；
   - 所有上游请求（含重定向每一跳）禁止 loopback/私网/链路本地/ULA/组播/云 metadata，默认只允许公网 http(s)；
   - 不得接受用户直接传入的任意上游 URL；直链文件类型也必须经过同一地址策略；
   - 票据失效返回 404/410，上游失败返回 502 并给中文提示。

## 7. 前端改动清单

~~~text
frontend/package.json                     # 新增 hls.js（运行时按需 import）
frontend/src/api/video.js                 # 新增 createPlayTicket
frontend/src/components/VideoPlayer.vue   # 新增：video/audio + hls.js 封装
frontend/src/components/VideoResult.vue   # 新增“在线播放”按钮与播放面板
~~~

- `VideoPlayer.vue` 由 `VideoResult` 用 `defineAsyncComponent` 异步加载，避免首屏引入 hls.js；
- 渐进式直接给 `<video>/<audio>` 设 `src`；
- HLS：`Hls.isSupported()` 时动态 `import('hls.js')` 并 attach；Safari 原生 HLS 直接设 `src`；两者都不支持时提示；
- 播放器显示标题、清晰度标签、加载/错误状态；
- 切换清晰度时销毁旧实例并重新申请票据；
- `onBeforeUnmount` / 关闭 / 重新解析 / 路由离开时销毁 hls 实例、暂停媒体、清空 src；
- 播放按钮在 `needsMerge` 或编码不受支持时禁用，并给出 title 提示；
- 播放错误文案：“播放地址已过期，请重新点击播放”“该视频暂不支持在线播放”等，复用现有错误样式。

## 8. 配置新增（三处 .env 必须同步）

| 变量 | 默认 | 说明 |
|---|---|---|
| PLAY_ENABLED | true | 在线播放总开关，关闭时票据接口返回业务错误 |
| PLAY_TICKET_TTL_SECONDS | 7200 | 票据有效期 |
| PLAY_MAX_TICKETS_PER_USER | 3 | 单用户同时有效票据上限，超出淘汰最旧 |
| PLAY_CHILD_MAX_PER_TICKET | 4096 | 单票据 HLS 子资源上限 |
| PLAY_UPSTREAM_CONNECT_TIMEOUT_MS | 10000 | 上游连接超时 |
| PLAY_UPSTREAM_TIMEOUT_SECONDS | 3600 | 单次上游流最长持续时间 |
| PLAY_BUFFER_BYTES | 65536 | 代理转发缓冲区 |
| PLAY_BLOCK_PRIVATE_HOSTS | true | 上游 SSRF 私网拦截开关 |
| PLAY_MAX_REDIRECTS | 5 | 上游重定向上限，逐跳校验 |

`application.yml` 对应：

~~~yaml
app:
  play:
    enabled: ${PLAY_ENABLED:true}
    ticket-ttl-seconds: ${PLAY_TICKET_TTL_SECONDS:7200}
    max-tickets-per-user: ${PLAY_MAX_TICKETS_PER_USER:3}
    child-max-per-ticket: ${PLAY_CHILD_MAX_PER_TICKET:4096}
    upstream-connect-timeout-ms: ${PLAY_UPSTREAM_CONNECT_TIMEOUT_MS:10000}
    upstream-timeout-seconds: ${PLAY_UPSTREAM_TIMEOUT_SECONDS:3600}
    buffer-bytes: ${PLAY_BUFFER_BYTES:65536}
    block-private-hosts: ${PLAY_BLOCK_PRIVATE_HOSTS:true}
    max-redirects: ${PLAY_MAX_REDIRECTS:5}
~~~

## 9. 实施阶段（每步含验证）

### Task 0 — 基线确认

- 读取 `HANDOFF.md`、`ISSUE.md`、`docs/superpowers/{plans,specs}` 最新文件；
- 确认 `WebConfig` 白名单现状与 `VideoResult` 现状未变；
- 验证：记录 `git status`，确认不覆盖他人未提交改动。

### Task 1 — 票据与渐进式代理（不含 HLS）

- 新增 Controller / SourceService / TicketStore / ProxyClient / TargetPolicy；
- 覆盖：yt-dlp 通用、YouTube 单文件、抖音、直链文件、音乐三平台、NetMirror；
- 验证：
  - `mvn -q clean compile -DskipTests` 退出码 0；
  - 重启后端后 `curl` 创建票据，`Range: bytes=0-1023` 返回 206；
  - 无 Range 返回 200；错误票据返回 404；
  - 浏览器 `<video>` 可播放并拖动。

### Task 2 — HLS 代理

- `HlsPlaylistRewriter` + 子 token 登记 + 分片/密钥转发；
- 覆盖：Tubi、BBC、CGTN、Amasian、腾讯；
- 验证：
  - 改写后的 master 中不再出现外链域名；
  - 分片请求 200，`#EXT-X-BYTERANGE` 场景 Range 正确；
  - 浏览器 hls.js 播放、拖动、切清晰度正常；
  - CCTV/VIP/华视/酷播等返回明确“不支持在线播放”。

### Task 3 — 平台补齐与错误收敛

- CGTN 新增 `resolveStreamUrl`；
- 统一 unsupported 文案与日志（不打印完整上游 URL 的敏感 query，只打印 host + path 摘要）；
- 验证：矩阵中“首期”平台逐个手工冒烟。

### Task 4 — 前端播放器

- `VideoPlayer.vue`、`createPlayTicket`、`VideoResult` 播放按钮与面板；
- 验证：
  - `npm run build` 退出码 0，hls.js 在独立 chunk；
  - 移动端窄屏不溢出；
  - 关闭/切换/重新解析后无残留定时器、无未销毁 hls 实例；
  - 未登录时点击播放提示登录。

### Task 5 — 回归与交接

- 播放不改变解析与下载行为；
- 白名单只新增两条播放路径；
- 验证命令真实记录 Exit Code；
- 更新 HANDOFF.md（本轮只写计划与规格，实施轮另记）。

## 10. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 上游不支持 Range | 退化 200；UI 提示“该源可能无法拖动”；日志记录 host |
| 直链在播放中途过期 | 票据 TTL 内失败返回 502；前端提示重新播放，重新申请票据 |
| HLS 直播/滚动 playlist | 不做特殊处理，`no-store` 转发；首期以点播为主 |
| AES-128 密钥接口需来源头 | 密钥 URI 也走子 token 代理，统一补 Referer/Cookie |
| seek 风暴导致连接堆积 | 有界 buffer、finally 关闭上游流；Tomcat 线程与并发限制在验收中观察 |
| CCTV h5e 加密 | 明确不支持；不把加密分片当普通 TS 返回 |
| YouTube CDN 需出口代理 | 复用 `ytDlp.proxy()` 配置，仅对 googlevideo/youtube 域名启用 |
| 长视频长期占用线程 | 首期为已知限制；后续如需扩展再做异步/切片缓存设计 |

## 11. 交付物

1. `.trae/documents/plan/2026-11-23-online-playback-plan.md`（本文件）
2. `.trae/documents/spec/2026-11-23-online-playback-spec.md`（接口与实现合同）
3. 实施轮：后端播放模块、前端播放器、三处 .env 与 application.yml、HANDOFF.md 更新

本轮不修改任何业务代码，不执行构建，不启动服务，不创建 commit。
