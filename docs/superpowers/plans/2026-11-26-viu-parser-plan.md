# ViuParser 解析下载与在线播放 实施计划

> 版本：v1 · 2026-11-26
> 关联规格：无独立 SPEC，本计划即完整设计。

## 1. 背景与目标

为 Viu（`www.viu.com`）OTT 平台的 `/ott/{country}/{lang}/vod/{product_id}/...` URL（如用户提供的 `https://www.viu.com/ott/sg/zh/vod/3491977/%E5%8D%88%E5%A4%9C%E8%A7%84%E5%88%99`）新增解析器，输出标题、封面、时长、多清晰度 MP4 直链（非 HLS）、字幕；支持服务端下载 + 在线播放（progressive 模式）。本 Parser 独立于 yt-dlp 提取器，不走通用 yt-dlp fallback。

## 2. 信息基础（已逆向）

**yt-dlp `ViuOTTIE` 提取器源码已完整读取**（仓库 `yt-dlp/yt_dlp/extractor/viu.py`，第 132–358 行），API 链如下：

```
1. GET  http://www.viu.com/ott/{country}/index.php
       ?r=vod/ajax-detail
       &platform_flag_label=web
       &product_id={video_id}
       [&area_id={AREA_ID[country].value}]
       → {data.current_product, data.series.product[]}
       current_product.ccs_product_id / cover_image_url / duration / number
       series[].name / product[]（剧集列表）

2. POST https://api-gateway-global.viu.com/api/auth/token?v={rand10}000
       Body(JSON): {"countryCode":"SG","platform":"browser","platformFlagLabel":"web",
                   "language":"en","uuid":"{uuid}","carrierId":"0"}
       Headers:   Content-Type: application/json
       → token（Bearer）

3. GET  https://api-gateway-global.viu.com/api/playback/distribute
       ?ccs_product_id={id}&language_flag_id={LANGUAGE_FLAG_ID[lang].value}
       Headers:   Authorization: Bearer {token}
                  Referer: {vod_url}
                  Origin:  {vod_url_host}
       → stream.url{s480p, s720p, s1080p...}（MP4 直链，URL 含 duration 参数）
         stream.duration / stream.size / subtitle[]
```

**映射表**：

| area_id | country_code | 语言_flag_id | lang_code |
|---|---|---|---|
| 1 | HK | 1 | zh-hk |
| 2 | SG | 2 | zh-cn / zh |
| 4 | TH | 3（或自定义） | en-us |
| 5 | PH | 3 | en-us |

**URL 识别**：正则 `^https?://(?:www\.)?viu\.com/ott/(?<cc>[a-z]{2})/(?<ll>[a-z]{2}-?[a-z]{0,2})/vod/(?<id>\d+)/`，例如 `/ott/sg/zh/vod/3491977/...`。

**代理需求**：中国大陆直连 Viu 首页返回地区受限页 `https://www.viu.com/ott/no-service/`，但 API `www.viu.com/ott/{cc}/index.php` 与 `api-gateway-global.viu.com` 的连通性需实测——项目 `.env` 已有 `PROXY_URL=http://127.0.0.1:10808`，且 `TubiParser` 已展示了代理注入模式（`@Value("${app.proxy:}")` → `HttpClient.Builder.proxy(...)`），ViuParser 直接复用。

## 3. 架构设计

**参考模板**：`TubiParser.java`（API 链 + HttpClient + 自实现认证 + 多清晰度 FormatInfo + 缓存 + resolveDownloadUrl/resolveStreamUrl + 代理支持）。

**类清单**：

```
cc.ivera.parser.domain.Platform          ← 枚举加 VIU
cc.ivera.parser.application.parser.video.ViuParser   ← 新增，extends AbstractVideoParser
cc.ivera.parser.interfaces.ParseController ← 注入 + supports 分支 + direct-url 拦截
cc.ivera.parser.application.ParserRegistry ← Spring 自动注册（按 VideoParser bean 自动注入，已存在无需改）
cc.ivera.backend/src/main/resources/application.yml ← 可选 viu 相关配置占位符
.env / backend/.env / .env.example       ← 如加新配置项需三处同步
```

**ViuParser 内部**：

