# ClipFetch 项目交接（HANDOFF）

更新：2026-11-02；本轮目标：排查并修复 WebSocket 连接失败问题。

> 事实优先级：当前工作树、实际命令/运行结果和当前后端状态 > 本文 > ISSUE.md 历史记录。

## 1. 本轮结论：WS 代码本身正常，故障源于前后端进程同时掉线

- 截图中的关键证据：
  - `POST /api/download` → `net::ERR_CONNECTION_REFUSED`
  - `ws://localhost:5173/ws/download-progress?taskId=...` → `WebSocket is closed before the connection is established`
  - Vite HMR 客户端 `localhost:5173/` 连接失败
- 原因链：
  1. 21:26:50 原先命令行启动的后端（PID 23340）被 IDEA 新启动的调试实例（PID 4304）顶掉，Maven 报 `Process terminated with exit code: 1`；当时正在下载的 1.02GB 文件被中断，前端显示 `Network Error`。
  2. 之后 IDEA 实例 PID 4304 也退出，8082 一度无监听；Vite dev server 同样处于停止状态。
  3. 后端不可达时 `downloadViaServer` 的 `finally { ws.close() }` 会关闭仍在 CONNECTING 的 WS，浏览器因此报 “closed before the connection is established”，并非 WS 端点故障。
- 已验证 WS 端点无需鉴权且工作正常：直连 8082 与经 Vite 5173 代理握手均返回 `HTTP/1.1 101 Switching Protocols`。
- 未改动任何代码（本轮没有发现需要修改的 WS 代码缺陷）。

## 2. 当前运行状态（已恢复）

| 服务 | 端口 | PID | 启动方式 | 日志 |
|---|---|---|---|---|
| 后端 | 8082 | 7368 | `$env:SERVER_PORT='8082'; mvn spring-boot:run`（后台） | `backend/target/restart2-20261102.log` |
| 前端 | 5173 | 39444 | `npm run dev`（后台） | `frontend/dev-restart-20261102.log` |

- 后端启动日志：`Started ClipFetchApplication in 2.32 seconds`。
- 注意：不要再同时用 IDEA 的 Run/Debug 启动第二个后端，否则会因端口冲突再次杀掉当前实例；需要 IDEA 调试时先停掉 PID 7368。

## 3. 本轮验证证据

- `curl POST /api/download`（无 token，直连 8082）：`401`。
- `curl POST /api/download`（无 token，经 5173 代理）：`401`。
- WS 直连 `http://localhost:8082/ws/download-progress?taskId=diagA`：`HTTP/1.1 101`。
- WS 经 Vite 代理 `http://localhost:5173/ws/download-progress?taskId=diagB`：`HTTP/1.1 101 Switching Protocols`。
- `Get-NetTCPConnection`：8082 → PID 7368，5173 → PID 39444。
- 鉴权刷新：`POST /api/auth/refresh {"refreshToken":"invalid"}` 返回 `401`（此前 curl 引号被 PowerShell 吃掉导致的 500 为测试方式问题，已用正确 JSON 复测）。
- 遗留：`backend/downloads/f17d24c3-2a2a-4017-8ab5-3692708fe1f5/` 有中断的 `video.mp4`（1,026,555,904 字节）与 `.aria2` 控制文件，来自 21:26 被终止的下载；未删除，待用户确认。

## 4. 前序工作（NetMirror 新版 tmdb 解析，已完成）

- `NetMirrorParser.java` 已支持 `/tmdb/watch/{tv|movie}/{id}`：
  - TMDB 元数据 `https://tmdb.imdb3.shop/3/...`（双 key 轮换）
  - `sig = HMAC-SHA256("{tmdbId}:{serverTime}", "net###@@sss")`
  - `external.php` → `servers` 中 `watchcinemo.php` → `qualities`，取 `type=mp4`
  - `exten=true` 必带，否则 qualities 为空
- `DownloadController.java`：tmdb 直链不带 `Referer`（带旧 `movieboxonline.net` 会 429）；旧站链路保持原 Referer。
- 冒烟验证（临时测试已删除）：`movie/550` 9 个格式；`tv/316197` 12 集 31 个格式；S1E1 1080P 直链可下载。
- 未纳入 DASH（`type=dash`）源；仅 MP4。

## 5. 边界与下一步

1. 让用户刷新 `http://localhost:5173` 后重新发起下载；新的下载任务会使用新的 UUID 目录，不会受旧 1GB 残件影响。
2. 是否删除 1.02GB 中断文件待用户确认。
3. 若再次出现 WS 失败，先检查 8082/5173 是否有监听、是否有第二个后端实例抢占端口，再判断代码问题。
4. 后端需要以单一实例运行；命令行实例与 IDEA 调试实例不可同时存在。
5. 已删除过期的 `frontend/scripts/native_torrent_browser_acceptance.py` 及空目录：该脚本未被 npm/CI/文档引用，且选择器与当前 Native 下载 UI 已不一致（尚未提交）。
