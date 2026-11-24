# ClipFetch 在线播放二期 技术规格（SPEC）

> 日期：2026-11-23
> 前置规格：`.trae/documents/spec/2026-11-23-online-playback-spec.md`（首期：票据 + 同源代理 + HLS + VideoResult 内嵌）
> 实施计划：`.trae/documents/plan/2026-11-23-online-playback-phase2-plan.md`

## 0. 实施状态

> 截至 2026-11-23（Asia/Shanghai），二期各 Task 落地情况：

| Task | 内容 | 状态 | 验证情况 |
|---|---|---|---|
| Task 1 | 票据空闲滑动续期 + 最大寿命硬上限 + 单票据并发放流上限 | 已完成 | 编译 Exit 0；运行时未登录/无效票据 401/404 断言通过；30 分钟空闲与 6 小时硬上限逻辑已实现，未做长时间等待实测 |
| Task 2 | 播放进度记忆数据层（V27、Service、`GET/POST /api/play/progress`、票据 `resume*` 字段） | 已完成 | 见下文「Task 2 已验证项」；SQLite Flyway 到 V27 通过，MySQL V27 脚本已落地但未用真实实例执行 |
| Task 3 | 平台扩展（CCTV/VIP/酷播/小红书/微博，除华视、YouTube） | 已完成（真实链接未验） | 编译 Exit 0；CCTV 分支确认进入真实取流；当时华视仍拒绝，已由 Task 4 放行；各真实平台端到端播放待手动验证 |
| Task 4 | 华视影院在线播放 | 已完成（真实剧集未验） | 编译 Exit 0；前端构建 Exit 0；华视分支已放行并返回明确 400；真实 15~25 秒广告等待端到端未验证 |
| Task 5 | YouTube 高清合并（后端：merge 票据、后台 yt-dlp、`/prepare`、本地 Range、启动清理） | 已完成（成功合并未验） | 编译 Exit 0；merge 生命周期（PREPARING→FAILED）、409、prepare 400/404、启动清理均实测通过；真实视频合并成功与本地 Range 200/206/416 未验证（当前网络下 YouTube 返回 video unavailable） |
| Task 6 | 前端续播提示与进度上报 | 已完成（浏览器端未验） | `npm run build` Exit 0；API 封装、续播跳转、进度节流上报、merge 轮询与清理逻辑已实现；真实浏览器续播/关闭保存未实测（需 8082 重启后配合真实可播链接） |
| Task 7 | 整体验证与收尾 | 已完成 | 编译/构建 Exit 0；隔离 SQLite Flyway 到 V27；鉴权、进度读写与校验、404/416、merge 生命周期与 409、prepare 400/404 均实测通过；三处 `.env` 218 键一致；36 个文本文件无 BOM；真实平台与浏览器端到端仍待手动验证 |

## 1. 目标与范围

二期在首期基础上增加四类能力：

1. **平台补齐**：CCTV（明文 HLS）、VIP、酷播、华视影院、小红书、微博。
2. **YouTube 高清合并**：`formatId` 形如 `137+140` 的音视频合并格式可在线播放。
3. **票据生命周期**：空闲滑动续期 + 最大寿命硬上限 + 单票据并发放流上限。
4. **播放进度记忆**：按用户 + 视频 URL 保存进度，再次播放自动续播。

非目标：图文轮播播放、CCTV h5e 边下边播（当前为整片预解密后播放）、多实例票据共享、播放历史列表页。

## 2. 现有实现基线

| 组件 | 现状 |
|---|---|
| `PlaybackController` | `POST /api/play/ticket`（需登录）、`GET/HEAD /api/play/stream/{ticket}`、`GET /api/play/hls/{ticket}/master.m3u8`、`GET /api/play/hls/{ticket}/{childToken}` |
| `PlaybackTicket` | token/userId/pageUrl/formatId/mode/upstreamUri/referer/cookie/userAgent/useProxy/mime/audioOnly/createdAt/expiresAt/children |
| `PlaybackTicketStore` | Caffeine `expireAfterWrite`、单用户最多 3 张、HLS 子 token pin/FIFO |
| `PlaybackProxyClient` | JDK HttpClient 流式转发、Range、重定向逐跳 SSRF 校验、playlist 拉取 |
| 白名单 | `/api/play/stream/*`、`/api/play/hls/**` |
| Flyway | 最大版本 `V26` |

二期不改变首期的端点语义，仅在票据模型、响应字段与流分配上扩展；新增端点见 §5、§6。

## 3. 总体流程

```
点击「在线播放」
  → POST /api/play/ticket（登录）
      ├─ 平台取流（同步）
      ├─ 读取播放进度（若有）
      └─ 模式判定
          ├─ progressive / hls：立即返回票据
          └─ merge（YouTube 合并）：立即返回 preparing，后台合并
  → 播放器挂载
      ├─ progressive：/api/play/stream/{ticket}
      ├─ hls：/api/play/hls/{ticket}/master.m3u8
      └─ merge：轮询 /api/play/prepare/{ticket} → ready 后 /api/play/stream/{ticket}
  → 播放中每 5 秒上报 /api/play/progress（登录）
  → 关闭/暂停/播完再上报一次
```

## 4. 票据生命周期

### 4.1 字段扩展

`PlaybackTicket` 新增：

