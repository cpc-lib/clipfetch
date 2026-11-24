# ClipFetch 在线播放二期 实施计划

> 日期：2026-11-23　范围：在首期（票据 + 同源代理 + HLS + VideoResult 内嵌播放）之上补齐平台、YouTube 高清合并、票据滑动续期/并发放流上限，并新增播放进度记忆与继续播放。
> 设计规格见：`.trae/documents/spec/2026-11-23-online-playback-phase2-spec.md`

## 1. 目标

1. 补齐首期未支持的平台在线播放：CCTV（明文流）、VIP、酷播、华视影院、小红书、微博。
2. 支持 YouTube 高清合并格式（`formatId` 形如 `137+140`）。
3. 票据从「固定 TTL」升级为「空闲滑动续期 + 最大寿命硬上限」，并限制单票据并发放流数量。
4. 新增播放进度记忆：按用户 + 视频 URL 保存进度，再次播放自动从上次位置继续。

## 2. 已核对的代码事实

| 项 | 事实 |
|---|---|
| 首期路由 | `PlaybackSourceService` 目前把 VIP/华视/酷播/小红书/微博/CCTV 统一拒绝（约 L146-L159） |
| VIP | `VipParser.parse()` 只产出 `vip_default`；`resolveDirect()` 返回 `DownloadTarget(streamUrl, headers, ...)`，可能带 Referer/UA |
| 酷播 | `IfyTvParser.download()` 已实现「URL → episodeKey → findClarity(formatId) → play(episodeKey, formatId, key, token)」；720p/1080p 需 sidecar VIP token |
| 华视 | `HuaVodNetParser.resolveStream()`：取新令牌 → `sleepAd(adDurationMs)`（站点要求 15~25 秒）→ `postResolve` → 返回 m3u8；令牌过早使用会作废，已内置重试一次 |
| CCTV | `CctvParser.parse()` 优先使用 `hls_h5e_url`，缺失时回退 `hls_url`；h5e 分片为 WASM 加密 |
| 小红书/微博 | 解析结果已有约 10 分钟内存缓存，缓存中包含视频直链；图文/多文件帖与单视频帖需区分 |
| YouTube | 合并格式为 `videoId+audioId`；`IV*` 走 `YouTubeMirrorService` 单文件镜像 |
| 票据 | 首期 `Caffeine.expireAfterWrite(PLAY_TICKET_TTL_SECONDS)`，无滑动续期、无并发放流计数 |
| 数据库 | Flyway 当前最大版本 `V26`，`playback_progress` 表不存在 |
| 迁移风格 | SQLite：`INTEGER PK AUTOINCREMENT` + `deleted INTEGER` + 时间 `TEXT`；MySQL：`BIGINT AUTO_INCREMENT` + `DATETIME(6)` + `deleted BIGINT` |

## 3. 关键决策与取舍

### D1 YouTube 高清合并：服务端按需合并落盘

- 方案：票据创建时提交后台任务，用 `yt-dlp -f "<videoId>+<audioId>" --merge-output-format mp4` 合并到本地缓存文件；前端轮询准备进度，就绪后通过带 Range 的本地流端点播放。
- 理由：浏览器原生控件、完整拖动、进度记忆全部可用；实现集中在后端，前端只增加准备态。
- 否决「浏览器双 `<video>/<audio>` 同步」：需要自绘控制条，易出现音画漂移，Safari/PiP 兼容差。
- 否决「ffmpeg 实时转封装管道」：输出不可拖动，无法支持继续播放。
- 代价：首次播放需等待合并完成，占用磁盘与带宽；用 TTL + 启动/惰性清理 + 单用户并发 1 控制。

### D2 华视影院：允许票据创建等待 15~25 秒

- 复用现有 `resolveStream()`（含广告等待与重试）。
- 前端该次请求单独设置 60 秒超时，并显示「正在获取播放地址（约 20 秒）」。
- 失败按上游原因返回中文提示，不缓存一次性令牌。

### D3 CCTV：只支持明文 `hls_url`

- 解析时同时保留 `hls_url` 与 `hls_h5e_url`，为明文流单独缓存变体表。
- 仅有 h5e 加密流时返回明确提示「请下载观看」，不做流式解密（首期已判定不可行）。

### D4 VIP / 酷播 / 小红书 / 微博：取流即时解析，不做额外缓存

