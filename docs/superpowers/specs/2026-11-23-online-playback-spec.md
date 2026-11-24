# ClipFetch 解析结果在线播放 技术规格（SPEC）

> 日期：2026-11-23（Asia/Shanghai）
> 状态：设计合同，尚未实施
> 仓库：D:\release\clipfetch
> 实施计划：`.trae/documents/plan/2026-11-23-online-playback-plan.md`
> 项目约束：AGENTS.md、HANDOFF.md、ISSUE.md
> 说明：本规格只定义实现与验收合同，不代表功能已完成；本轮不修改业务代码、不构建、不启服务、不创建 commit。

## 1. 目标与验收标准

### 1.1 目标

解析成功后在 `VideoResult` 卡片内在线播放所选清晰度，支持：

- 渐进式单文件（MP4/WebM/MOV、MP3/M4A/AAC/FLAC/OGG/OPUS/WAV）；
- HLS（m3u8 master/media playlist、TS/fMP4 分片、AES-128 密钥、EXT-X-MAP init、EXT-X-BYTERANGE）；
- 进度拖动、清晰度切换、关闭即释放；
- 平台 Referer/Cookie、出口代理等来源头由后端统一补齐。

### 1.2 完成标准

1. `POST /api/play/ticket` 需要 JWT；未登录返回 401。
2. 渐进式：无 Range 返回 200；`Range: bytes=0-1023` 返回 206 且 `Content-Range`、`Content-Length` 正确；越界返回 416 且 `Content-Range: bytes */total`。
3. HLS：改写后的 playlist 只包含 `/api/play/hls/...` 同源地址；hls.js 能连续播放；拖动触发的新分片请求成功。
4. 播放期间关闭播放器或组件卸载后，上游连接在 5 秒内关闭，无持续拉流日志。
5. 票据过期、被淘汰或服务器重启后，播放器收到明确中文错误，可一键重新播放。
6. 服务端不接受任意用户 URL 代理（直链文件类型也必须过地址策略），无 SSRF。
7. 既有解析、下载、magnet/ed2k/ftp 接口无回归；白名单只新增播放两条路径。

### 1.3 非目标

- 不做服务端转封装/音视频合并、DRM、CCTV h5e WASM 解密、边下边播、字幕挂载、投屏、播放进度记忆；
- 不做离线缓存、播放统计、分享链接；
- 不新增数据库表、Mapper、Flyway、Redis 用途、MQ、微服务、新 HTTP 客户端；
- 不改变现有下载链路与解析结果 JSON 结构。

## 2. 组件与职责

~~~mermaid
flowchart TD
    C[PlaybackController] -->|创建票据| S[PlaybackSourceService]
    C -->|查询票据| K[PlaybackTicketStore]
    C -->|渐进式| P[PlaybackProxyClient]
    C -->|HLS| H[HlsPlaylistRewriter]
    S --> PR[ParserRegistry / 各 parser resolve 方法 / YtDlpService]
    K --> M[(内存 Caffeine)]
    P --> T[UpstreamTargetPolicy]
    H --> T
    T -->|校验后| U[上游 CDN]
~~~

~~~text
backend/src/main/java/cc/ivera/parser/
  interfaces/PlaybackController.java
  application/PlaybackSourceService.java
  application/PlaybackTicketStore.java
  application/PlaybackTicket.java
  infrastructure/playback/PlaybackProxyClient.java
  infrastructure/playback/HlsPlaylistRewriter.java
  infrastructure/playback/UpstreamTargetPolicy.java
frontend/src/components/VideoPlayer.vue
frontend/src/components/VideoResult.vue（修改）
frontend/src/api/video.js（修改）
~~~

职责边界：

- `PlaybackController`：只做参数校验、当前用户识别、票据校验与响应写出，不解析平台细节；
- `PlaybackSourceService`：按 URL 平台 + formatId 解析上游地址与请求头，复用 parser 现有能力；不下载；
- `PlaybackTicketStore`：票据与 HLS 子 token 的唯一内存真相，负责 TTL、容量淘汰、用户配额；
- `PlaybackProxyClient`：JDK HttpClient 流式转发，处理 Range 与中断；不缓存完整文件；
- `HlsPlaylistRewriter`：纯函数式改写 playlist 文本，子资源登记通过 Store；
- `UpstreamTargetPolicy`：协议、私网地址、重定向逐跳校验。

