# ClipFetch 项目交接（HANDOFF）

更新：2026-10-19；最新操作：按用户指令完成代码提交（commit: `4dc708e` / amend 更新）；工作目录：`D:\release\clipfetch`。

> 事实优先级：当前工作树及此次测试输出 > 本文 > ISSUE.md 历史记录。

## 1. 当前状态与系统边界

ClipFetch 单体多媒体解析与下载平台。后端 Java 21 + Spring Boot 3.5.4；前端基于 Vue 3 + Vite 6 + Tailwind CSS 3 构建。前端全面支持 vue-router 驱动的路由架构、异步懒加载代码分割与移动端响应式导航；后端支持 qBittorrent 与自研 Native BitTorrent 双引擎。

## 2. 本次提交主要内容 (commit: `4dc708e`)

1. **前端工程现代化与体积优化**：
   - 接入官方 `vue-router`，建立以路由驱动的页面结构（`frontend/src/router/index.js`、`frontend/src/views/VideoView.vue`）。
   - 在 Vite 与组件层引入路由按需动态导入 `() => import(...)` 与重型组件 `defineAsyncComponent` 分包机制，大幅压缩首屏打包体积。
2. **移动端响应式导航适配**：
   - 在 `AppHeader.vue` 统一支持桌面端横向导航与移动端折叠式汉堡面板。
   - 移动端菜单具备路由跳转自闭合、触摸屏快速响应及窄屏手机（360px~375px）按钮无溢出适配。
3. **磁力下载与引擎健康检查容错**：
   - 修复 qBittorrent 未配置或未连接时 `/api/magnet/engine/health` 与 `/api/magnet/downloads` 报 400 Bad Request 导致前端高频轮询报错问题。
   - 后端改为始终返回 HTTP 200 结构化健康状态；下载列表在外部引擎不可用时安全降级回查数据库任务。
   - 前端增加轮询前置就绪检查与异常自动熔断，防止控制台刷屏。
4. **规范与文档修订**：
   - `README.md` 补充官方 Ubuntu 磁力测试链接。
   - `AGENTS.md` 深度修订，移除成人内容敏感词并确立红线禁令，同步前端路由架构与健康检查开发约定。

## 3. 本轮改动文件统计

- `AGENTS.md`
- `HANDOFF.md`
- `README.md`
- `backend/src/main/java/cc/ivera/magnet/client/QBittorrentClient.java`
- `backend/src/main/java/cc/ivera/magnet/interfaces/MagnetController.java`
- `frontend/package.json` / `frontend/package-lock.json`
- `frontend/vite.config.js`
- `frontend/src/main.js` / `frontend/src/App.vue`
- `frontend/src/stores/app.js`
- `frontend/src/router/index.js`
- `frontend/src/views/VideoView.vue`
- `frontend/src/components/AppHeader.vue`
- `frontend/src/components/MagnetDownloader.vue`
- `frontend/src/components/MagnetEngineWorkspace.vue`

## 4. 执行过的验证命令与真实结果

| 命令 | 真实 Exit Code | 结果 |
|---|---|---|
| `npm run build` (frontend) | **0** | 前端生产构建 1.47s 成功，分块与 gzip/br 产物完整 |
| `mvn -q clean compile -DskipTests` (backend) | **0** | 后端 Clean Compile 成功通过 |
| `python -c "check_bom"` | **0** | 全库所有修改/新建文件 100% 为 UTF-8 无 BOM |
| `git status` | **0** | 工作区整洁，代码已成功提交 |

## 5. 后续提示

- 后端若在 IntelliJ IDEA 中运行，请在 IDEA 控制台点击 **Rerun** 重新加载最新的 Controller 字节码。
