# 04丨记账软件｜ChatGPT Project 文件更新清单 v0.1（建议）

- 日期：2026-11-16
- 目的：对照 Git 归档，提示 Project 共享区应保留 / 可撤下 / 需替换的文件。**不删除任何 Git 历史文档。**
- 来源：`Downloads/bookkeeping-project-files-triage-2026-11-03-v0.1.md`（A 表 10 份移出建议）+ 本轮 v1.2 现行入口。

## 1. Project 建议撤下且须在 Git 可核验（历史 10 份 + 过期状态类）

下列为 2026-11-03 triage **A 表 10 份**移出候选。核验时在仓库内检索到的对应路径（存在即「已安全归档」；缺则标 UNKNOWN，不得伪造）：

| # | Project 文件名（历史） | 仓库内对应 / 说明 |
|---:|---|---|
| 1 | governance v1.9 | 仓库保留多版 `docs/governance/` 历史；现行正式治理以仓库外/Project 现行版为准 |
| 2 | approval-index v0.6 | `docs/governance/bookkeeping-2026-10-25-approval-index-v0.6.md` |
| 3 | cross-chat lead handoff approved | 检索 `docs/governance/` / `docs/implementation/` 历史；以备份 ZIP 为准若未入库 |
| 4–5 | specialists unified delivery review/approval | 同上；批准事实不因移出 Project 而消失 |
| 6 | S5 plan review v0.1 | 历史 Review；Approved v0.2 在 `docs/implementation/` |
| 7 | LT-01 db permission review v0.2 | 历史 Pending；以现行治理为准 |
| 8 | S1 plan v0.5 approved | `docs/implementation/bookkeeping-v1.0-stage-s1-plan-v0.5-approved.md` |
| 9 | S1 stage closeout | `docs/implementation/bookkeeping-s1-stage-closeout-v0.1.md` |
| 10 | S2 two-milestones scope approved | `docs/implementation/bookkeeping-s2-two-milestones-scope-v0.1-approved.md` |

另建议撤下/替换的**过期现行状态**（不计入 A 表，但常与「13 份」口述一并处理）：过期 `current-state-handoff`、过期 `approval-index-current`、已合并 PR 后的 github runbook。合计口述「约 13 份」时，以 **A 表 10 + 过期状态/索引类 2～3** 核对，不物理删除 Git。

## 2. Project 应保留或更新为现行

- 现行治理（仓库外正式版）与**唯一**现行状态交接（替换过期 v3.x）
- v1.0 PRD / UI Freeze（已批准）
- v1.2 S1：批准记录、三项 Review v0.2、结项、现行 Backlog、CURRENT 入口引用
- 本轮：本地正式运行验收 v0.1；索引 v0.25；**明确**尚未授权 `release/v1.2` Push/PR

## 3. 禁止上传 Project

MoneyWiz CSV、个人 SQL/备份、私有 env、真实账本截图与明细、私有回填脚本。