## 3. HTTP 接口合同

### 3.1 创建播放票据（需登录）

`POST /api/play/ticket`

请求：

~~~json
{
  "url": "https://www.youtube.com/watch?v=xxxx",
  "formatId": "18"
}
~~~

响应：

~~~json
{
  "success": true,
  "data": {
    "ticket": "H1k...（43 字符 base64url）",
    "mode": "progressive",
    "mime": "video/mp4",
    "title": "示例标题",
    "expiresAt": "2026-11-23T22:00:00"
  }
}
~~~

`mode` 取值：`progressive`、`hls`。

校验规则：

| 字段 | 规则 |
|---|---|
| url | 必填，`http://` 或 `https://` 开头；沿用 `validateUrl` 文案 |
| formatId | 渐进式/HLS 必填；音乐可空（按默认品质） |

失败：

| 场景 | HTTP | 文案示例 |
|---|---|---|
| 未登录 | 401 | 沿用全局认证 |
| PLAY_ENABLED=false | 400 | 在线播放功能未开启 |
| 需合并的高清格式（formatId 含 `+` 或 `needsMerge`） | 400 | 该清晰度需要音视频合并，暂不支持在线播放，请下载后观看 |
| 平台明确不支持（CCTV/华视等） | 400 | 该平台暂不支持在线播放 |
| 解析取流失败 | 400 | 获取播放地址失败：<上游原因> |
| 用户票据超限 | 400 | 同时播放的任务过多，请关闭其他播放后重试 |

### 3.2 渐进式流代理（免登录，票据校验）

`GET /api/play/stream/{ticket}`

- 无 Range：返回 200 + `Content-Type` + `Content-Length`（上游已知时）+ `Accept-Ranges: bytes`；
- 单段 Range：转发上游；上游 206 时原样返回 206 + `Content-Range` + `Content-Length`；
- 上游忽略 Range 返回 200 时，按 200 全量转发（播放可用，seek 受限）；
- 越界：416 + `Content-Range: bytes */{total}`；
- `HEAD`：同样校验票据，只返回头部；
- 上游非 2xx：502 + 中文错误文本；HTML 响应（防盗链页）识别为“播放链接已失效，请重新播放”；
- 客户端断开（seek）时捕获 `IOException` 并关闭上游输入流，不记为业务错误。

`Content-Disposition` 不下发（播放不是下载）。

### 3.3 HLS 代理（免登录，票据校验）

- 入口：`GET /api/play/hls/{ticket}/master.m3u8`
- 子资源：`GET /api/play/hls/{ticket}/{childToken}`（子 playlist、分片、密钥、init map）
- 响应类型：
  - playlist：`application/vnd.apple.mpegurl`，`Cache-Control: no-store`；
  - 分片/密钥：透传上游 `Content-Type`，未知时 `application/octet-stream`；
  - 支持 `Range` 透传（`#EXT-X-BYTERANGE` 场景由 hls.js 发起）。
- 错误与渐进式一致；子 token 不存在/过期返回 404。

## 4. 票据与子 token 模型

### 4.1 PlaybackTicket

| 字段 | 说明 |
|---|---|
| token | 32 字节 `SecureRandom` 的 base64url 编码，256-bit |
| userId | 创建者，仅用于审计与配额，不参与无头请求鉴权 |
| pageUrl / formatId | 原始解析请求 |
| mode | progressive / hls |
| upstreamUri | 创建时解析出的上游地址（含 query token） |
| requestHeaders | Referer / Cookie / User-Agent / 是否走出口代理 |
| mime | 建议的浏览器 Content-Type |
| createdAt / expiresAt | LocalDateTime |
| children | HLS 子 token → {uri, contentTypeHint, createdAt} |

- 存储：Caffeine，`expireAfterWrite = PLAY_TICKET_TTL_SECONDS`，`maximumSize = 5000`；
- 单用户配额 `PLAY_MAX_TICKETS_PER_USER`，超出时淘汰该用户最旧票据；
- 票据 token 不写日志；日志只记录 `userId`、`mode`、host、formatId 与是否命中缓存；
- 票据不落库，服务器重启后失效属于预期行为。

### 4.2 子 token

- 每个 HLS 叶子 URI 生成 18 字节随机 token；
- 子 token 生命周期跟随票据；票据过期即全部失效；
- 单票据上限 `PLAY_CHILD_MAX_PER_TICKET`，超限淘汰最早登记项；
- 重复 URI 在当前票据内复用同一 token，避免刷新 playlist 时无限增长；
- 子 token 不可跨票据使用。

