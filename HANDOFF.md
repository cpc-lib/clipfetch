# HANDOFF.md

> 最后更新：2026-11-16（第八轮）

## 1. 本轮目标

新增 `IfyTvParser`（酷播 kubb.tv），解析 `https://www.kubb.tv/play/F56a9HuHFiF?id=qhs1Jr5aaC9` 这类播放页，
列出 1080 / 720 / 576 三档清晰度并支持服务端下载。

实测确认 **1080p / 720p 为 VIP 专属**（`clarity[].isVIP=true`），游客即使请求 1080 档的 key，
接口返回的 `customData.v` 写着 1920:1080，但 CDN 分片实际仍是 1024x576（ffprobe 已验证）。
经用户确认采用「弹出 Chrome 手动登录一次 → sidecar 提取 sessionStorage token → 带 token 重新签名请求」方案。

## 2. 站点链路（实测确认）

```
GET https://www.kubb.tv/                                  ← 首页 SSR，注入 "pConfig":{"publicKey","privateKey":[...]}
GET https://m10.kubb.tv/v3/video/detail?key=...&vv=...&pub=...   ← 剧集元信息（title/imgPath/cid/serialCount）
GET https://m10.kubb.tv/v3/video/languagesplaylist?...&cid=0,1,4,151  ← 分集列表（cid 必须带，否则空）
GET https://m10.kubb.tv/v3/video/play?...&id={集数key}     ← clarity[] 档位 + flvPathList（isHls 的 result 即 m3u8）
```

- 签名：`vv = md5(publicKey + "&" + lowercase(原始query) + "&" + privateKey)`，同时带 `pub=publicKey`。
  publicKey/privateKey **会轮换**，每次解析必须实时抓首页 `P_CONFIG`，不能缓存。
- 签名覆盖的是**未 URL 编码的原始 query**（`cid=0,1,4,151` 的逗号原样参与签名，请求 URL 也不能编码）。
- 参数顺序固定：业务参数 → `v=1`（appendApiVersion 追加在末尾）→ token 字段（uid/expire/gid/sign/token）。
  Java 端按此顺序拼串再签名，含 token 时签名覆盖 token 参数。
- 播放页 SSR 对本机网络请求无响应（Cloudflare 拦截），只能走 JSON API。
- `video/play` 的 `id` 必须是**集数 key**（URL 里的 `id=`）或清晰度 key，传剧集 key 会返回「视频不存在」。
- 下载直链是签名 HLS（`chunklist.m3u8?vendtime=...&vhash=...`），需带 `Referer: https://www.kubb.tv/`。

## 3. 实际改动文件

- 新增 `backend/src/main/java/cc/ivera/parser/application/parser/video/IfyTvParser.java`
  - `supports`：`kubb.tv` / `*.kubb.tv` / `ify.tv` / `*.ify.tv`；`platform()` 返回 null，`cookiePolicy()` NONE
  - `parse`：抓 pConfig → detail（失败仅 warn）→ 无 `id` 时取 playlist 第一集 → play → 用 `clarity[]` 生成格式
    （formatId = clarity.key，按 height 倒序，`serverOnly=true`），标题 `detail.title + " " + playingMedia.title`，
    时长取 `customData.t`，`notice` 提示 1080/720 为 VIP 专属
  - `download`：先求目标档位高度；>576 时取 sidecar token（为空则抛「酷播 1080p 为 VIP 专属，请先在「我的 Cookies → 酷播」完成 VIP 登录」）
    → 带 token 重新请求 play → 校验 `playingMedia.title` 实际高度是否达标 → 取 `flvPathList` 中 `isHls` 的 `result`
    → `downloadService.downloadToResponse(..., List.of("-N","16","--add-headers","Referer:https://www.kubb.tv/"))`
- 新增 `backend/src/main/java/cc/ivera/parser/infrastructure/sidecar/KubbBrowserSidecar.java`
  - 按需拉起常驻 Python sidecar（参照 `TencentBrowserSidecar`），方法：`enabled/login/logout/token/ensureRunning`
  - `token()` 未登录返回 null（不抛），`/login` 超时 330s
  - 脚本定位：显式配置 → `src/main/resources/kubb/kubb_sidecar.py` → classpath 解压到 `java.io.tmpdir/clipfetch-kubb/`
- 新增 `backend/src/main/resources/kubb/kubb_sidecar.py`
  - Python + Playwright + 系统 Chrome（`channel="chrome"`，`headless=False`，`launch_persistent_context`）
  - `GET /health` → `{ok, logged_in}`；`POST /login`（等用户手动登录，最长 300s，可关窗口）；
    `GET /token` → `{ok, logged_in, token, user_agent}`；`POST /logout`
  - token 来源：`sessionStorage.__t`（JSON 中含 `token` 字段才算已登录）；为空时打开站点，
    由 `autologin` cookie 让 SPA 自动填充后再轮询
- 修改 `backend/src/main/java/cc/ivera/parser/interfaces/DownloadController.java`
  - 注入 `IfyTvParser`，在 YouTube 分支前新增酷播下载分支（按所选清晰度重新取 m3u8）