| 字段 | 类型 | 说明 |
|---|---|---|
| `lastAccessAt` | `LocalDateTime` | 最近访问时间（滑动续期） |
| `hardExpiresAt` | `LocalDateTime` | 创建时间 + 最大寿命，不可续期 |
| `activeStreams` | `Deque<ActiveStream>` | 当前活跃流句柄（仅内存） |
| `mergeStatus` | `String` | `null` / `PREPARING` / `READY` / `FAILED` |
| `mergeProgress` | `int` | 0~100 |
| `mergeError` | `String` | 失败原因（中文） |
| `localFile` | `Path` | 合并落盘文件（仅 merge） |

`PlaybackTicket.Child` 不变。

### 4.2 滑动续期

- 缓存：`Caffeine.newBuilder().expireAfterAccess(PLAY_TICKET_IDLE_SECONDS).maximumSize(5000)`。
- `find(token)`：
  1. `getIfPresent`（Caffeine 会刷新 access 时间）；
  2. 若 `now > hardExpiresAt` → `invalidate` 并返回 `null`；
  3. 否则返回票据。
- 响应中的 `expiresAt` 固定返回 `hardExpiresAt`（前端不依赖空闲时间）。
- 不新增定时任务；过期由 Caffeine 与每次访问惰性处理。

### 4.3 并发放流上限

- `PLAY_MAX_STREAMS_PER_TICKET` 默认 3，作用于持续的字节流请求：`/api/play/stream/{ticket}` 的 GET（HEAD 不计数）与 `/api/play/hls/{ticket}/{childToken}` 的分片请求；m3u8 文本请求不占配额。
- 控制器在拿到 `Upstream` 后调用 `ticketStore.registerStream(ticket, handle)`：
  - 活跃数 `< max`：直接加入；
  - 活跃数 `>= max`：关闭队列中最早建立的句柄（关闭其 `InputStream`），再加入新句柄。
- 请求结束（正常/异常/客户端断开）在 `finally` 中 `unregisterStream`。
- 被关闭的旧请求表现为流中断，前端播放器自行报错；这是「新请求优先」的预期行为。
- HLS 分片请求很多但生命周期短，按同一规则计数即可。

### 4.4 单用户票据数

保持首期：`PLAY_MAX_TICKETS_PER_USER` 默认 3，创建新票据时淘汰该用户最旧票据，并同步清理 merge 缓存文件（见 §6.5）。

## 5. 播放进度记忆

### 5.1 数据模型

表名：`playback_progress`。Flyway `V27__playback_progress.sql`（SQLite 与 MySQL 同版本号）。

SQLite（实际落地脚本）：

```sql
CREATE TABLE playback_progress (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id          BIGINT  NOT NULL,
    source_key       VARCHAR(64)   NOT NULL,
    source_url       VARCHAR(2048) NOT NULL,
    platform         VARCHAR(64),
    title            VARCHAR(512),
    format_id        VARCHAR(256),
    mode             VARCHAR(16),
    position_seconds INT     NOT NULL DEFAULT 0,
    duration_seconds INT,
    completed        INT     NOT NULL DEFAULT 0,
    created_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          BIGINT  NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_playback_progress_user_key
    ON playback_progress (user_id, source_key, deleted);
CREATE INDEX idx_playback_progress_user
    ON playback_progress (user_id, updated_at);
```

MySQL（实际落地脚本）：

```sql
CREATE TABLE playback_progress (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '所属用户 ID',
    source_key VARCHAR(64) NOT NULL COMMENT '归一化 URL 的 SHA-256',
    source_url VARCHAR(2048) NOT NULL COMMENT '原始播放链接',
    platform VARCHAR(64) NULL COMMENT '平台标识',
    title VARCHAR(512) NULL COMMENT '标题快照',
    format_id VARCHAR(256) NULL COMMENT '最近播放清晰度',
    mode VARCHAR(16) NULL COMMENT 'progressive/hls/merge',
    position_seconds INT NOT NULL DEFAULT 0 COMMENT '上次位置（秒）',
    duration_seconds INT NULL COMMENT '总时长（秒）',
    completed TINYINT NOT NULL DEFAULT 0 COMMENT '1=已看完',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0=正常 非0=记录 id',
    UNIQUE KEY uk_playback_progress_user_key (user_id, source_key, deleted),
    KEY idx_playback_progress_user (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='播放进度记忆';
```

实体：`cc.ivera.parser.domain.PlaybackProgress`，遵循项目实体风格（`@TableName`、`@TableId(type = IdType.AUTO)`、`@TableLogic`、Lombok）。Mapper 接口放 `cc.ivera.parser.domain`（`@MapperScan` 自动扫描），继承 MyBatis-Plus `BaseMapper`，并新增 `resources/mapper/PlaybackProgressMapper.xml`，含 `findOwned`、`insertProgress`、`updateProgress` 三条自定义 SQL，全部带 `user_id` 与 `deleted=0` 条件。

### 5.2 URL 归一化与 source_key

1. `trim()`，拒绝空值。
2. `URI.create` 解析；仅允许 `http/https`，否则 400。
3. scheme、host 转小写；去掉 fragment；保留 path 与 query；去掉默认端口。
4. 重新拼成字符串后取 SHA-256，十六进制小写，作为 `source_key`。

已知限制：同一视频的不同链接形式（`youtu.be/xxx` 与 `youtube.com/watch?v=xxx`）视为不同记录，不做平台级 ID 归一。

### 5.3 写入规则

`PlaybackProgressService.save(userId, SaveCommand)`：

