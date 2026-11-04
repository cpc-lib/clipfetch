# ClipFetch 项目交接（HANDOFF）

更新：2026-11-04；本轮目标：恢复被误删的 Native v2 全部工作成果并提交到 feature 分支。

> 事实优先级：当前工作树、实际命令/运行结果和当前后端状态 > 本文 > ISSUE.md 历史记录。

## 1. 事件经过（本次恢复的背景）

- v2 成果（`server/`、`native/`、`infra/native-v2/`、backend `magnet/v2`、前端 v2、V24 SQL、根 pom.xml 等）**从未提交过 git**，仅以未跟踪文件存在于工作区。
- 本轮开头应用户要求将 main 重置到 origin/main 并 `git clean -fd(x)`，v2 未跟踪文件被全部删除。
- 恢复来源：① git 仓库内 4 个未可达暂存树（最新为 `8dad8a1f`，11-02/03 状态）；② Trae 本地历史 `Trae CN\User\History`（temp/clipfetch 路径下 96 文件的最新编辑版）；③ `D:\release\magnet` 原平台仓库（native/ C++ Worker 源码）。

## 2. 恢复结果

- 已恢复 114 个未跟踪文件 + 9 个已跟踪文件的三方合并（base=origin/main，ours=feature，theirs=恢复版）。冲突取舍：v2 新增一律保留；`vite.config.js` 的 vitest `test` 块与 pom.xml 的 testcontainers/surefire 按 11-04 主动清理决定**未恢复**；所有测试源码遵循 11-04 决定未恢复。
- `application.yml` 采用 v2 版（`mapper-locations: classpath*:mapper/*.xml`、Redis autoconfigure 排除、`magnet.native-v2` 配置块）；`.env.example` 含全部 `MAGNET_V2_*` 参数。

## 3. 重建部分（丢失源码，非原版）

- `server/native-v2-engine-spi/src` 与 `server/native-v2-engine-libtorrent/src` 在任何来源均无留存，**为本轮根据设计文档（2026-11-02 spec/plan）、全部调用方签名与 `native/proto/download_engine.proto` 重建的新实现**（13 个文件）：
  - spi：`EngineEnvelope`/`EngineCommand`/`AddTorrentCommand`/`ResolveMetadataCommand`/`UpdatePrioritiesCommand`（record）、`NativeV2Snapshot`/`ResumeSnapshot`/`NativeV2Health`、`V2FailureKind` 枚举、`NativeV2EngineException`、`NativeV2Engine` 接口（9 方法 + close）。
  - libtorrent：`NativeV2GrpcProperties`（Builder + 校验，非回环强制 TLS）、`GrpcNativeV2Engine`。
  - 两模块 pom 无 protobuf-maven-plugin，gRPC 为**手写 proto3 线格式编解码 + `MethodDescriptor<byte[],byte[]>` + `blockingUnaryCall`**。
  - 已知偏差：现有 proto 无 `error_code` 字段，失败分类暂按错误文本关键词映射（代码已注释，待 proto 补字段后改纯码值映射）。

## 4. 未恢复项（确认丢失或主动放弃）

- `native/libtorrent-worker` 的 clipfetch 定制差异无法完全确认（当前=magnet 版 + 历史版 main.cpp）；`native/libtorrent-worker/tests/worker_policy_test.cpp` 丢失。
- 全部 v2 测试源码（11-04 主动删除，未恢复）。
- 11-03/04 对 server 模块的最后编辑以 Trae 历史最新版为准，可能与删除前最终态存在细微差异。

## 5. 验证

- `mvn clean compile -DskipTests`（根目录，8/8 模块）：**Exit 0**。
- `frontend npm install && npm run build`：**Exit 0**（v2 面板分包正常）。
- 运行时验证（8082 后端 + nv2-worker gRPC 联通）**未做**，引擎实际联通性待启动后确认。

## 6. 恢复中间产物

- `d:\release\temp-recover\`（snapshot/、history/、history-map*.json、restore.ps1、merge.ps1、resolve.ps1）保留备查，确认无问题后可删。
