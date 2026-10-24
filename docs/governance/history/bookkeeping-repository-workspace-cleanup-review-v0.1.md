# 04丨记账软件｜本地工程目录与 Git Worktree 收敛方案 · 调查审阅稿 v0.1

- **日期**：2026-10-23；**状态：Review / PENDING APPROVAL**（只读调查可提案，但本稿**不授权**移动、删除、重建、改变权限或修改任何 Git 分支）
- **输入事实**：负责人 Finder 截图列出了约 20 个 `bookkeeping*` 工作目录/ZIP；本 Chat 已知负责人主工程 `/Users/limingyuan/Documents/code/personal/bookkeeping`，S1 验收 worktree `bookkeeping-uat-v1.0-s1`；其他各目录用途尚未实地检查。
- **目标**：以后让负责人始终使用一个默认工程目录验收，由隔离 Agent 承担开发和审核；历史快照可追踪但不无限堆积。以既有 Git、branch、worktree、GitHub 受保护 main 实现，不建新的 Agent 平台。

## 1. 现状与核心问题

截图中可见如下名称（仅截图确认“目录存在”，并非已确认它们是独立 Git 仓库）：

| 类型候选（待核验） | 目录名 | 不允许推断的事实 |
|---|---|---|
| 负责人主工程 | `bookkeeping` | 需确认 HEAD、工作区及是否有本地未提交资产 |
| 当前 UAT | `bookkeeping-uat-v1.0-s1` | 已知 `uat/v1.0-s1` worktree；需实际验证状态与引用关系 |
| POC、布局与测试辅助候选 | `bookkeeping-ai-poc`、`bookkeeping-fixtures-review`、`bookkeeping-layout-work`、`bookkeeping-miniprogram`、`bookkeeping-p1b-isolated`、`bookkeeping-p2-runner`、`bookkeeping-p2-search`、`bookkeeping-qa-poc2-datetime` | 可能为实验副本、独立 repo、审核快照或含独有数据的临时工程 |
| 历史任务审核候选 | `bookkeeping-t2-review`、`bookkeeping-t3-review`、`bookkeeping-t4a-review`、`bookkeeping-t4b-review`、`bookkeeping-t5a-review`、`bookkeeping-t6b1`、`bookkeeping-t6b2`、`bookkeeping-t6c1-review`、`bookkeeping-t7a`、`bookkeeping-t7b` | 名称不表示任务代码已合并、未提交改动可丢弃或可以删除 |
| 同列归档 | `bookkeeping-t2-review.zip` | ZIP 不能视作已可验证的唯一恢复证据，须检查内容、哈希与版本 |

## 2. 先只读盘点，再决策

由 Cursor 在负责人实际授权可读的父目录下，**只读**逐个产出清单；不得跨越 macOS 用户或读取敏感文件：

1. `realpath`、是否 symlink、`.git` 是文件/目录/缺失、仓库 `rev-parse --show-toplevel`、`--git-common-dir`（判断是否同一仓库 worktree）、`git worktree list --porcelain`；普通非 Git 目录标为普通文件副本。
2. 当前分支、完整 HEAD、远端名/URL（若含隐私 token 则在报告中脱敏）、`git status --porcelain --untracked-files=all`（**仅路径/状态，不扫描文件正文和凭据**）、最近提交与独有提交（若可可靠比较）。
3. 独有未提交文件、未推送提交、tag/reflog 所指重要恢复点、与正式 `main` 和验收分支差异；清查结果中明确 **未知**，不靠 `git status clean` 就断定可以删除（Git 忽略文件/未跟踪证据要另行核实目录占用）。
4. 体积、目录最后修改时间、可复用正式审批/测试证据路径、是否还被 Cursor/微信开发者工具/本地后端依赖；记录这些事实，不打开敏感 `.env`、密钥、个人数据。
5. 将每个目录初分 `保留活跃` / `可归档候选` / `可清理候选` / `无法判定`，列出要删除前的核验要求、恢复点及是否需负责人明确授权；**本阶段不移动或删除任何目录，也不运行 `git worktree remove/prune`、`git branch -D`、`rm -rf`**。

## 3. 长期目标与路径

- **负责人默认工程唯一入口**：`/Users/limingyuan/Documents/code/personal/bookkeeping`。完成安全校验后，在该目录检出相应 UAT 分支做小程序人工验收；`main` 历史仍被 GitHub 保护。若工作区脏或分支被其他 worktree 占用，由 Agent 按获批规则安全处理或报阻断。
- **AI 工程隔离**：延续 `ai-runner` 用户 `700` 私有目录、按获批 Task / 工作领域复用功能会话、单 Coding Agent 串行；不要求把每次独立审核留作负责人项目目录下永久副本。
- **UAT 临时目录**：当前 S1 验收 worktree 先保留，待 PR/合并与证据归档完成后，再考虑从负责人日常目录接管下一次 UAT 的默认方式；**不要求清理历史路径后才能继续新 Chat**。
- **历史 Review / POC 目录**：正式批准文件和必要证据按 v1.3 的 Git `docs/`＋Project 可访问索引存放；其他副本通过只读盘点决定是否归档/清理，不能以所有文件都曾存在 ZIP 为由跳过校验。
- **不允许**：将几十个历史任务目录全部自动移到主仓库、开放 `ai-runner` 目录权限、替换 `main` HEAD、修改敏感文件或为这个问题另建同步/调度平台。

## 4. 三步最小执行计划（均需按相应批准边界行事）

| 阶段 | 行动 | 授权/验收 |
|---|---|---|
| A 现在 | 只读盘点、报告“目录→仓库身份→分支/HEAD/脏文件→用途/保留理由”；不改任何目录 | 负责人可单独确认只读调查；遵守目录访问边界 |
| B Stage 收口后 | 负责人 Review 逐项归档/清理候选；优先只做非破坏的归档与明确恢复点，必要时取得高风险清理授权 | 不能依据本稿直接执行删除、移动、prune |
| C 以后每个功能 Stage 结束 | 在结项记录中补一行“临时工作区是否可回收”；有独立批准的清理任务才执行，日常复用而不是新增永久副本 | 与待审治理 v1.4-r3 的结项契约一致，不为每个 Task 强制清理 |

## 5. 负责人 Review 决策

- D-W1：是否接受“**一个负责人固定目录 + Agent 隔离 worktree/工作区 + 少量必须保留的历史归档**”目标模式？
- D-W2：是否先让 Cursor 开展一次**只读清查**，提交目录分类/唯一数据风险报告，而不是现在批量删除？
- D-W3：是否同意“每个功能/Stage 结束做一次工作区可回收性检查”，仅产生候选清单，不自动删除？

## Change Log

| 版本 | 日期 | 变化 | 状态 |
|---|---|---|---|
| Review v0.1 | 2026-10-23 | 首次根据 Finder 截图整理仓库杂乱问题、只读证据清查、未来固定负责人目录/AI 隔离目标与清理授权边界 | Pending Approval |