- 修改 `backend/src/main/java/cc/ivera/parser/interfaces/LoginController.java`
  - 新增 `POST /api/kubb/login`、`POST /api/kubb/logout`
- 修改 `backend/src/main/resources/application.yml`
  - 新增 `app.kubb-sidecar.{enabled,port,profile-dir,script}`（默认 true / 8099 / kubb-profile / 空）
- 修改 `backend/.env`、仓库根 `.env`、`.env.example`（三处同位置新增同名两键，键集合保持一致）
  - `KUBB_SIDECAR_PORT=8099`、`KUBB_PROFILE_DIR=D:/data/kubb-profile`
- 修改 `frontend/src/api/video.js`：新增 `kubbLogin()`（timeout 330s）、`kubbLogout()`
- 修改 `frontend/src/components/CookieModal.vue`：腾讯视频卡片后新增「酷播」卡片（标签「浏览器登录」，
  「登录 VIP」/「清除登录态」按钮，提示游客可下 576p、720p/1080p 为 VIP 专属）

## 4. 验证结果（真实执行）

- `mvn -q -pl backend compile -DskipTests` → Exit 0
- `cd frontend; npm run build` → Exit 0（✓ built in 1.80s）
- 8083 测试实例（`$env:SERVER_PORT='8083'`，测试账号 `kubb.test@local.dev`，id=4）：
  - `POST /api/parse` → success，耗时 **2.2s**，`title="午夜规则 01"`、`duration=2789`、
    3 个格式：1080(`txudVRUUSbE`) / 720(`jy6Fg0wAK0G`) / 576(`qhs1Jr5aaC9`)，均 `serverOnly=true`，
    notice 提示 VIP 专属
  - `POST /api/download {formatId:"qhs1Jr5aaC9"}` → HTTP 200，耗时 **30.4s**，**247,421,927 字节**；
    ffprobe：h264 **1024x576** + aac，时长 2789.387s，MP4 完整
  - 未登录请求 1080 档 → HTTP 400「酷播 1080p 为 VIP 专属，请先在「我的 Cookies → 酷播」完成 VIP 登录（会弹出 Chrome 窗口）」；
    同时 sidecar 自动拉起、Chrome 窗口打开、`GET /token` 返回 200（logged_in=false）
  - 单独启动 `kubb_sidecar.py` → `GET /health` 返回 `{"ok":true,"logged_in":false}`
- 测试残留已清理：`%LOCALAPPDATA%\Temp\kubb\`（含 236MB 测试 MP4）已删除；`downloads/parser` 为空；
  8083 后端、8099 sidecar 及其 Chrome 进程已停止；8082 空闲。

## 5. 未验证 / 遗留事项

- **1080p（VIP 链路）未完成端到端验证**：需要用户在本机 Chrome 弹窗里手动登录酷播 VIP 账号并通过
  Cloudflare Turnstile。已验证的只有「未登录 → 正确报错 + sidecar 正常拉起」。验证步骤：
  1. 重启后端（IDEA 里 8082 的实例是旧代码，必须重启才会加载 `IfyTvParser` 与新 Controller）
  2. 前端「我的 Cookies」→「酷播」→「登录 VIP」，在弹出的 Chrome 窗口里登录（最长 5 分钟）
  3. 重新解析该链接 → 选 1080 下载；后端日志会打出「酷播下载: episode=..., 清晰度=1080p, 实际=1080p」
- `KUBB_PROFILE_DIR` 目前是空 profile（未登录过），首次登录后该目录会存登录态，禁止提交/打印。
- 抓取 pConfig 依赖首页 SSR 结构，站点改版需同步调整 `P_CONFIG` 正则。
- 已知站点侧限制：游客请求 1080 档 key 时不报错，只是静默降级到 576p，所以下载前必须校验
  `playingMedia.title` 实际高度，否则会误以为下到了 1080。
- `IfyTvParser` 只支持 `/play/{剧集key}?id={集数key}` 播放页，不支持详情页/搜索页。

## 6. 现状要点（沿用，仍有效）

- 配置优先级：OS 环境变量 / `-D` > 根 `.env` > `backend/.env` > `application.yml`；三处 env 键集合必须一致。
- 目录：下载统一在 `downloads/{parser,ftp,ed2k,magnet-v0,magnet-v1,magnet-v2}`；日志统一在仓库根 `logs/`。
- 端口：后端 8082、前端 5173、nv2 worker 50051、aMule 4711、FTP fixture 20021/20022、qBt 文件网关 18766、
  媒体/登录 sidecar 8095（QQ音乐）/8096（酷狗）/8097（腾讯）/8098（SpankBang）/**8099（酷播）**。
- 容器：nv2-* healthy；`clipfetch-amule`、`clipfetch-dev-ftp`、`clipfetch-dev-ftps` Up。
- 仓库无单元测试源码（已按用户要求移除）；未创建 commit（用户未要求）。
- FTPS 覆盖上传已存在文件仍返回 422「FTPS 数据通道未使用 TLS」（未排查，历史遗留）。