## 5. 播放源解析合同

### 5.1 解析顺序

`PlaybackSourceService.resolve(url, formatId, user)`：

1. 若 `formatId` 含 `+` 或对应 `FormatInfo.needsMerge()` → 直接拒绝；
2. 按平台分发（与 DownloadController 分支保持一致，只取地址不下载）：

| 条件 | 取流方式 | 请求头 |
|---|---|---|
| `directFileParser.supports` | 原 URL | UA |
| `douyinParser.supports` | `tryDirectUrl`，失败回退 `ytDlp.directUrl` | 平台 Referer |
| `instagramParser.supports` | `ytDlp.directUrl` | 可选 cookies |
| `qqMusicParser.supports` | `resolveAudioUrl` | `Referer: https://y.qq.com/` |
| `kugouMusicParser.supports` | `resolveAudioUrl` | `Referer: https://www.kugou.com/` |
| `neteaseMusicParser.supports` | `resolveAudioUrl` | `Referer: https://music.163.com/` |
| `netMirrorParser.supports` | `resolveDownloadUrl` | 站点 Referer（按 isTmdbWatch 分支） |
| `tubiParser.supports` | `resolveDownloadUrl` | UA |
| `bbcParser.supports` | 变体 map（缺缓存时重解析一次） | UA |
| `cgtnParser.supports` | 新增 `resolveStreamUrl` | UA |
| `amasianTvParser.supports` | `resolveStreamUrl` | UA |
| `tencentParser.supports` | `directUrl` | `Referer: https://v.qq.com/` + UA |
| CCTV / VIP / 华视 / 酷播 / 小红书 / 微博 | 首期明确不支持 | — |
| 其他 | `ytDlp.directUrl(url, formatId, cookies)` | 按平台 cookies；googlevideo 走出口代理 |

3. 判定模式：URI 路径以 `.m3u8` 结尾，或 HEAD 响应 `Content-Type` 含 `mpegurl` → `hls`；否则 `progressive`；
4. 生成票据并返回。

### 5.2 不新增的行为

- 不修改 `VideoInfo` / `FormatInfo` 字段；
- 不把直链回传前端（只回票据）；
- 不改变解析缓存 TTL 与键规则；
- 不重构既有 parser 下载方法；CGTN 只新增一个只读取流地址的方法。

## 6. HLS 改写规则

### 6.1 识别

- 内容含 `#EXT-X-STREAM-INF` 或 `#EXT-X-MEDIA:` → master；
- 否则按 media playlist 处理（仍逐行改写 URI）。

### 6.2 需改写的 URI 位置

| 位置 | 处理 |
|---|---|
| `#EXT-X-STREAM-INF` 下一行的变体 URI | 登记 + 替换 |
| `#EXT-X-KEY:URI="..."` | 登记 + 替换（AES-128 密钥） |
| `#EXT-X-MAP:URI="..."` | 登记 + 替换（fMP4 init） |
| `#EXT-X-MEDIA:...,URI="..."` | 登记 + 替换（音轨/字幕组） |
| `#EXT-X-I-FRAME-STREAM-INF:...URI="..."` | 登记 + 替换 |
| `#EXT-X-PART:...URI="..."` | 登记 + 替换（LL-HLS） |
| `#EXT-X-PRELOAD-HINT:...URI="..."` | 登记 + 替换 |
| `#EXT-X-RENDITION-REPORT:...URI="..."` | 登记 + 替换 |
| 其他非 `#` 开头的 URI 行（分片） | 登记 + 替换 |

### 6.3 规则

- 相对 URI 以“上游最终 URL（跟随重定向后）”为基准解析；
- 保留 `#EXT-X-BYTERANGE`、`#EXT-X-DISCONTINUITY`、`#EXT-X-PROGRAM-DATE-TIME` 等指令原样；
- 只替换 URI 文本，不重排标签、不修改时长/序号；
- 上游 playlist 响应非 2xx、为空或明显是 HTML 时返回 502；
- 上游 playlist 编码按 UTF-8 读取，BOM 去除；输出 UTF-8 无 BOM。

### 6.4 上游请求头

