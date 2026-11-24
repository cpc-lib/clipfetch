# HANDOFF

> 更新时间：2026-11-24（Asia/Shanghai）

## 本轮目标

1. 从 `main` 历史 `ba77a45^` 找回 `XvideosParser`、`PornhubParser`、`SpankBangParser`（含 sidecar）和 `MissavParser` 能力（并按要求移除 `AGENTS.md` 第 8 节第一条限制）。
2. 修复 XVideos 在线播放报错与清晰度误标 `audioOnly=true`，并将 XVideos / SpankBang / MissAV / Pornhub 接入在线播放。
3. 修复 Pornhub 在线播放命中 410 HLS 节点与缺 Referer 404 导致的播放失败。
4. 修复 SpankBang 点击「下载文件」时跳转新网页在线预览而非直接触发文件下载的问题。

## 根因定位（SpankBang 下载跳转网页）

- `SpankBangParser.parseViaSidecar()` 里的 `toFormatInfo()` 调用了 11 参数的 `FormatInfo` 构造器，默认 `serverOnly = false`（与 `YtDlpService.mapVideoInfo()` 给 `Platform.SPANKBANG` 设的 `serverOnly = true` 不一致）。
- 前端 `VideoResult.vue` 在 `!format.needsMerge && !format.serverOnly` 时会先调 `POST /api/direct-url` 并执行 `window.location.href = directUrl`；而 `ParseController.directUrl()` 未拦截 SpankBang，返回了无 `Content-Disposition: attachment` 的 CDN MP4 链接，导致浏览器直接导航打开视频页而非下载文件。

## 实际改动

- `backend/src/main/java/cc/ivera/parser/application/parser/video/SpankBangParser.java`
  - `toFormatInfo()` 改为调用 12 参数 `FormatInfo` 构造器，显式设置 `serverOnly = true`，与 `yt-dlp` 解析路径保持一致。
- `backend/src/main/java/cc/ivera/parser/interfaces/ParseController.java`
  - 注入 `SpankBangParser`，在 `POST /api/direct-url` 中明确拦截 SpankBang（抛「SpankBang 视频请使用服务端下载」），兜底避免旧缓存触发浏览器直链跳转。
- 其余本轮未提交改动：见 `git status`（4 个恢复的解析器 + sidecar、`XvideosParser` 修复 `audioOnly=false`、`PlaybackSourceService` 接入 XVideos/SpankBang/MissAV/Pornhub、`PlaybackProxyClient` Cookie 头换行过滤、`AGENTS.md`、配置与 `CookieModal.vue`）。

## 验证与真实结果

1. **UTF-8 无 BOM 校验**：`SpankBangParser.java`、`ParseController.java`、`HANDOFF.md` 等全部改动文件均无 BOM。
2. **后端编译**：`mvn -q clean compile -DskipTests` Exit Code 0。
3. **此前同轮已验证**：Pornhub `https://cn.pornhub.com/view_video.php?viewkey=6aeb6dd056547` 隔离 8099 实测建票 + `HEAD` 200 + `Range` 206（`ftypisom`）通过；前端 `npm.cmd run build` Exit Code 0。

## 未验证边界

- 未重启 `8082` 运行中后端服务；需重启 `8082` 后改动才会生效（若页面命中 5 分钟解析缓存，点击「清除解析缓存」后重新解析即可；即使命中旧缓存，`ParseController` 兜底拦截也会使前端自动回退服务端下载）。
- 未在浏览器端对 SpankBang 真实链接做完整服务端下载实测。

## 下一步

- 重启 `8082` 后端服务后，在页面复测 SpankBang 点击「下载文件」，确认走服务端代理直接触发浏览器保存文件（不再跳转网页）。
- 如需提交并推送本轮改动，待用户确认后再执行。