1. 校验：`url` 必填且长度 ≤ 2048；`positionSeconds` ∈ [0, 86400]；`durationSeconds` 为空或 ∈ [1, 86400]；有 duration 时 position ≤ duration + 5；`title` ≤ 512；`formatId` ≤ 256；`mode` 为空或 ∈ {progressive, hls, merge}。
2. `platform` 由 `Platform.from(url).name()` 自动写入，前端无需传。
3. `completed`：请求显式 `true`，或 `duration > 0 && position * 100 >= duration * PLAY_RESUME_COMPLETE_PERCENT`。
4. 查询 `(user_id, source_key, deleted=0)`：
   - 存在 → 更新 source_url/platform/title/format_id/mode/position/duration/completed/updated_at；
   - 不存在 → 插入。
5. 并发竞争由唯一索引兜底；捕获 `DuplicateKeyException` 后重查再改为更新。
6. 不向前端回传原始 cookie 或任何头信息。

### 5.4 读取与续播

`PlaybackProgressService.find(userId, url)` 返回：

```json
{ "positionSeconds": 754, "durationSeconds": 5400, "completed": false, "updatedAt": "2026-11-23T12:00:00" }
```

- `completed=true` 或 `duration > 0 && position / duration >= 95%` → `resumeSeconds = 0`。
- `positionSeconds < PLAY_RESUME_MIN_SECONDS` → `resumeSeconds = 0`。
- 否则 `resumeSeconds = positionSeconds`。

票据创建时（已登录）同步读取并写入响应；未登录不会走到票据创建。

## 6. API 契约

统一响应：`ApiResponse<T>`；业务错误抛 `BusinessException`，返回中文 `error`。

### 6.1 POST /api/play/ticket（扩展，需登录）

请求不变：

```json
{ "url": "https://...", "formatId": "137+140", "title": "视频标题" }
```

响应新增字段：

```json
{
  "ticket": "AbC...",
  "mode": "progressive|hls|merge",
  "mime": "video/mp4",
  "title": "视频标题",
  "audioOnly": false,
  "expiresAt": "2026-11-23T18:00:00",
  "resumeSeconds": 754,
  "resumeCompleted": false,
  "prepare": {
    "status": "PREPARING|READY|FAILED",
    "progress": 42,
    "error": null
  }
}
```

> 落地状态：`resumeSeconds`、`resumeCompleted` 已随 Task 2 落地；`prepare` 字段待 Task 5（YouTube merge）实现，当前票据响应不返回该字段。

- `prepare` 仅在 `mode=merge` 时非空；其余为 `null`。
- `mode=hls` 的 `mime` 为 `application/vnd.apple.mpegurl`。
- 平台不支持或取流失败：HTTP 400 + 中文 `error`。

新增后端错误文案（示例）：

| 场景 | 文案 |
|---|---|
| CCTV h5e 解密失败 | CCTV 解密失败：{Node 脚本最后一行}，请稍后重试 |
| 小红书/微博图文或多文件 | 该内容不是单个视频，暂不支持在线播放 |
| 酷播 VIP 未登录 | 酷播 1080p 为 VIP 专属，请先在「我的 Cookies → 酷播」完成 VIP 登录 |
| 华视等待后无流 | 华视影院解析失败：{上游原因}，请稍后重试 |
| YouTube 合并进行中 | 该视频正在准备中，请稍候再试 |

### 6.2 GET /api/play/prepare/{ticket}（新增，需登录）

响应：

```json
{ "status": "PREPARING", "progress": 42, "error": null }
```

- 票据不存在/过期：HTTP 404，中文文本。
- 非 merge 票据：HTTP 400「该播放不需要准备」。
- 轮询会刷新票据的空闲时间。
- 前端轮询间隔 1 秒；`READY`/`FAILED` 后停止。

### 6.3 GET/HEAD /api/play/stream/{ticket}（扩展，免登录）

- `progressive`：保持首期逻辑（上游 Range 透传 200/206/416）。
- `merge`：
  - `mergeStatus != READY` → HTTP 409，文本「视频正在准备中，请稍候」；
  - `READY` → 从 `localFile` 读取，按 `Range` 返回 206 + `Content-Range`；无 Range 返回 200 + 完整长度；非法/越界 Range 返回 416 + `Content-Range: bytes */总长度`。
  - 响应头：`Content-Type: video/mp4`、`Accept-Ranges: bytes`、`Cache-Control: no-store`。
- `hls` 票据访问 `/stream` 不属于约定用法，按首期逻辑处理（不额外拦截）。

### 6.4 GET /api/play/progress?url=...（新增，需登录）

```json
{
  "success": true,
  "data": { "positionSeconds": 754, "durationSeconds": 5400, "completed": false, "updatedAt": "..." }
}
```

无记录时 `data = null`。所有查询带 `user_id`。

### 6.5 POST /api/play/progress（新增，需登录）

请求：

```json
{
  "url": "https://...",
  "positionSeconds": 754,
  "durationSeconds": 5400,
  "formatId": "137+140",
  "mode": "progressive",
  "title": "视频标题",
  "completed": false
}
```

响应 `ApiResponse.ok(true)`。校验失败返回 400 中文错误。

### 6.6 HLS 端点

路径与行为不变。CCTV/VIP/酷播/华视返回的 m3u8 统一走 `/api/play/hls/{ticket}/master.m3u8`，其子资源改写逻辑复用首期实现。

## 7. 平台扩展规格

### 7.1 平台矩阵