- 所有 playlist、分片、密钥请求统一附加票据记录的 `Referer / Cookie / User-Agent`；
- googlevideo / youtube.com 域名按 `ytDlp.proxy()` 走配置出口代理，其他域名默认直连；
- 重定向最多 `PLAY_MAX_REDIRECTS` 跳，每一跳都重新做地址策略校验。

## 7. 错误映射

| 场景 | HTTP | 行为 |
|---|---|---|
| 票据不存在/过期 | 404 | 前端提示“播放地址已过期，请重新播放” |
| 上游 404/403/410 | 502 | 提示“播放地址已失效，请重新播放” |
| 上游 5xx | 502 | 提示“视频源暂时不可用，请稍后重试” |
| 上游返回 HTML | 502 | 同上，防止把防盗链页面当媒体播放 |
| Range 越界 | 416 | `Content-Range: bytes */total` |
| 多段 Range | 416 | 首期明确不支持，返回规范错误 |
| 客户端主动断开 | — | 只记录 debug，不抛业务异常 |
| PLAY_ENABLED=false | 400 | 创建票据时返回业务错误 |

错误响应统一走 `BusinessException` + 全局异常处理；免登录代理端点无法返回 JSON 时，可直接写纯文本错误体并设置对应状态码。

## 8. 配置合同

三处 `.env`（`backend/.env`、根 `.env`、`.env.example`）必须键集合一致：

| 变量 | 默认 | 校验 |
|---|---|---|
| PLAY_ENABLED | true | 布尔 |
| PLAY_TICKET_TTL_SECONDS | 7200 | 60–86400 |
| PLAY_MAX_TICKETS_PER_USER | 3 | 1–10 |
| PLAY_CHILD_MAX_PER_TICKET | 4096 | 128–16384 |
| PLAY_UPSTREAM_CONNECT_TIMEOUT_MS | 10000 | 1000–60000 |
| PLAY_UPSTREAM_TIMEOUT_SECONDS | 3600 | 30–21600 |
| PLAY_BUFFER_BYTES | 65536 | 8192–1048576 |
| PLAY_BLOCK_PRIVATE_HOSTS | true | 布尔；生产不得关闭 |
| PLAY_MAX_REDIRECTS | 5 | 0–10 |

`application.yml` 使用 `${ENV:default}` 占位符，键名与上表一一对应，不硬编码。

## 9. 前端设计

### 9.1 组件状态机

~~~mermaid
stateDiagram-v2
    [*] --> Idle
    Idle --> Creating: 点击在线播放
    Creating --> Playing: 票据成功 + 播放器就绪
    Creating --> Failed: 创建失败
    Playing --> Switching: 切换清晰度
    Switching --> Playing: 新票据就绪
    Playing --> Idle: 关闭/卸载
    Failed --> Creating: 重试
~~~

### 9.2 行为

- `VideoResult.vue` 增加“在线播放”按钮（与“下载文件”并列，主次分明）；
- 点击后调用 `createPlayTicket({ url, formatId })`；
- `VideoPlayer.vue` props：`ticket`、`mode`、`mime`、`audioOnly`、`title`；
- 渐进式：`<video controls autoplay playsinline>` 或 `<audio controls autoplay>`，`src` 指向 `/api/play/stream/{ticket}`；
- HLS：
  - `Hls.isSupported()` → 动态 `import('hls.js')`，`new Hls({ enableWorker: true })`，`loadSource('/api/play/hls/{ticket}/master.m3u8')`；
  - 否则若 `canPlayType('application/vnd.apple.mpegurl')` → 直接设 `src`；
  - 都不支持 → 提示“当前浏览器不支持 HLS 播放”；
- 错误事件：`hls.on(Hls.Events.ERROR)` 与 `video.onerror` 统一映射中文提示；
- 关闭、切换、`onBeforeUnmount`、`props.info` 变化：`hls.destroy()`、`video.pause()`、`removeAttribute('src')`、`load()`；
- 播放按钮禁用规则：`needsMerge === true`，或 `ext` 不在可播放集合；
- 切换清晰度时先销毁再重新申请票据，避免旧 token 混用；
- 不把 token 写入 localStorage / URL 参数 / 日志。

### 9.3 打包

- hls.js 通过动态 `import()` 进入独立 chunk；
- 若 chunk 超过现有 `chunkSizeWarningLimit`，在 `manualChunks` 中显式拆出 `vendor-hls`，不提高全局告警阈值来掩盖问题。

## 10. 安全不变量