- VIP：新增 `resolveStreamUrl(url, formatId)`，复用 `resolveDirect()`，把 `DownloadTarget.headers` 写入票据。
- 酷播：新增 `resolveStreamUrl(url, formatId)`，抽出 `download()` 的取流段复用；VIP 清晰度仍依赖 sidecar token。
- 小红书/微博：新增 `resolveVideoUrl(url)`，缓存未命中时重新解析一次；仅支持单视频帖。
- 这四家不新增持久化，票据本身即短时缓存。

### D5 进度记忆放服务端数据库

- 理由：按用户隔离、跨浏览器/设备一致，符合项目“用户数据必须带 user_id”的硬约定。
- 否决 localStorage：换设备/清缓存即丢失，且无法与账号绑定。
- 表 `playback_progress`，唯一键 `(user_id, source_key, deleted)`，`source_key = sha256(归一化 URL)`。

### D6 票据滑动续期

- `Caffeine.expireAfterAccess(PLAY_TICKET_IDLE_SECONDS)` + 票据 `hardExpiresAt`（`PLAY_TICKET_MAX_LIFETIME_SECONDS`）。
- `find()` 命中后检查硬上限；超限立即失效。
- 不新增 `@Scheduled`：惰性清理 + Caffeine 自身淘汰即可，符合项目定时任务限制。

### D7 单票据并发放流上限

- `PLAY_MAX_STREAMS_PER_TICKET` 默认 3；超过时主动关闭该票据最早建立的流（新请求优先）。
- 作用于 `/api/play/stream/*` 与 HLS 子资源请求；实现为票据内活跃流句柄队列。

## 4. 任务分解

### Task 1 票据滑动续期与并发放流

改动：

- `PlaybackTicket`：新增 `lastAccessAt`、`hardExpiresAt`、活跃流队列。
- `PlaybackTicketStore`：Caffeine 改 `expireAfterAccess`；`find()` 硬上限检查；新增 `registerStream/unregisterStream`，超限关闭最早流。
- `PlaybackController`、`PlaybackProxyClient`：开流时登记、关闭时注销。
- `application.yml` + 三处 `.env`：`PLAY_TICKET_IDLE_SECONDS`（替换 `PLAY_TICKET_TTL_SECONDS`）、`PLAY_TICKET_MAX_LIFETIME_SECONDS`、`PLAY_MAX_STREAMS_PER_TICKET`。

验证：

- 30 分钟无请求后 `/stream` 返回 404；持续请求可续到硬上限；第 4 个并发流时第 1 个被中断。
- `mvn -q clean compile -DskipTests` Exit 0。

### Task 2 播放进度记忆数据层

改动：

- 新增 `db/migration/{sqlite,mysql}/V27__playback_progress.sql`（同版本号）。
- `cc.ivera.parser.domain.PlaybackProgress` 实体 + `PlaybackProgressMapper`（`@MapperScan` 自动扫描）。
- `PlaybackProgressService`：URL 归一化 + SHA-256、查询、upsert、完成判定。
- `PlaybackController` 新增 `GET/POST /api/play/progress`（需登录）。
- 票据创建响应增加 `resumeSeconds`、`resumeCompleted`。

验证：

- 迁移在 SQLite/MySQL 均可执行（启动日志 schema version 27）。
- 同一 URL 重复保存只有一行；跨用户查询互不可见；`position` 越界返回 400 中文错误。

### Task 3 平台扩展（除华视、YouTube）

改动：

- `VipParser`：新增 `resolveStreamUrl`，返回流地址与请求头。
- `IfyTvParser`：新增 `resolveStreamUrl`，`download()` 改为复用取流段。
- `CctvParser`：保留明文 `hls_url` 变体表，新增 `resolveStreamUrl`（按清晰度高度匹配，缺失时取最高）。
- `RednoteParser` / `WeiboParser`：新增 `resolveVideoUrl`（仅单视频）。
- `PlaybackSourceService`：按新顺序接入，移除这些平台的一刀切拒绝。

验证：

- 各平台真实链接手动播放；无 cookies/token 时返回对应中文提示；纯图文帖返回「暂不支持在线播放」。
- 后端编译 Exit 0。

### Task 4 华视影院

改动：

- `HuaVodNetParser`：把 `resolveStream` 暴露为 `resolveStreamUrl`，返回 m3u8 与 `Referer: https://huavod.net/`。
- `PlaybackSourceService`：华视分支允许 15~25 秒等待。
- 前端播放请求超时 60 秒，loading 文案单独提示。

验证：

- 真实剧集点击播放，等待后能出流；超时/失败给出上游原因。
- 不重复使用已作废令牌（每次创建票据重新取令牌）。

### Task 5 YouTube 高清合并

改动：