- `@Service`、`@Slf4j`，构造注入 `@Value("${app.parse-timeout:60}")`、`@Value("${app.proxy:}")`（与 TubiParser 完全同构）。
- 内部 HttpClient 复用代理，UA 统一。
- 私有方法：
  - `extractGroups(url)` → (country_code, lang_code, video_id)
  - `mapAreaId(countryCode)` → int 或抛异常（不支持的地区）
  - `mapLanguageFlagId(langCode)` → int
  - `fetchProductData(vodUrl, productId, areaId)` → current_product + series 元数据
  - `fetchBearerToken(countryCode, videoId)` → token（uuid 随机，每次解析刷新）
  - `fetchPlaybackDistribute(ccsProductId, langFlagId, token, vodUrl)` → stream JSON
  - `buildFormats(stream, vodUrl, productId)` → List\<FormatInfo\>，解析 `s(\d+)p` 提取 height
- 公共 `parse(ParseContext ctx)`：串起以上链，构造 VideoInfo 返回。
- 公共 `resolveDownloadUrl(originalUrl, formatId)` / `resolveStreamUrl(...)`：同 TubiParser，缓存 formatId → streamUrl 映射。
- `CookiePolicy.NONE`（无需登录 cookies，匿名 Bearer token）。
- 缓存：`ConcurrentHashMap<String, Map<String, String>>`（pageUrl → formatId → streamUrl），避免解析 → 下载/播放时重复走 API 链。

**返回 VideoInfo 结构**（与 Tubi 一致）：

```
id         = product_id（vod 路径数字）
title      = current_product.synopsis（单集名）
thumbnail  = current_product.cover_image_url
duration   = stream.duration（秒）或 current_product.time_duration
durationString = YtDlpService.formatDuration(seconds)
platform   = Platform.VIU.display ("Viu")
formats    = buildFormats() 产物
media      = null（非内嵌在线源）
subtitles  = stream.subtitle[].url / second_subtitle_url
```

**FormatInfo serverOnly 决策**（关键）：
- Viu 返回的是**直接 MP4 下载 URL**（yt-dlp `formats.append({'url': stream_url, ..., 'ext': 'mp4'})`），非 HLS m3u8。
- 但需实测确认这些 URL 能否被浏览器直接拉取：若 URL 含签名参数、需要 Referer/Cookie/时效约束 → `serverOnly=true`（仅服务端下载），在线播放走 `progressive` 服务端代理流（由 PlaybackController 代理拉流）；若 URL 完全开放 → `serverOnly=false`（浏览器可直链下载），在线播放走 `progressive` 浏览器直链。
- 先保守 `serverOnly=true`，实测后再放开（ParseController 的 `direct-url` 拦截也对应修改）。

## 4. 下载链路

两条路径：

**A. 浏览器直链下载（若 serverOnly=false）**
- ParseController `POST /api/direct-url` 里加分支：`if (viuParser.supports(url)) { ... }`，直接返回 `resolveDownloadUrl(...)`。

**B. 服务端下载（默认）**
- ParseController `direct-url` 拦截：`if (viuParser.supports(url)) throw new BusinessException("Viu 视频请使用服务端下载")`。
- 实际下载：复用现有 `HlsClient` / 服务端下载流程？不——Viu 返回的是**MP4 直链**，应该直接复用项目里"MP4 单文件 progressive"链路（参考 TubiParser 的服务端下载：ffmpeg 自动拉流并落盘）。TubiParser 也是 HLS 但 Viu 是 MP4，差别只在输入，输出都是最终文件，ffmpeg/ytdlp 都能处理。
- 最简单方案：让 `ParseController.direct-url` 的 `viu` 分支调用 `viuParser.resolveDownloadUrl(url, formatId)` 返回直链字符串，然后服务端下载流程（YtDlpService 或 DownloadService）照常消费——MP4 URL 直接用 HTTP GET 流式落盘即可，无需 ffmpeg 参与。

## 5. 在线播放链路

Viu 返回 MP4 直链 → **progressive 模式**。PlaybackController 的 progressive 代理流（`/api/play/stream/{ticket}`）已支持 MP4 Range 请求，直接复用：

