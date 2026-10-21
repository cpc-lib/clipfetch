# ClipFetch 项目交接（HANDOFF）

更新：2026-10-21；本轮目标：按已批准计划实现「原生磁力下载 → 保存到本机文件夹」（浏览器目录授权 + 整文件流式写入）。

> 事实优先级：当前工作树、实际命令/运行结果和当前后端状态 > 本文 > ISSUE.md 历史记录。

## 1. 本轮状态

- 已读取 `AGENTS.md`、`ISSUE.md`、`docs/superpowers/plans/` 与 `docs/superpowers/specs/` 及本文后再动手。
- 已按批准计划 `.trae/documents/native-download-save-to-local-folder.md` 实现，未扩大范围。
- 后端新增只读导出能力：`GET /api/magnet/native/tasks/{taskUuid}/files`（JSON 清单）与 `GET /api/magnet/native/tasks/{taskUuid}/files/{fileIndex}/content`（octet-stream 流）。
- 前端在 `COMPLETED` 任务上新增「保存到本机」按钮：`window.showDirectoryPicker()` 选目录 → 二次确认文件清单 → 原生 `fetch` 流式写入用户所选文件夹。
- 两个新端点走普通 JWT 认证，**未**加入 `WebConfig` 免登录白名单，**未**使用下载 ticket。
- 保留原「下载保存目录」服务器目录卡片，未改动其逻辑。

## 2. 本轮文件

新增：

- `backend/src/main/java/cc/ivera/magnet/domain/TorrentNativeFile.java`
- `backend/src/main/java/cc/ivera/magnet/engine/piece/NativeFileExportService.java`

修改：

- `backend/src/main/java/cc/ivera/magnet/domain/TorrentNativeTaskMapper.java`（`listTaskFiles`）
- `backend/src/main/resources/mapper/TorrentNativeTaskMapper.xml`（`nativeFile` resultMap + `listTaskFiles` 查询）
- `backend/src/main/java/cc/ivera/magnet/interfaces/NativeTaskControlController.java`（两个端点）
- `frontend/src/api/nativeTorrent.js`（`listNativeTaskFiles`、`openNativeFileStream` 原生 fetch）
- `frontend/src/components/NativeTorrentManager.vue`（按钮、导出弹窗、目录选择、流式写入、进度、页脚文案）

## 3. 本轮验证证据

均在本机实际执行：

```powershell
cd backend; mvn -q clean compile -DskipTests
```

Exit Code `0`（`-q` 无输出）。

```powershell
cd frontend; npm run build
```

Exit Code `0`；输出 `✓ 108 modules transformed`、`✓ built in 1.73s`，`NativeTorrentManager-*.js` 正常产出。

BOM 检查：对上述 7 个新增/修改文件读取首三字节，全部非 `EF BB BF`（Java 为 `70 61 63`，XML 为 `3C 3F 78`，JS 为 `69 6D 70`，Vue 为 `3C 73 63`）。

数据库只读抽查（`D:/data/clipfetch/sqlite/fvd.db`，`mode=ro`）：

- `torrent_native_task` 存在 COMPLETED 样本 `c49c025c-4886-4e45-b65d-62e3e3b3783b`，`state_path=D:\release\clipfetch\downloads\torrent-native\1\c49c025c-...`。
- `torrent_native_file` 对应行：`file_index=0`、`relative_path=ubuntu-26.04-live-server-amd64.iso`、`total_bytes=2918598656`、`is_selected=1`，与新查询条件匹配。

未做到/无法在此环境验证：

- **未做端到端浏览器导出验证**：8082 上运行的是 IDEA 调试进程（PID `45732`，父进程 IDEA），加载的仍是旧字节码；需要用户在 IDEA 中重启后端后才能请求到新端点。本轮未杀进程、未重启。
- 未新增自动化测试（仓库当前无 `backend/src/test/java`，`mvn test` 无测试可跑）。
- 未验证生产反代下大文件长传输（Nginx 需足够大的 `proxy_read_timeout`/`proxy_send_timeout`）。

## 4. 实现要点（便于接手）

- `NativeFileExportService` 放在 `engine.piece` 包，是为了复用包私有的 `NativeTaskStoragePaths.assertPersisted(...)`；仅允许 `COMPLETED` 任务导出。
- 文件解析同时接受真实文件名与大小相等的 `<name>.clipfetch.part`（容忍最终改名尚未执行的窗口），大小不符或缺失时 `available=false`，列表仍返回。
- 路径校验：拒绝绝对路径、`:`、`\`、`\0`、`.`/`..`/空段；`normalize()` 后必须位于 task root 内；拒绝符号链接。
- 流式响应先完成全部校验与文件大小读取，再提交响应头；64 KiB 缓冲；客户端中断时若响应已提交则不再抛异常。
- 前端必须用原生 `fetch`（axios blob 会让 2.7 GiB 整体进内存）；401 时提示「登录已过期，请刷新页面后重试」。
- `showDirectoryPicker()` 是点击处理器中第一个 await 的调用；`FileSystemDirectoryHandle` 用 `shallowRef` 保存，避免被 Vue 代理导致 Illegal invocation。
- 写入前先不带 `create` 探测同名文件并二次确认；任一步失败 `writer.abort()`，避免留下截断文件。

## 5. 边界与下一步

1. 在 IDEA 中重启后端（必须），然后在浏览器打开「原生 BT 下载」页面。
2. 用 COMPLETED 任务 `c49c025c-4886-4e45-b65d-62e3e3b3783b` 点「保存到本机」→ 选空文件夹 → 核对文件大小为 `2918598656` 字节。
3. 对未完成/非 COMPLETED 任务调用导出应返回中文错误「任务尚未下载完成，请等待下载结束后再保存到本机」。
4. 明确不做（本轮计划已排除）：Range/断点续传、跨会话目录记忆、边下边写、移除服务器目录卡片、新增 Flyway、新增免登录白名单。
5. 不推送远程、不创建 PR 或合并。