- `YtDlpService`：新增 `mergeToFile(url, formatId, cookies, outputFile, progressConsumer)`，复用现有 cookies/代理/命令行拼装方式，打印完整命令。
- 新增 `PlaybackMergeService`：有界线程池（`PLAY_MERGE_CONCURRENCY`）、单用户 1 个任务、落盘 `PLAY_MERGE_CACHE_DIR`、大小上限 `PLAY_MERGE_MAX_BYTES`、TTL `PLAY_MERGE_TTL_SECONDS`、失败清理。
- `PlaybackTicket`：新增 `mergeStatus / mergeProgress / localFile / mergeError`。
- `PlaybackSourceService`：`formatId` 含 `+` 且平台为 YouTube 时创建 merge 票据。
- `PlaybackController`：新增 `GET /api/play/prepare/{ticket}`（需登录）；`/stream/{ticket}` 对 merge 票据支持本地文件 Range（206/200/416）。
- 清理：`@PostConstruct` 启动清扫 + 创建票据时惰性清理过期文件（不引入 `@Scheduled`）。

验证：

- 选 1080p 合并格式：返回 preparing，进度递增，ready 后播放/拖动/续播正常。
- 缓存文件在票据过期后删除；`PLAY_MERGE_MAX_BYTES` 超限返回中文错误。
- 同用户并发第二个 merge 请求被拒绝并提示等待。

### Task 6 前端

改动：

- `api/video.js`：新增 `getPlayProgress`、`savePlayProgress`、`getPrepareStatus`。
- `VideoResult.vue`：播放按钮逻辑区分 `needsMerge`（YouTube 可播，其他仍提示）；展示「上次播放到 mm:ss」；保存进度。
- `VideoPlayer.vue`：
  - 支持 `startAt`，在 `loadedmetadata` / hls `manifestParsed` 后跳转。
  - `timeupdate` 节流发 `progress` 事件；暂停、结束、卸载时立即上报。
  - merge 模式：轮询准备进度，ready 后挂载播放器。
  - 提供「从头播放」入口，重置进度。
- 配置：无新增前端环境变量。

验证：

- `npm run build` Exit 0；播放→关闭→重开能续播（误差 ≤5 秒）；播完再开从头开始。
- 组件卸载后轮询/定时器全部清理。

### Task 7 验证与收尾

- `mvn -q clean compile -DskipTests`、`npm run build` 均 Exit 0。
- 备用端口启动后端，验证 `/api/play/progress` 需登录、票据 404/416、merge 准备态。
- 三处 `.env` 键集合一致；所有文本文件无 BOM。
- 整体替换更新 `HANDOFF.md`；不创建 commit（除非用户明确要求）。

## 5. 配置变更清单

新增/替换（`application.yml` 占位符 + `backend/.env` + 根 `.env` + `.env.example` 四处同步）：

```text
PLAY_TICKET_IDLE_SECONDS=1800              # 替换 PLAY_TICKET_TTL_SECONDS（空闲续期）
PLAY_TICKET_MAX_LIFETIME_SECONDS=21600     # 票据硬上限 6 小时
PLAY_MAX_STREAMS_PER_TICKET=3
PLAY_RESUME_MIN_SECONDS=5                  # 低于该秒数不提示续播
PLAY_RESUME_COMPLETE_PERCENT=95            # 达到该比例视为看完，下次从头
PLAY_PROGRESS_SAVE_INTERVAL_SECONDS=5
PLAY_MERGE_CACHE_DIR=./data/play-cache
PLAY_MERGE_MAX_BYTES=4294967296
PLAY_MERGE_TTL_SECONDS=86400
PLAY_MERGE_CONCURRENCY=1
```

## 6. 风险

1. 华视等待 15~25 秒且令牌一次性，站点改动会导致失败；已按上游错误原文提示。
2. CCTV 明文 `hls_url` 并非所有节目都提供，覆盖率为尽力而为。
3. YouTube 合并需完整下载，磁盘/带宽成本与等待时间随清晰度上升。
4. 票据仍是进程内内存，多实例部署不共享（与首期一致）。
5. 进度按 URL 记忆，同一视频不同链接形式（youtu.be / youtube.com/watch）不会合并。
6. 页面被强杀时可能丢失最后 5 秒进度。

## 7. 不做的事

- 不做小红书/微博图文轮播、微博多视频聚合播放。
- 不做 CCTV h5e 流式解密。
- 不做播放历史列表页/推荐，仅保存与续播单条记录。
- 不做跨实例共享票据或分布式流控。
