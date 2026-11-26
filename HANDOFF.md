# HANDOFF

> 更新时间：2026-11-26（Asia/Shanghai）

## 本轮目标

解决 huavod.net（华视影院）解析/播放/下载的连续故障，定位并修复真实根因。

## 已确认的三层根因（均有实测证据）

1. **系统环境变量污染**：用户级 `HTTP_PROXY`/`HTTPS_PROXY = http://127.0.0.1:10808` 被后端子进程（yt-dlp、ffmpeg）继承，导致可直连的站点被强制走未启动的代理（`WinError 10061`）。
2. **命令沙箱误判网络**：我此前用 `curl` 探测 huavod 得到 HTTP 000，是命令沙箱拦截所致；非沙箱实测 `HTTP 200 in 1.4s`，站点可直连。
3. **站点 WAF 拦截特定客户端指纹**：同一 m3u8 URL 实测——`curl`（Chrome UA + Referer）连续 3 次 200；`yt-dlp`（含 `--impersonate chrome`）403；`ffmpeg` 默认 UA 403；`ffmpeg + Chrome UA + Referer` 200 并成功落盘（8 秒 572KB）。

结论：华视的 `/link/*.m3u8` 需要 **Chrome UA + Referer**，且 yt-dlp 的客户端指纹被拦，必须改用 ffmpeg。

## 实际改动

- `backend/.../infrastructure/service/HlsClient.java`
  - `downloadToResponse` 新增 7 参重载 `(…, taskId, referer, httpProxy)`；5 参版本委托空值，既有平台行为不变
  - 传了 referer 时追加 `-user_agent <Chrome UA>` 与 `-headers "Referer: …"`；传了 httpProxy 时追加 `-http_proxy`
  - ffmpeg 子进程清除继承的 `HTTP_PROXY/HTTPS_PROXY/ALL_PROXY`（大小写），是否走代理只由 `-http_proxy` 决定
- `backend/.../infrastructure/service/YtDlpService.java`
  - `enhanceEnvironment` 同样清除继承的代理环境变量
- `backend/.../parser/video/HuaVodNetParser.java`
  - 依赖 `DownloadService` 改为 `HlsClient`；`download(...)` 改用 `hlsClient.downloadToResponse(url, name, null, response, taskId, SITE + "/", viaProxy ? proxy : null)`
  - **直连优先、代理兜底**：`transports()` = `[直连]` 或 `[直连, 代理]`，仅网络层失败（`IOException` → `TransportFailure`）才换出口
  - 新增 `record Stream(url, viaProxy)` / `resolveStreamInfo`；解析、取流、切片、下载统一复用同一出口
- `backend/.../application/PlaybackSourceService.java`
  - 华视分支用 `resolveStreamInfo`，切片代理开关取 `stream.viaProxy()`

## 本轮追加修复（长视频下载体验 / 并发自伤）

日志分析（`logs/clipfetch.log`）发现：05:18:59 / 05:22:46 / 05:23:16 / 05:23:47 连续 4 次下载同一集，且**没有任何「HLS 下载完成/失败」记录**——说明每次都在整集下载（实测 457MB / 247s）期间被用户重复点击重启，多个 ffmpeg 并发抢带宽，越拖越慢。

- `HlsClient` 新增 `downloadStreamToResponse(...)`：ffmpeg 输出**分片化 MP4 到 stdout**（`-movflags frag_keyframe+empty_moov+default_base_moof -f mp4 pipe:1`），边下边写响应，浏览器立即开始接收；失败时记录退出码
- `HlsClient.fetchText/parseMediaPlaylist` 增加 `referer` 参数，修掉 Java 侧拉 m3u8 的 403（仅影响进度时长）
- `HuaVodNetParser.download` 改用流式方法，并加 **同集并发锁**：重复点击返回「该集正在下载中，请等待当前任务完成」

## 本轮追加修复（0KB 根因：签名 m3u8 一次性）

实测：resolveUrl 拿到签名 m3u8 后，先用 Java/Any client 拉一次（HTTP 200，28968 字节），**紧接着 ffmpeg 拉同一 URL 会卡死**（>4.5 分钟无输出）。即 `huavod.net/link/penny-*.m3u8` 是**一次性 URL**。

`downloadStreamToResponse` 里原先会先调 `parseMediaPlaylist` 算时长，把 URL 消耗掉，导致 ffmpeg 0 字节、浏览器保存 0KB 文件。

- 修复：流式下载路径**不再预取 m3u8**（`durationSec = 0`，进度只展示已下载字节，不显示百分比）
- 保留：ffmpeg 是唯一请求方

## 本轮追加修复（雪花 / 无进度）

- **雪花**：放弃 `pipe:1` 分片化 MP4 直传（用户实测画面雪花、播放异常），改为 ffmpeg **先落盘 `+faststart` MP4 再回传**。落盘产物与已验证样例同参数：H.264 1080p + AAC，前 120 秒解码 stderr 行数 = 0
- **无进度**：ffmpeg 在 stderr 被重定向为管道时会自动关闭统计输出，命令补 `-stats`，前端才能收到 `size=/time=`
- 保留：不预取 m3u8（一次性签名 URL）、同集并发锁、Referer

### 百分比进度（本轮）

- `HlsClient.materializePlaylist`：只请求一次签名 m3u8，改写分片/`URI="..."` 为绝对地址落到本地清单，并用 `#EXTINF` 累加总时长（实测 206 分片 / 2427 秒，与整集 40:27 一致）
- ffmpeg 读本地清单时必须加 `-protocol_whitelist file,http,https,tcp,tls,crypto,data`，否则 `Protocol 'https' not on whitelist 'file,crypto,data'`
- 输入为本地清单时不能再传 `-user_agent/-headers`（HTTP 协议私有选项，会报 `Option not found`），分片由 CDN 直接放行
- 实测：`ffmpeg -stats -protocol_whitelist … -i playlist.m3u8 -t 20 -c copy -bsf:a aac_adtstoasc -movflags +faststart` → Exit 0、2,234,612 字节，stderr 出现 `size= … time=00:00:20.08`（进度可用）

## 验证

- `cd backend; mvn -q compile -DskipTests` → **ExitCode 0**
- 样例文件（457MB / 40:26 / faststart）ffprobe：h264 1920x1080 + aac；`ffmpeg -v error -t 120 -f null -` → **0 行错误**
- 一次性 URL 验证：先 HTTP 200 预取一次（28968 字节）→ ffmpeg 再拉同一 URL 卡死
- **未验证**：在线播放（HLS 票据代理）画质是否正常，需用户实测；若雪花出现在"在线播放"而非下载文件，问题在 `HlsPlaylistRewriter`/`PlaybackProxyClient` 链路，需另查

## 使用前提与边界

1. 必须**重启后端**（Bean 构造函数变更，IDEA 热替换不生效）
2. 直连可用时全程不走代理；仅直连网络失败且配置了 `PROXY_URL` 才回退，回退后 ffmpeg 显式带 `-http_proxy`
3. 仅支持 `/vodplay/{id}-{sid}-{nid}.html` 播放页；详情页仍提示「仅支持华视影院播放页链接」
4. 取流需等待站点广告时长（本次实测 22 秒），票据创建同步阻塞，属既有行为
5. 在线播放由 `PlaybackProxyClient`（Java HttpClient + Referer）代理切片，未改动；若播放仍 403 再补 UA

## 下一步

- 用户重启后端后实测：解析 → 在线播放 → 服务端下载
- 所有改动未提交；不主动创建 commit