| 平台 | 取流方法 | 模式 | 依赖 | 限制与提示 |
|---|---|---|---|---|
| CCTV | `CctvParser.resolvePlainStreamUrl` / `resolveEncryptedStreamUrl` | hls（明文）/ merge（h5e 预解密） | 明文 `hls_url`；明文清晰度不足或无明文时 Node 解密 sidecar | 明文低于所选档位时改走 h5e 整片解密 |
| VIP | `VipParser.resolveStreamUrl(url, formatId)` | hls/mp4 | `resolveDirect()` | 上游失败原样返回中文原因 |
| 酷播 | `IfyTvParser.resolveStreamUrl(url, formatId)` | hls | sidecar token（>576p） | 未登录 VIP 时明确提示 |
| 华视影院 | `HuaVodNetParser.resolveStreamUrl(url, formatId)` | hls | 15~25 秒广告等待 + 一次性令牌 | 前端 60 秒超时 |
| 小红书 | `RednoteParser.resolveVideoUrl(url)` | mp4 | 10 分钟解析缓存 | 仅单视频帖 |
| 微博 | `WeiboParser.resolveVideoUrl(url, cookieContent)` | mp4 | 10 分钟解析缓存 + 可选 cookies | 仅单视频帖 |
| YouTube 合并 | `YtDlpService.mergeToFile(...)` | merge | cookies + 出口代理 | 首次播放需等待准备 |

### 7.2 CCTV

1. `parse()` 同时读取 `hls_url` 与 `hls_h5e_url`：
   - 下载继续使用现有 `hls_h5e_url`（含解密链路）；
   - 若 `hls_url` 非空，用 `HlsClient.parseManifest` 解析明文变体，写入独立缓存 `plainCache`（键为播放页 URL）。
2. `resolvePlainStreamUrl(url, formatId)`：
   - `plainCache` 未命中 → 重新 `parse()` 一次；
   - 按 `formatId` 对应的高度匹配明文变体；找不到时取最高码率明文变体；
   - 未精确匹配且明文最高变体低于 h5e 同档位实际分辨率（如明文只有 270p、所选档位 h5e 为 720p）时返回 `null`，交给 h5e 解密，避免用户选高清却播低清；
   - 无明文缓存或变体为空 → 返回 `null`。
3. `resolveEncryptedStreamUrl(url, formatId)`：从 `cache` 取对应 h5e 变体（未命中取最高码率），无流时返回 `null`。
4. 在线播放分支：
   - 明文流满足所选档位 → 票据 `mode=hls`，走 `/api/play/hls/...`；
   - 仅 h5e → 票据 `mode=merge`、`mime=video/mp4`，后台 `PlaybackMergeService.submitCctvDecrypt` 调用 `CctvNodeDecryptSidecar.decryptToDirectory` 解密到 `cctv-<ticket>/out.mp4`，再移动到 `<cacheDir>/<ticket>.mp4` 并置 READY；
   - 前端复用 merge 的 `/prepare` 轮询与本地 Range 播放，无需按平台区分。
5. 解密进度取 Node 输出的 `seg X/Y`，映射为 0~99；READY 置 100。失败时票据 `FAILED`，错误透传 Node 脚本最后一行。
6. 解密工作目录 `cctv-<ticket>` 在任务结束后删除；异常残留由启动/建票清理按 TTL 递归删除。
7. 票据 Referer：`https://tv.cctv.com/`（若上游要求）。

### 7.3 VIP

1. 新增 `public String resolveStreamUrl(String url, String formatId)`：
   - 复用 `resolveDownload(url)` → `resolveDirect(url)`；
   - 仅接受 `http/https` 结果；
   - 将 `DownloadTarget.headers()` 中的 `Referer`/`User-Agent` 原样带入票据（`DownloadTarget` 无该头时使用平台默认值）。
2. 模式按 URL 是否含 `.m3u8` 判定。
3. 解析失败直接抛出上游中文错误。

### 7.4 酷播

1. 抽出 `download()` 中「URL → episodeKey → 清晰度 → play → 选择 `flvPathList` 中 `isHls=true` 的 `result`」为：
   `public String resolveStreamUrl(String url, String formatId)`。
2. `download()` 改为调用该方法，下载参数（`-N 16`、Referer）保持不变。
3. >576p 且 sidecar 无 token → 抛出与下载一致的 VIP 提示。
4. 票据 Referer：`https://www.kubb.tv/`。

### 7.5 华视影院

1. 将私有 `resolveStream(EpisodeRef)` 暴露为 `public String resolveStreamUrl(String url, String formatId)`，内部仍执行：
   取新令牌 → `sleepAd(adDurationMs)` → `postResolve` → 返回 m3u8。
2. 票据 Referer：`https://huavod.net/`。
3. 该分支允许票据创建耗时 15~25 秒；前端 `createPlayTicket` 单独使用 60 秒超时，播放按钮 loading 文案为「等待广告…」并提示「华视影院需要等待约 15~25 秒广告，请勿关闭页面」。
4. 每次播放都重新取令牌，不复用旧令牌。
5. 非 `/vodplay/{videoId}-{nid}-{formatId}.html` 链接返回 400「仅支持华视影院播放页链接…」。

### 7.6 小红书 / 微博

1. 新增 `public String resolveVideoUrl(String url)`（小红书）与
   `public String resolveVideoUrl(String url, String cookieContent)`（微博，复用已上传 cookies）：
   - 读取现有缓存；未命中时重新执行一次解析；
   - 仅当结果中恰好有 1 个 `type=video` 的媒体项时返回其直链；
   - 图文、多视频、无视频 → 返回 `null`，由 `PlaybackSourceService` 抛「该内容不是单个视频，暂不支持在线播放」。
2. 票据 Referer 使用平台播放页地址（小红书 `https://www.xiaohongshu.com/`，微博 `https://weibo.com/`）。
3. 直链通常为 CDN，若返回 403，提示「视频直链已失效，请重新解析后再播放」。

