# HANDOFF

> 更新时间：2026-11-25（Asia/Shanghai）

## 本轮目标

1. 移除测试文件与临时脚本，保证仓库整洁。
2. 全量核查前后端 API 接口匹配情况，清理过时或废弃死接口，确保不破坏现有任何业务功能。
3. 验证前端与后端构建，确认服务正常运行。

## 实际改动

- **测试文件与临时脚本清理**：
  - 确认仓库此前已按规则移除了单元测试源码（`backend/src/test` 不存在）；
  - 清理调试/分析时在 `scratch/` 目录下产生的临时探测脚本（`scan_apis.ps1`、`scan_backend_apis.ps1`、`scan_frontend_calls.ps1`），删除 `scratch/` 目录。
- **前后端 API 匹配与死接口清理**：
  - 全量扫描后端 19 个 Controller 的所有映射端点及前端 `frontend/src/api/*.js` 中定义的所有导出函数与实际组件调用；
  - 发现并清理 `frontend/src/api/temp.js` 中的过时遗留函数 `markDownloaded(url)`（其请求 `POST /temp/mark-downloaded`，后端 `TempController` 从未实现该接口，且前端任何 `.vue` 组件均未引用）；
  - 确认为前端内部消费的实用工具函数（`ftp.js` 中的 `fetchFtpFileBlob`，`video.js` 中的 `triggerSave` 与 `parseFilename`），以及前后端匹配的 RESTful 辅助接口（`video.js` 中的 `getPlayProgress` 对应 `PlaybackController` 的 `GET /api/play/progress`，`ed2k.js` 中的 `fetchEd2kTaskDetail` 对应 `Ed2kController` 的 `GET /api/ed2k/tasks/{taskUuid}`），均保持完备匹配与功能兼容。
- **代码优化保留**：
  - 保留此前已完成的 `NetMirrorParser.java` 并发多集解析优化、`compareFormats` 规则排序与冗余变量清理；
  - 保留 `VideoResult.vue` 默认选中首个可用清晰度的交互优化。

## 验证与真实结果

1. **前后端接口全覆盖核查**：
   - 提取后端所有 Controller 端点与前端 `frontend/src/api/*.js` 调用的 URL，确认除去已清理的 `markDownloaded` 外，其余所有接口路径、方法及鉴权规则 100% 对应一致。
2. **构建校验**：
   - 前端：`cd frontend; npm run build`（Exit Code 0，Vite 生产构建成功，分包正常）；
   - 后端：`mvn clean compile -DskipTests`（Exit Code 0，clipfetch-parent 及 7 个子模块全编译通过，BUILD SUCCESS）。
3. **服务运行与联调**：
   - 后端服务：运行于 8082 端口（`Tomcat started on port 8082 (http) with context path '/'`）；
   - 接口连通性验证：`curl.exe -s http://127.0.0.1:8082/api/magnet/engine/health` 正常响应鉴权拦截（`{"success":false,"data":null,"error":"请先登录"}`）；
   - 前端服务：Vite 运行于 5173 端口，`curl.exe -s -I http://localhost:5173/` 返回 `HTTP/1.1 200 OK`。
4. **编码规范校验**：
   - 所有变动文件（`NetMirrorParser.java`、`temp.js`、`VideoResult.vue`、`HANDOFF.md`）经首三字节核验，均为无 BOM 的 UTF-8。

## 遗留事项与下一步

1. 当前工作区处于 clean/ready 状态，所有修改文件已通过构建与本地验证。
2. 等待用户确认下一步工作或是否需要提交 git commit。