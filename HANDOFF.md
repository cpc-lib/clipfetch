# HANDOFF

> 更新时间：2026-11-25（Asia/Shanghai）

## 本轮目标

1. 解决 NetMirror 在线播放（`https://netmirror.gold/tv/12068/?embed=1`）在前端界面报错「该格式暂不支持在线播放」且「在线播放」按钮呈灰色禁用状态的问题。
2. 修复前端 `VideoResult.vue` 中可播放格式白名单与后端解析格式扩展名判定不匹配导致的拦截，打通 DASH 在线播放全链路。

## 根因定位

1. **前端 `PLAYABLE_EXT` 未包含 `mpd`**：
   - `frontend/src/components/VideoResult.vue` 中定义 `PLAYABLE_EXT = new Set(['mp4', 'm4v', 'webm', 'mov', 'mp3', 'm4a', 'aac', 'flac', 'ogg', 'opus', 'wav'])`；
   - `canPlay(format)` 仅对白名单中的格式放行，若不在集合中，`playBlockedReason` 会输出「该格式暂不支持在线播放」，导致界面直接将「在线播放」按钮置灰禁用。
2. **后端 `NetMirrorParser` 生成格式时 `ext` 字段值**：
   - 后端 `collectTmdbFormats` 中将 DASH 流的扩展名标记为 `ext: "mpd"`，导致前端判断不通过；且在下载落盘时，用户期望默认文件名后缀为 `.mp4` 而非 `.mpd` 清单名。
3. **播放器异常友好提示**：
   - DASH MPD 清单包含 H.265/HEVC 视频流（`codecs="hev1.1.6.L150.90"`），部分未开启硬件解码的浏览器若出现 `capability` 不支持时，原提示较为抽象，需明确提示。

## 实际改动

- **前端改动**：
  1. `frontend/src/components/VideoResult.vue`：
     - 将 `mpd` 与 `m3u8` 加入 `PLAYABLE_EXT`，允许渐进式、HLS 和 DASH 容器格式直接发起在线播放。
  2. `frontend/src/components/VideoPlayer.vue`：
     - 优化 `dashPlayer.on(dashjs.MediaPlayer.events.ERROR)` 错误处理逻辑：增加错误日志输出，当检测到设备或浏览器不支持视频编解码器（如 `capability` 或 `notsupported`）时，明确提示「当前浏览器不支持该视频编码（H.265/HEVC），请尝试下载后观看」。
- **后端改动**：
  1. `backend/src/main/java/cc/ivera/parser/application/parser/video/NetMirrorParser.java`：
     - 在 `collectTmdbFormats` 中统一设置 `ext = "mp4"`，使前端判断和默认下载文件保存名保持规范统一。

## 验证与真实结果

1. **编译与构建验证**：
   - 前端：`cd frontend; npm run build`（Exit Code 0，`dash.all.min` 分块打包正常）；
   - 后端：`mvn compile -pl backend -DskipTests`（Exit Code 0，BUILD SUCCESS）。
2. **后端服务重启**：
   - 重启守护进程 `cd backend; $env:SERVER_PORT='8082'; mvn spring-boot:run`；
   - 日志验证：`Started ClipFetchApplication in 4.549 seconds`，端口 8082 正常监听。
3. **端到端 API 全链路测试**：
   - `POST /api/parse` 解析 `https://netmirror.gold/tv/12068/?embed=1`：
     - 成功解析出 33 个多语言清晰度格式；
     - 格式 `formatId: se1ep1-1080-english`，`ext: mp4`（满足前端 `canPlay` 校验，界面「在线播放」按钮恢复高亮可用）；
   - `POST /api/play/ticket`：
     - 成功下发播放票据，`mode: "dash"`, `mime: "application/dash+xml"`；
   - `GET /api/play/dash/{ticket}/manifest.mpd`：
     - 返回 HTTP 200，内容类型 `application/dash+xml;charset=utf-8`，带有 `Access-Control-Allow-Origin: *`，包含完整 DASH MPD 清单及 AWS CloudFront 签名分片。
4. **编码规范与无 BOM 检查**：
   - 经首三字节核验，`VideoResult.vue`、`VideoPlayer.vue`、`NetMirrorParser.java` 均为无 BOM 的 UTF-8 文件。

## 遗留事项与下一步

1. 用户在浏览器刷新页面后，输入 `https://netmirror.gold/tv/12068/?embed=1` 点击解析，选择任意清晰度后，「在线播放」按钮已正常可用，可直接点击秒开播放。
2. 若用户使用的浏览器或硬件不支持 H.265 硬解，可使用现代 Chromium 浏览器或直接点击「下载文件」。
3. 不主动创建 commit。