## 8. YouTube 高清合并（merge 模式）

### 8.1 触发条件

- `Platform.YOUTUBE` 且 `formatId` 包含 `+`（形如 `137+140`）。
- 单个 `formatId`（含 `IV*` 镜像）继续走首期单文件逻辑。
- 非 YouTube 平台的 `+` 格式仍拒绝：「该清晰度需要音视频合并，暂不支持在线播放，请下载后观看」。

### 8.2 任务生命周期

1. `POST /ticket` 同步校验参数与平台，创建票据（`mode=merge`、`mergeStatus=PREPARING`、`mergeProgress=0`），提交后台任务后立即返回。
2. 任务限制：
   - 全局并发 `PLAY_MERGE_CONCURRENCY`（默认 1）；
   - 单用户同时最多 1 个 merge 任务；再有请求 → 400「该视频正在准备中，请稍候再试」。
3. 执行：`yt-dlp`（复用 `YtDlpService` 的 cookies/代理/日志方式）
   `-f "<videoId>+<audioId>" --merge-output-format mp4 --no-playlist --newline --max-filesize <PLAY_MERGE_MAX_BYTES> -o "<cacheDir>/<ticket>.mp4" <url>`。
   - 落地实现为 `YtDlpService.mergeToFile(url, formatId, cookieContent, outputFile, maxBytes, timeoutSeconds, progress)`；`--no-playlist`、cookies 与代理由 `baseArgs` 统一加入，全程 30 分钟超时。
4. 进度：解析 `yt-dlp --newline` 的百分比行写入 `mergeProgress`；无法解析时按 5% 递增，最高 95%，完成置 100。
5. 成功：`mergeStatus=READY`、记录 `localFile`；
   失败：`FAILED` + 中文 `mergeError`（含上游最后一行错误）。
6. 前端轮询 `/api/play/prepare/{ticket}`，`READY` 后挂载播放器。
7. 票据失效（空闲/硬上限/被同用户新票据淘汰）时删除本地文件。

### 8.3 缓存与清理

- 目录：`PLAY_MERGE_CACHE_DIR`（默认 `./data/play-cache`，相对启动目录；启动时自动创建）。
- 文件命名：`<ticket>.mp4`。
- 清理策略：
  - 票据过期/淘汰时删除；
  - 应用启动 `@PostConstruct` 清扫目录中修改时间早于 `PLAY_MERGE_TTL_SECONDS` 的文件；
  - 创建新票据时顺带清扫一次过期文件。
- 不使用 `@Scheduled`。

### 8.4 本地 Range 读取

- 解析 `Range: bytes=start-end`：
  - 缺省 end → `total-1`；`start >= total` → 416 + `Content-Range: bytes */total`；
  - 合法 → 206，写 `Content-Range: bytes start-end/total` 与 `Content-Length: end-start+1`；
  - 无 Range → 200 + 完整文件。
- 使用 `RandomAccessFile` 顺序写出，缓冲区复用 `PLAY_BUFFER_BYTES`。
- HEAD 请求只返回头，不写正文。
- 合并文件未就绪时返回 409 文本，不返回 JSON（`<video>` 不解析 JSON）。

## 9. 前端规格

### 9.1 VideoResult

- 可播放判定：
  - `serverOnly=true` 且 `ext ∈ {mp4,m4v,webm,mov,mp3,m4a,aac,flac,ogg,opus,wav}` → 可播；
  - `needsMerge=true`：YouTube 平台允许播放（走 merge），其他平台禁用并提示「需下载后观看」；
  - `formatId=images` → 不可播。
- 点击播放：
  - 未登录 → 打开登录弹层；
  - 华视平台请求超时设为 60 秒；
  - 响应含 `resumeSeconds > 0` 时，由 `VideoPlayer` 在视频下方显示「已从 mm:ss 继续播放」与「从头播放」（跳转逻辑在播放器内，便于直接操作媒体元素）。
- 进度保存：
  - 转发 `VideoPlayer` 的 `progress` 事件到 `POST /api/play/progress`；
  - 节流 `PLAY_PROGRESS_SAVE_INTERVAL_SECONDS`（前端按 5 秒）；
  - 暂停、播完、关闭、组件卸载时立即保存一次（播完带 `completed=true`）。
- 关闭播放器时取消准备轮询并停止保存定时器。

### 9.2 VideoPlayer

新增 props：`startAt`（秒，默认 0）。
新增 emits：`progress({ position, duration, completed })`。

| 模式 | 行为 |
|---|---|
| progressive | `src=/api/play/stream/{ticket}`；`loadedmetadata` 后若有 `startAt` 且 `startAt < duration - 3` 则 `currentTime = startAt` |
| hls | hls.js `MANIFEST_PARSED` 后跳转；原生 HLS 用 `loadedmetadata` |
| merge | 父组件轮询准备态；`READY` 后以 progressive 方式播放；`FAILED` 显示 `error` |

- `timeupdate` 每 5 秒发一次 `progress`；`ended` 发 `{completed:true}`。
- 关闭/卸载：`hls.destroy()`、清空 `src`、停止事件监听。
- 「从头播放」：`currentTime=0`，并立即上报一次 `position=0, completed=false`。

### 9.3 api/video.js

新增：

```js
export async function getPlayProgress(url) { ... }   // GET /play/progress?url=...
export async function savePlayProgress(payload) { ... } // POST /play/progress
export async function getPrepareStatus(ticket) { ... }  // GET /play/prepare/{ticket}
```

全部走 `request.js`；错误用 `errMsg(error)`。

## 10. 配置

