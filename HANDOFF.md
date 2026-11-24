# HANDOFF

> 更新时间：2026-11-24（Asia/Shanghai）

## 本轮目标

1. 修复 `POST /api/play/progress` 在 CCTV h5e（`hls-decrypt`）播放时返回 400「播放模式仅支持 progressive、hls 或 merge」的故障。
2. 修复再次点击「在线播放」时无法从上次暂停点继续播放的故障（覆盖 progressive 与 HLS / CCTV `hls-decrypt`）。

## 根因定位

1. **后端进度模式校验遗漏 `hls-decrypt`**：
   - `PlaybackSourceService.java` 为 CCTV h5e 创建的票据 `mode` 为 `hls-decrypt`，但 `PlaybackProgressService.java` 的 `MODES` 白名单仍仅包含 `progressive / hls / merge`，导致进度上报全部 400 失败。
2. **前端 HLS 续播过早 seek 被覆盖 + 首帧前 `timeupdate` 将进度覆写为 0**：
   - `VideoPlayer.vue` 原先未向 `new Hls(...)` 传入 `startPosition`，而在 `Hls.Events.MANIFEST_PARSED` 阶段就调用 `applyStartAt()`（此时 MSE 尚未建缓冲、`readyState=0`），设置 `el.currentTime` 无效或被 `hls.js` 从分片 0 起播覆盖，却已将 `startApplied` 置为 `true` 阻断了后续跳转。
   - `setup()` 将 `lastReportAt` 重置为 `0`，首帧/初始 seek 完成前触发的早期 `timeupdate`（`currentTime=0`）或加载中卸载会立刻上报 `positionSeconds: 0`，把数据库中保存的暂停点覆写归零。
3. **再次点击「在线播放」时未等待进度落库且丢弃实时进度**：
   - `VideoResult.vue` 的 `play()` 调用 `closePlayer()` 未等待 `savePlayProgress` 完成便并发发起 `createPlayTicket`，导致建票读到旧进度；且 `closePlayer()` 先置 `playing.value = null`，使子组件 `onBeforeUnmount` 上报的实时位置被 `if (!ticket) return` 丢弃。

## 实际改动

- `backend/src/main/java/cc/ivera/parser/application/PlaybackProgressService.java`
  - 将 `MODES` 扩展为 `Set.of("progressive", "hls", "hls-decrypt", "merge")`，并同步更新非法模式错误文案。
- `frontend/src/components/VideoPlayer.vue`
  - `hls.js` 初始化直接传入 `startPosition: startAt > 0 ? startAt : -1`，移除过早的 `MANIFEST_PARSED` seek，新增 `LEVEL_LOADED` 结尾保护与 `loadeddata` / `seeked` 兜底确认；非 `hls.js` 保持 `loadedmetadata` 跳转并跟踪 `pendingStartSeek`。
  - 增加 `hasLoaded` 与 `pendingStartSeek` 保护：首帧就绪与初始续播跳转完成前禁止上报进度，避免将 `0` 写回覆盖数据库；补充 `onseeked` 监听，暂停状态下拖动进度条也会立即上报。
  - 通过 `defineExpose({ getCurrentProgress })` 向父组件暴露实时播放进度快照。
- `frontend/src/components/VideoResult.vue`
  - 维护 `pendingSave` 串行队列并绑定 `playerRef`，`closePlayer()` 在清空 `playing` 前读取播放器实时进度并持久化，返回落库 Promise。
  - `play()` 中 `await closePlayer()` 后再调用 `createPlayTicket`，并在 `playing` 中保存当前票据对应的 `url / formatId / title`，避免换清晰度或换解析结果时错写进度。

## 验证与真实结果

### 1. 编码与构建检查

- `PlaybackProgressService.java`、`VideoPlayer.vue`、`VideoResult.vue`、`HANDOFF.md` 均为 UTF-8 无 BOM。
- 仓库根目录 `mvn -q clean compile -DskipTests`：Exit Code 0。
- `frontend/` 下 `npm.cmd run build`：Exit Code 0（Vite 6.4.3，118 modules，耗时 3.30s）。

### 2. 隔离后端 + 真实 Chrome Vue UI 端到端验证

在隔离端口 `8099` + 临时 SQLite（`%TEMP%\clipfetch-verify-resume-e2e.db`，`MAGNET_V2_ENABLED=false`，`PLAY_BLOCK_PRIVATE_HOSTS=false`）启动后端，并用本地静态/代理服务（`127.0.0.1:5199` 托管 `frontend/dist` 并代理 `/api` 到 `8099`），通过 Playwright + 系统 Chrome 执行 `verify-resume-e2e.cjs`（Exit Code 0，未触碰 8082 实例）：

- **Progressive MP4**：解析并播放本地 40s 测试 MP4 → 拖动到 `18s` 并暂停 → 直接再次点击「在线播放」→ `POST /api/play/progress` 返回 200（`positionSeconds=18`），新票据 `resumeSeconds=18`，播放器直接从 `18s` 起播并推进到 `19.48s`，显示「已从 0:18 继续播放」，DB 保持 `18` 未被归零；点击「从头播放」后回到 `0.44s`。
- **CCTV h5e（`hls-decrypt`）**：解析真实样本 `https://tv.cctv.com/2026/11/23/VIDERt14K2MWKI2GBhLyNPmZ261123.shtml` 选 `cctv_1080` → 拖动到 `65s` 并暂停 → 直接再次点击「在线播放」→ `POST /api/play/progress` 返回 200（`mode="hls-decrypt", positionSeconds=65`），新票据 `mode="hls-decrypt", resumeSeconds=65`，播放器直接从 `65s` 起播并推进到 `67.56s`（`1280x720`，`mediaError=null`，显示续播提示），DB 保持 `65` 未被归零；随后暂停在 `67s` → 点击「关闭」→ 再点「在线播放」→ 续播位置 `67s`。
- 全程浏览器 `CONSOLE_ERRORS: []`。
- 验证结束后已停止 8099 隔离后端并清理临时测试 MP4 与临时 SQLite 文件。

## 未验证边界

- 本机 `8082` 端口仍有既有后端进程（PID 25116）在运行，未直接重启；前端改动经 Vite dev（`5173`）热更新可生效，但 `PlaybackProgressService` 后端改动需**重启 `8082` 后端**后才会在 `http://localhost:5173` 生效。
- 本次测试使用 SQLite，未在真实 MySQL 实例上重复运行；截图在清理阶段被同步删除，未做人工看图复核（本次通过 `videoWidth=1280, videoHeight=720, mediaError=null` 与连续播放时间推进验证）。
- 未实测 `merge` 模式与原生 Safari HLS 的续播路径（逻辑已按同等 `loadedmetadata` / `pendingStartSeek` 保护处理）。

## 下一步

- 重启 `8082` 后端服务后，在 `http://localhost:5173` 实测播放暂停并再次点击「在线播放」，确认续播与「从头播放」符合预期。
- 不主动创建 commit，不修改无关模块。