1. 前端 `POST /api/play/ticket` → PlaybackController 识别 VideoInfo 的 progressive 格式，签票据
2. PlaybackSourceService 拉取 `viuParser.resolveStreamUrl(originalUrl, formatId)` 返回的 MP4 URL 作为 upstream
3. PlaybackProxyClient 逐跳 SSRF 校验 + 透传 Range
4. 前端 `<video>` 直接挂载，支持拖动/续播

**Referer/Origin 头传递**：MP4 直链若带鉴权，可能需要 Referer 头。方案：`ViuParser.resolveStreamUrl` 生成的 URL 已完整（API 返回的 URL 自身已含签名参数），浏览器直拉时 CDN 可能校验 Referer。若校验失败，走 PlaybackProxyClient 代理流（服务端发请求，Referer 由后端设置），此时 PlaybackProxyClient 的 `UpstreamTargetPolicy` 允许 viu.com CDN 域名。

## 6. Platform 枚举

```java
// Platform.java
VIU("Viu"),  // 新增
// ... rest unchanged
// Platform.from(url)
if (host.contains("viu.com")) { return VIU; }
```

## 7. ParseController 注册

```java
// 构造注入
private final ViuParser viuParser;

// direct-url 拦截（服务端下载强制）
if (viuParser.supports(url)) {
    throw new BusinessException("Viu 视频请使用服务端下载");
}
```

**ParserRegistry 无需改动**：它是 Spring `@Autowired List<VideoParser>` 自动发现所有 VideoParser bean，ViuParser 加 `@Service` 就会被自动注入。

## 8. 配置（可选，初期不必加）

ViuParser 不需要新 `.env` 变量——复用现有的 `app.proxy`、`app.parse-timeout`。

如果后续需要定制：
- `VIU_REQUEST_TIMEOUT_SECONDS`（默认 60）
- 三处 `.env` + `.env.example` + `application.yml` 占位符，保持集合一致

## 9. 验收矩阵

| ID | 场景 | 预期 |
|---|---|---|
| T1 | 解析 URL `https://www.viu.com/ott/sg/zh/vod/3491977/...` | VideoInfo 标题/封面/时长/1080p/720p 格式列表/字幕，formats 非空 |
| T2 | 不支持的 country（如 `/ott/us/...`） | BusinessException "该 Viu 地区暂不支持解析" |
| T3 | 不支持的 lang（如 `/ott/sg/ko/...`） | BusinessException 或 fallback 到 en-us |
| T4 | 视频需要 VIP 登录 | BusinessException "该 Viu 内容需要登录观看"（yt-dlp 遇此情况抛 ExtractorError） |
| T5 | 解析缓存命中后重复 parse | 第二次 parse 命中 cache，不走 API 链 |
| T6 | 服务端下载 | 返回直链 MP4，ffmpeg/HTTP GET 落盘成功 |
| T7 | 在线播放 progressive | `/api/play/ticket` → `/api/play/stream/{ticket}` 返回 200，`<video>` 可播放、拖动、续播 |
| T8 | 代理不可用（10808 未监听） | 解析失败，错误信息清晰，不抛异常泄漏内部 |
| T9 | 404 / 已下架视频 | BusinessException 带中文提示，Markdown 错误不吞掉 |

## 10. 实施顺序

1. Platform 加 `VIU` + `from()` 匹配 → 编译校验
2. ViuParser 骨架：URL 正则 + area_id/language_flag_id 映射 + 空 `parse()`
3. 串起 API 链（index.php → auth/token → playback/distribute），硬编码目标 URL 跑通并打印响应
4. buildFormats / build VideoInfo / cache 逻辑
5. ParseController 注入 + direct-url 拦截
6. 配置代理后跑 T1 真 URL 解析
7. 跑 T6 服务端下载 + T7 在线播放 progressive
8. .env 若需加新键，三处同步 + application.yml 占位符

## 11. 已知约束与后续

- Viu CDN 签名 URL 时效 / Referer 校验策略 **需实测确认**，这是决定 serverOnly=true 还是 false 的关键。
- 系列多集解析（ViuOTTIE 里 `_yes_playlist` 分支支持）本期**不做**，按单 URL → 单集处理；后续可扩展 hasParseSideEffects + 批量入库。
- 字幕本期只读 API 返回的 `subtitle[]` 列表，不做 VTT/SRT 转换。
- 不接 cookies 白名单；Viu OTT 匿名 Bearer token 是公开 API，无需用户上传 cookies。