所有键必须在 `application.yml` 占位符与 `backend/.env`、根 `.env`、`.env.example` 三处同时存在、名称一致。

```yaml
app:
  play:
    ticket-idle-seconds: ${PLAY_TICKET_IDLE_SECONDS:1800}
    ticket-max-lifetime-seconds: ${PLAY_TICKET_MAX_LIFETIME_SECONDS:21600}
    max-streams-per-ticket: ${PLAY_MAX_STREAMS_PER_TICKET:3}
    resume-min-seconds: ${PLAY_RESUME_MIN_SECONDS:5}
    resume-complete-percent: ${PLAY_RESUME_COMPLETE_PERCENT:95}
    progress-save-interval-seconds: ${PLAY_PROGRESS_SAVE_INTERVAL_SECONDS:5}
    merge-cache-dir: ${PLAY_MERGE_CACHE_DIR:./data/play-cache}
    merge-max-bytes: ${PLAY_MERGE_MAX_BYTES:4294967296}
    merge-ttl-seconds: ${PLAY_MERGE_TTL_SECONDS:86400}
    merge-concurrency: ${PLAY_MERGE_CONCURRENCY:1}
```

> 落地状态：已随 Task 1/2 写入 `application.yml` 与三处 `.env` 的键为 `ticket-idle-seconds`、`ticket-max-lifetime-seconds`、`max-streams-per-ticket`、`resume-min-seconds`、`resume-complete-percent`；`progress-save-interval-seconds` 与各 `merge-*` 键待 Task 5/6 落地后再同步。

- 删除首期 `PLAY_TICKET_TTL_SECONDS`（不再保留兼容）。
- 首期其余 `PLAY_*` 键保持不变。

## 11. 安全

1. 白名单不新增任何路径：`/api/play/stream/*`、`/api/play/hls/**` 继续凭票据访问。
2. `/api/play/ticket`、`/api/play/prepare/*`、`/api/play/progress` 必须登录；进度查询与写入强制 `user_id`。
3. 上游地址继续经过 `UpstreamTargetPolicy`（仅 http/https、拒绝私网/metadata），重定向逐跳校验。
4. merge 落盘只写 `PLAY_MERGE_CACHE_DIR`，文件名由服务端生成（ticket token），不接受用户路径。
5. 日志不打印 cookie、JWT、`.env` 值、完整播放直链查询串；仅记录 host、平台、模式、状态。
6. 进度接口限制字段长度与数值范围，避免超长写入。

## 12. 验收测试

| # | 场景 | 期望 |
|---|---|---|
| 1 | `mvn -q clean compile -DskipTests` / `npm run build` | Exit 0 |
| 2 | 启动后端 | Flyway schema 到 V27，无 Bean 装配错误 |
| 3 | 播放直链 MP4，拖动到 5:00 后关闭，重开 | 自动从约 5:00 继续，误差 ≤5 秒 |
| 4 | 播完视频后重开 | 从头播放（completed 已记录） |
| 5 | 同一视频用两个账号分别播放 | 进度互不可见 |
| 6 | 单票据并发 4 个 progressive 请求 | 第 1 个被中断，后 3 个正常 |
| 7 | 票据空闲超过 30 分钟 | `/stream` 404；持续访问可续至硬上限 6 小时 |
| 8 | CCTV 明文节目 / 仅 h5e 节目 | 前者直接播放；后者显示准备进度，READY 后本地 Range 播放 |
| 9 | VIP、酷播、华视、小红书单视频、微博单视频 | 真实链接可播放；缺登录态时提示对应平台 |
| 10 | YouTube 1080p 合并格式 | 返回 PREPARING 且进度递增，READY 后可播放/拖动/续播 |
| 11 | merge 文件超过 `PLAY_MERGE_MAX_BYTES` | 任务失败，中文提示 |
| 12 | 票据过期后检查缓存目录 | 对应 `.mp4` 已删除 |
| 13 | 三处 `.env` 键集合 | 完全一致 |
| 14 | 新增/修改文本文件 | UTF-8 无 BOM |

### Task 2 已验证项（2026-11-23）

在隔离 SQLite 数据库 + 备用端口 8099 运行后端完成（不影响 8082 开发实例），真实断言结果：

- `#1` 编译：`mvn -q clean compile -DskipTests` Exit 0。
- `#2` SQLite：Flyway 成功迁移到 V27（启动日志确认）；MySQL V27 脚本已落地但未用真实实例执行。
- 无记录时 `GET /api/play/progress` 返回 `data=null`。
- URL 归一化：大小写、默认端口、fragment 变体写入同一记录，重复保存仅一行（DB 断言 `MAX_ROWS_PER_USER_SOURCE=1`）。
- 跨用户隔离：账号 B 不可见且不能覆盖账号 A 的进度。
- `position` 超过 `duration+5` 返回 400 中文错误。
- 票据续播：30 秒返回 `resumeSeconds=30, resumeCompleted=false`；3 秒返回 `resumeSeconds=0`；95% 处返回 `resumeSeconds=0, resumeCompleted=true`。
- `#13` 三处 `.env` 键集合一致（`PLAY_KEY_COUNT=13`）。
- `#14` 本轮 11 个相关文本文件无 BOM。

未覆盖：`#3/#4` 端到端续播需前端 Task 6；`#6/#7` 长时间空闲与并发放流未做真实时长/高并发实测；MySQL 迁移未实机执行。

### Task 3 已验证项（2026-11-23）