1. **鉴权**：创建票据必须 JWT；播放代理只认票据，票面 token 即能力，禁止同时接受裸 URL。
2. **白名单**：仅新增 `/api/play/stream/*`、`/api/play/hls/*`；不得把 `/api/play/ticket` 加入白名单；不得把 JWT 放 URL。
3. **SSRF**：
   - 只允许 `http/https`；
   - 解析 DNS 后校验全部 A/AAAA，拒绝 loopback、私网、链路本地、ULA、组播、unspecified、保留地址与云 metadata；
   - 重定向逐跳校验；拒绝非 http(s) 跳转；
   - `PLAY_BLOCK_PRIVATE_HOSTS` 关闭仅限本地开发，生产默认 true；
   - 不代理用户直接输入的任意上游地址：直链文件与 yt-dlp 结果同样过策略。
4. **隔离**：票据绑定 userId；不同用户票据互不可用；日志不含 Cookie、JWT、完整上游 query。
5. **资源**：票据、子 token、缓冲均有上限；客户端断开必须关闭上游流；不得无限缓存完整媒体字节。
6. **数据**：播放不写数据库、不修改 temp 表下载状态、不影响解析缓存。

## 11. 性能与资源边界

- 单次播放占用一个 Servlet 线程（阻塞式流式转发），与现有下载实现一致；首期不做异步 Servlet；
- 转发缓冲区默认 64KB；
- 上游连接超时 10s；流持续时间上限 1h（可配置）；
- HLS 分片大小由上游决定，不额外缓存；同一分片重复请求直接回源；
- 单用户票据上限 3，避免单用户开过多并发流；
- 已知限制：大量并发播放会占用与并发数相当的 Tomcat 线程，需要后续异步化或切片缓存才能扩展。

## 12. 验证矩阵

| 范畴 | 场景 | 通过条件 |
|---|---|---|
| 鉴权 | 未登录创建票据 | 401 |
| 鉴权 | 过期/伪造票据播放 | 404 |
| 渐进式 | 无 Range | 200 + Content-Type + Accept-Ranges |
| 渐进式 | `bytes=0-1023` | 206 + Content-Range + 1024 字节 |
| 渐进式 | 越界 Range | 416 + `bytes */total` |
| 渐进式 | 浏览器拖动 | 连续 206 请求，画面跳转 |
| HLS | master 改写 | 响应中无外链域名，全部为 `/api/play/hls/...` |
| HLS | media playlist + 分片 | 连续 200，hls.js 播放 |
| HLS | AES-128 | 密钥 URI 也被改写并成功取回 |
| HLS | EXT-X-BYTERANGE | 分片 Range 透传正确 |
| HLS | 子 token 越权/过期 | 404 |
| 平台 | yt-dlp 通用 / YouTube 单文件 / 抖音 | 可播放 |
| 平台 | 音乐三平台 | 可试听，Referer 生效 |
| 平台 | Tubi/BBC/CGTN/Amasian/腾讯 | HLS 可播放 |
| 平台 | CCTV/VIP/华视/酷播 | 明确中文“不支持在线播放” |
| 前端 | 播放中切换清晰度 | 旧实例销毁，新票据生效 |
| 前端 | 关闭/卸载/重新解析 | 无残留 hls 实例与网络请求 |
| 前端 | `needsMerge` 格式 | 播放按钮禁用并有提示 |
| 回归 | 解析/下载/magnet/ed2k/ftp | 行为不变，白名单仅新增两条 |
| 安全 | 私网/loopback 直链 | 拒绝且不发起连接 |

实施后需真实执行的命令（本轮不执行）：

~~~powershell
mvn -q clean compile -DskipTests
# 重启后端（SERVER_PORT=8082）
npm run build   # 在 frontend 目录
~~~

并记录真实 Exit Code、curl 输出、浏览器冒烟结果；未验证项必须明确标注。

## 13. 未决项

1. CCTV 是否需要在二阶段改为非 h5e 端点或复用 Node sidecar 解密后播放；
2. VIP/华视/酷播/小红书/微博是否追加“解析期直链缓存”，需要评估令牌时效与内存占用；
3. YouTube 高清 `bv+ba` 是否需要服务端转封装，属于独立方案；
4. HLS 直播（滑动窗口）是否需要额外刷新策略；
5. 并发播放的 Servlet 线程模型是否需要异步化，取决于实际并发量。
