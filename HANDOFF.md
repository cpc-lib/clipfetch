# ClipFetch 项目交接（HANDOFF）

更新：2026-10-19；本轮目标：梳理 Native BitTorrent 下载实现，把当前处理流程集中写入根 `README.md`，并按用户确认的保守范围移除纯设计规格。工作目录：`D:\release\clipfetch`。

> 事实优先级：当前工作树及本轮检查输出 > 本文 > `ISSUE.md` 与历史实施记录。

## 1. 开工基线与范围

- 开工前已阅读 `AGENTS.md`、`HANDOFF.md` 与 `ISSUE.md`，并以当前代码核对旧设计内容。
- 当前 HEAD：`ef0376d 页面调整`。
- 用户确认只删除纯设计规格，保留 `torrent/` 下实施、故障、性能和验收证据；不删除 `ISSUE.md`、`docs/superpowers/plans`、qBittorrent 规格或基础设施 README。
- 本轮只改 Markdown 文档；未修改 Java、Vue、配置、数据库、下载目录或用户任务，也未创建 commit。

## 2. README 梳理结果

根 `README.md` 已新增并统一说明以下 Native BitTorrent 内容：

1. 功能定位：纯 Java BitTorrent v1 实验链路，与 qBittorrent 完全隔离，健康接口仍为 `productionReady=false`。
2. 两种元数据入口：上传 `.torrent` 离线验证，或经用户明确同意后使用 Tracker/DHT + BEP10/BEP9 解析 v1 Magnet。
3. 任务流程：文件选择与受控保存根 → `CREATED` → Revision CAS `READY` → Worker Fence `RUNNING` → `PAUSED / COMPLETED / FAILED`。
4. 网络流程：Tracker、可选 DHT fallback、PeX、健康度与稀缺优先；Mailbox Actor、16 KiB Block、自适应流水线、TCP 复用、超时接管及 End-game CANCEL。
5. 可信进度：完整 Piece SHA-1 → `.part` 持久化与 fsync → Checkpoint bitmap → 数据库 Fence CAS；网络 `wireBytes` 不等于 `verifiedBytes`。
6. 恢复与完成：重启后重读磁盘逐 Piece 校验；全部完成后 `finalizeVerifiedFiles()` 逐文件原子改名。
7. 安全边界：JWT/用户隔离、task UUID、revision/epoch/instance fence、目录白名单、逻辑删除、公开发现 InfoHash 披露提示。
8. 运行入口：Native API 分组、核心 `.env` 开关和启用步骤。

代码核对发现旧设计中 LSD 容易被误解为当前 Worker 自动启用：README 已明确当前 Worker 自动发现为 Tracker、可选 DHT 与 PeX；BEP14 LSD 组件存在，但当前公网 Worker 未自动启用，健康接口也标记 discovery acceleration 非自动开启。

## 3. 删除的纯设计规格

以下 4 个已跟踪文件已删除，仍可从 Git 历史恢复：

- `torrent/01-自研BitTorrent引擎研发规划书.md`
- `torrent/02-自研BitTorrent协议技术实现方案.md`
- `torrent/README.md`
- `docs/superpowers/specs/2026-10-18-native-mailbox-actor-throughput-design.md`

保留 `torrent/` 下 40 份实施/验收记录；`specs/magnet-downloader-spec.md` 属于 qBittorrent 链路，不在本轮删除范围。

## 4. 实际修改文件

- 修改：`README.md`
- 删除：上述 4 份纯设计规格
- 整体替换：`HANDOFF.md`

## 5. 验证命令与真实结果

| 验证 | Exit Code | 结果 |
|---|---:|---|
| README 范围/内容 Python 检查 | **0** | 6 个必需流程标记齐全；4 个目标文件均不存在；`ISSUE.md` 与 `.env.example` 新链接存在；Markdown fence 成对；保留 40 份 `torrent/*.md` 历史记录。 |
| `rg` 检查 3 个已删除设计文件名的仓库残留引用 | **0** | `STALE_REFERENCES=0`。 |
| `git diff --check` | **0** | 无空白错误。 |
| README UTF-8 BOM 检查 | **0** | 无 UTF-8/UTF-16 BOM。 |

未运行 Maven 或前端构建：本轮只有文档内容与文档删除，`AGENTS.md` 明确要求不要为文档改动执行构建；因此没有声称代码测试在本轮重新通过。

README 全量本地链接检查另发现一个本轮之前已存在的失效链接 `infra/qbt-file-gateway/linux/`。该链接属于 qBittorrent 基础设施且不在已批准的 Native 范围内，本轮未擅自修改。

## 6. 当前边界与下一步

- `ISSUE.md` 的 ISSUE-001 至 ISSUE-008 状态未改变；README 流程梳理不代表公网 Worker、吞吐或生产就绪验收完成。
- 当前工作树应只包含 `README.md`、本 `HANDOFF.md` 和 4 个目标删除文件。
- 后续接手先运行 `git status --short`、`git diff --check` 和 BOM 检查；若继续精简历史文档，必须重新向用户确认范围，不得默认删除保留的实施/验收证据。