- 编译：`mvn -q clean compile -DskipTests` Exit 0。
- 隔离 SQLite + 8099 启动正常，Flyway 到 V27。
- 未登录 `GET /api/play/progress` 仍为 401；直链 `POST /api/play/ticket` 返回 `mode=progressive`，续播字段正常。
- CCTV 分支：用 CCTV 页面链接请求票据，确认进入真实取流链路并以「CCTV 页面返回状态码 404」失败，而非「该平台暂不支持在线播放」。
- 华视仍返回 400「该平台暂不支持在线播放，请下载后观看」（Task 3 时点，Task 4 已放行）。

未覆盖：CCTV/VIP/酷播/小红书/微博的真实可播放链接端到端播放未验证（需要真实链接、有效 cookies 与 sidecar 登录态）；小红书/微博 `null` 分支仅代码审查。

### Task 4 已验证项（2026-11-23）

- 后端编译 Exit 0；前端 `npm run build` Exit 0。
- 隔离 SQLite + 8099 启动正常，Flyway 到 V27。
- `POST /api/play/ticket` 传 `https://huavod.net/foo`：返回 400「仅支持华视影院播放页链接（形如 /vodplay/204771-1-1.html）」，证明华视已从「不支持在线播放」放行并进入取流校验。
- 直链票据仍返回 `mode=progressive`，无回归。

未覆盖：真实 huavod 剧集链接的 15~25 秒广告等待与 m3u8 播放未验证（需真实剧集链接与可用网络）；前端 60 秒超时与广告提示文案仅构建验证，未在浏览器实测。

### Task 5 已验证项（2026-11-23）

- 编译：`mvn -q clean compile -DskipTests` Exit 0。
- `#2` 隔离 SQLite + 8099 启动正常，Flyway 到 V27，`PlaybackMergeService` Bean 装配无错误。
- merge 状态机：YouTube merge 票据响应 `prepare={"status":"PREPARING","progress":0,"error":null}`，约 5 秒后经 `GET /api/play/prepare/{ticket}` 读到 `FAILED`，`error` 为中文并附带上游最后一行（`合并失败：ERROR: [youtube] ...`）。
- `#10`（部分）`GET /api/play/stream/{ticket}` 对 FAILED 票据返回 HTTP 409，正文「合并失败：...」，不再落回上游代理。
- `prepare` 边界：未知/过期票据 404「播放地址已过期，请重新播放」；非 merge 票据 400「该播放不需要准备」；未登录 401（该端点不在白名单，符合预期）。
- 非 YouTube 平台请求含 `+` 的 `formatId`：`POST /api/play/ticket` 返回 400「该清晰度需要音视频合并，暂不支持在线播放，请下载后观看」。
- `#12` 启动清理：预置一个 mtime 超过 TTL 的 `old-test.mp4` 与一个新文件，重启后日志显示「清理过期播放缓存: old-test.mp4」，新文件保留（验证后新文件已手动删除，目录残留 0）。
- `#13` 三处 `.env` 键集合一致：`PLAY_` 键各 17 个，两两 diff 为 0。
- `#14` 本轮 Java/yml/md 文本文件无 BOM。

未覆盖：`#10` 成功合并后 `READY` 与本地 Range 200/206/416 未实测——当前网络下 YouTube 对测试视频返回 `This video is unavailable`，无法取得可合并的真实视频；`#11` 超限失败仅代码路径审查；`#12` 票据过期（非启动清扫）触发的文件删除未做长时实测；合并并发上限与 30 分钟超时未做压力实测。

### Task 6 已验证项（2026-11-23）

- `npm run build` Exit 0（117 modules，`VideoPlayer` 与 `video` API 独立分包，hls 仍为异步 chunk）。
- 代码落地核对：
  - `api/video.js` 新增 `getPlayProgress` / `savePlayProgress` / `getPrepareStatus`，错误优先取后端 `error`，`prepare` 的 text/plain 404 取响应正文。
  - `VideoResult`：`needsMerge` 仅 YouTube 放行播放，其他平台仍提示下载；merge 票据按 1 秒轮询 `prepare`，`READY` 挂载播放器、`FAILED` 显示后端中文错误并熔断；关闭/换解析结果/卸载时 `clearInterval`。
  - `VideoPlayer`：`startAt` 在 `loadedmetadata`（progressive/原生 HLS）与 `MANIFEST_PARSED`（hls.js）跳转一次；`timeupdate` 5 秒节流上报，暂停/播完/卸载立即上报；「从头播放」归零并立即上报 `position=0`。
- 未覆盖：真实浏览器中的播放→关闭→重开续播（误差 ≤5 秒）、播完重开从头、merge `READY` 后播放与轮询清理未实测——需要重启 8082 后端并配合真实可播放链接；本次仅构建与代码路径核对。

### Task 7 整体验收项（2026-11-24）

隔离 SQLite（`data/verify-t7`）+ 备用端口 8099 启动后端（`MAGNET_V2_ENABLED=false`），全程不影响 8082 的 IDEA 实例。

