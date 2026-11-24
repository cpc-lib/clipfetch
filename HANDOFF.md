# HANDOFF

> 更新时间：2026-11-24（Asia/Shanghai）

## 本轮目标

将在线播放合并缓存目录从 `D:\release\clipfetch\data\play-cache` 迁移至 `D:\release\clipfetch\downloads\play-cache`，并删除 `D:\release\clipfetch\data\play-cache` 文件夹。

## 实际改动

- `.env`、`backend/.env`、`.env.example`
  - 同步将 `PLAY_MERGE_CACHE_DIR` 更新为 `d:/release/clipfetch/downloads/play-cache`（三处键集合保持完全一致，各 222 键）。
- `backend/src/main/resources/application.yml`
  - 将 `app.play.merge-cache-dir` 默认占位符更新为 `${PLAY_MERGE_CACHE_DIR:./downloads/play-cache}`。
- `backend/src/main/java/cc/ivera/parser/application/PlaybackMergeService.java`
  - 将构造器 `@Value` 默认路径更新为 `./downloads/play-cache`。
- 本地目录调整
  - 删除 `D:\release\clipfetch\data\play-cache`。
  - 创建 `D:\release\clipfetch\downloads\play-cache`。

## 验证与真实结果

1. **UTF-8 无 BOM 校验**：`.env`、`backend/.env`、`.env.example`、`application.yml`、`PlaybackMergeService.java`、`HANDOFF.md` 首三字节均无 BOM。
2. **三处 `.env` 键集合一致**：`root: 222, backend: 222, example: 222`，`rootDiff: 0, backendDiff: 0`。
3. **后端编译**：仓库根目录执行 `mvn -q clean compile -DskipTests`，Exit Code 0。
4. **目录状态**：`D:\release\clipfetch\data\play-cache` 已删除，`D:\release\clipfetch\downloads\play-cache` 已存在。

## 未验证边界

- 未重启正在运行于 `8082` 端口的后端服务；需重启后端以加载新的 `PLAY_MERGE_CACHE_DIR` 配置与编译产物。
- 本次未做合并下载写盘实测，仅验证配置同步、目录状态与编译通过。
- 历史设计文档（`docs/superpowers/{plans,specs}/2026-11-23-online-playback-phase2-*.md`）中对旧路径 `./data/play-cache` 的记录未改。

## 下一步

- 重启 `8082` 后端服务使新缓存目录生效。
- 如需推送本次改动，再按需提交并推送到远程分支。