| 检查 | 实测结果 |
|---|---|
| `mvn -q clean compile -DskipTests` | Exit 0 |
| `npm run build` | Exit 0 |
| Flyway | `Successfully applied 27 migrations ... now at version v27` |
| 未登录 `GET /api/play/progress` | 401 |
| 未登录 `GET /api/play/prepare/*` | 401 |
| 无记录 `GET /api/play/progress` | 200，`data=null` |
| `POST /api/play/progress` → 再查 | 保存成功，读回 `position=42, duration=600, completed=false` |
| 进度越界校验（700/600） | 400「播放位置不能超过视频总时长」 |
| 未知票据 `/stream` | 404「播放地址已过期，请重新播放」 |
| 多段 Range `bytes=0-1,5-6` | 416「暂不支持多段 Range 请求」 |
| 正常 Range `bytes=0-99` | 206，返回 100 字节 |
| 后缀 Range `bytes=-50` | 206，返回 50 字节 |
| HEAD `/stream` | 200 |
| 非 YouTube + `137+140` | 400「该清晰度需要音视频合并，暂不支持在线播放，请下载后观看」 |
| YouTube merge 票据 | `mode=merge`，`prepare={"status":"PREPARING","progress":0}` |
| merge 轮询 | 约 3 秒后 `FAILED`，`error=合并失败：ERROR: [youtube] ... This video is unavailable` |
| FAILED 票据 `/stream` | 409，正文为中文错误 |
| `/prepare` 未知票据（已登录） | 404「播放地址已过期，请重新播放」 |
| `/prepare` 非 merge 票据 | 400「该播放不需要准备」 |
| 三处 `.env` 键集合 | 各 218 键，两两 diff 0；`PLAY_` 键 17 个 |
| 文本文件 BOM | 36 个改动/相关文本文件全部无 BOM |
| merge 失败清理 | `backend/data/play-cache` 残留 0 |

未覆盖（真实环境限制）：各平台真实链接端到端播放、YouTube 成功合并后 READY 与本地 Range、真实浏览器续播与 merge 轮询、MySQL 实例上的 V27 迁移、30 分钟空闲与 6 小时硬上限长时验证。

### CCTV h5e 预解密播放（2026-11-24）

隔离 SQLite + 备用端口 8099 启动后端（`MAGNET_V2_ENABLED=false`），不影响 8082 的 IDEA 实例；测试视频 `https://tv.cctv.com/2018/08/01/VIDEjaWf5HOkjRxTiFyHHSGA180801.shtml`。

| 检查 | 实测结果 |
|---|---|
| `mvn -q clean compile -DskipTests` | Exit 0 |
| Node 脚本全量解密（直接执行 `decrypt_browser.js`） | 输出 `out.mp4` 158,619,533 字节，duration=2724.33s，约 65s |
| Java→Node 解密下载 `POST /api/download`（`cctv_360`） | HTTP 200，158,619,533 字节，耗时 45.6s |
| 解密日志 | `CCTV Node 批量解密` / Playwright 启动失败后 `回退系统 Chrome` / `seg 273/273` / `CCTV Node 解密下载完成` |
| CCTV 明文回归（建票） | `mode=hls`，`prepare=null`；`master.m3u8` HTTP 200，552 行 |
| `cctv-*` 目录清理 | 建票日志「清理过期解密目录: cctv-oldtest」，旧目录删除、新目录保留 |
| YouTube merge 回归 | `formatId=137+140` 建票 `mode=merge`，`prepare.status=PREPARING` |

### CCTV 1080p 明文低清误判修复（2026-11-24）

用户反馈 `https://tv.cctv.com/2018/08/30/VIDEYaRYJ9n6X9CLojtXnsBI180830.shtml` 选 1080p 在线播放未调用 sidecar。实测该视频 h5e 变体为 `[720p@2048000, 720p@1228800, 360p@870400, 270p@460800]`，明文 `hls_url` 仅有 `270p@460800`；修复前明文存在就无条件使用，1080p 档位精确匹配失败后回退到 270p 明文，导致画质降级且不触发解密。

修复：`resolvePlainStreamUrl` 在明文未精确匹配且明文最高变体低于 h5e 同档位实际分辨率时返回 `null`，转 h5e 解密。同时补充排障日志：`CCTV 解析完成`（h5e/明文是否存在、h5e 变体）、`CCTV 明文流变体`、`CCTV 明文取流`（目标高度、精确匹配、实际高度、地址）与 `PlaybackSourceService` 的明文/解密分支决策。

| 检查 | 实测结果 |
|---|---|
| 修复前 1080p 建票 | `mode=hls`，日志显示明文 270p 精确匹配=false 仍直接使用 |
| 修复后 1080p 建票 | `mode=merge`、`mime=video/mp4`，日志「明文最高 270p 低于 h5e 档位 720p，转 h5e 解密」 |
| `CctvNodeDecryptSidecar` 调用 | 解密该视频输出 636,896,927 字节 |
| 准备进度 | 0→100，约 160 秒 READY |
| READY 后本地 Range | `bytes=0-1023` → 206/1024；`bytes=-50` → 206/50；HEAD → 200 |
| `mvn -q clean compile -DskipTests` | Exit 0 |

未覆盖边界：浏览器中的实际画面播放未验证；本机 `HTTPS_PROXY` 指向 127.0.0.1:10808 且代理未启动时，Node 下载分片会因 `ECONNREFUSED` 失败（属本机代理环境，非代码缺陷），验证时需清除代理环境变量或启动代理；8082 IDEA 实例需重启后本轮改动才生效。

## 13. 风险与已知限制

1. 华视的 15~25 秒等待受站点广告时长控制，站点调整会导致失败。
2. CCTV 明文流覆盖率取决于上游是否下发 `hls_url`；仅 h5e 时需整片解密（45 分钟节目约数分钟），准备期间不可拖动。
3. YouTube 合并首次播放等待时间与文件大小成正比；`-c copy` 无转码但需完整下载。
4. 票据、活跃流计数均为进程内状态，重启后失效；多实例部署不共享。
5. 进度按 URL 记忆，不同短链形式不合并。
6. 浏览器被强制关闭时可能丢失最后 5 秒进度。
7. 小红书/微博 CDN 直链有效期短，过期需重新解析。
