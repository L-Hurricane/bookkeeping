# 04丨记账软件｜唯一现行项目状态与跨 Chat 交接记录 v1.5 · Project 替换候选

- 日期：2026-10-26；状态：**Project 替换候选 / NOT YET SYNCED**。正式入 Project 后替代旧唯一状态 v1.3/v1.4 现行角色；不能同时保留两个“唯一现行”入口。
- 事实来源：负责人本 Chat 转达的 Cursor Gate、Git、数据库回执及人工批准，不代表本 Chat 已直连 Mac Cursor、Github 或实时数据库。后续以 Git 的 `docs/CURRENT.md`、实测回执和最新获批文件为准。
- 正式治理：v1.8；单 Coding Agent 串行、独立 Gate 不变、已有 `release/v1.0` 仅有界非强制 Push；禁止 Agent Merge main、新建 PR、生产部署、越权数据库或破坏性清理。

## 1. 最新 Stage / Gate

| 对象 | 当前状态 | 依据 |
|---|---|---|
| S1、S2、S3、S4 | **CLOSED** | 已批准各阶段 CLOSEOUT；S4 最终 HEAD `f6908c8e3c91f9a1ef8396f4416e77ed5555ec77`（据 Cursor 回执） |
| 统一数据库设计 V18 | Schema APPROVED、隔离演练 PASS | 设计 v0.3 SHA `7844121057e2d14504c9ca21baf82d8b83fff8a9638b2bb7cd56e8c5525b47ae`；V18 尚不准自动迁入日常/共享/生产库 |
| S5-T00～T05 | **Coding 完成** | 产品提交 `d3d785633bc5f6e06673692a2c4d64e17d748017`，单测 33、小程序 231 PASS、typecheck/lint PASS |
| S5-T06 | **独立 Testing PASS / Supervisor PASS** | 证据文件 `bookkeeping-s5-t06-gate-v0.1.md` SHA `7ebb394c4c7d3018a4f9b5855d8d418767be200e8a2a392bc665b90a7cc3269c`，Gate 提交 `a38a186af89d541ee9d79bb3fc4b4116795b78f8`；P0/P1/P2=0，P3=1 |
| S5-T07 | **组长 Review PASS + 负责人简化阶段放行 APPROVED** | 本轮批准 S5 验收修订 v0.2；S5 独立人工功能 UAT **未执行，不得写 PASS** |
| S5-T08 | **NEXT / 待结项执行** | 需整理 CLOSEOUT、Backlog、状态、索引和合法 Git 交付；不自动认定 S5 CLOSED |

## 2. 本轮正式批准与原件

- 初版 S5 `Approved v0.1` SHA `1958595f6ff480273fb460d3e70245167f8aff6adf7d19cd7332ef1708ecf964`，AI-01～AI-07、方案 B 继续有效。
- 人工 Gate 调整原 Review：`bookkeeping-v1.0-s5-human-gate-adjustment-review-v0.2.md`，**SHA `6b31c3cd371ecf66b9a13599aa98149806a3d275cfeba27353519aaa68b4c870`**，负责人明确确认。
- 新现行计划：`bookkeeping-v1.0-stage-s5-ai-plan-v0.2-approved.md`，**SHA `c7add0aefce153441ce4d22c0907ec549b362eaedafd713df37cfe33a816f081`**，保留旧版原字节。
- 新独立批准记录：`bookkeeping-s5-stage-plan-amendment-approval-record-v0.2.md`，**SHA `7f403b8906311e92aa0cdcac4a30bbfc36fba26605f41ac94bcb8273f6e22914`**。三者尚待 Cursor 核验并归档到 Git；Project 中 Approved v0.2 应替代旧执行版。
- 方案 B 不变：永久删除任一账户，旧 LEDGER 月报／年报不可读，已耗额度不返还；用户无被永久删除账户恢复功能。

## 3. T06 已验证、未测与 Backlog

- 已验证：后端 33 单测；`S5T06IsolationGateTest` 1 项真 MySQL（只在 `bookkeeping_migration_test`，V18/Flyway18，含回滚/恢复）；小程序 235、typecheck、lint PASS；Testing/Supervisor 双 PASS。
- 仍未测：真实模型效果及调用、微信真机、MockMvc、完整 `./mvnw test`。不得伪称 AI 外部调用、人工 UAT 或 CI PASS。
- `S5-UAT-DEFER-01`：S5 真实 AI 自然月/年及历史/额度/报告用户体验**延期至 v1.0 全功能完成后的最终统一 Human Gate**，最终 Stage/Task Plan 仍需独立获批。
- `S5T06-D1` P3：空期间 `EMPTY_PERIOD` 在前端显示通用失败文案；最迟最终 v1.0 UI / Human Gate 复核，无需即时额外 Coding。
- `UX-ST-01` P2：v1.0 业务完成后，统计易用性与 MoneyWiz 对比，随全局 UI 统一 Review；既有 `UI-NAV-01`、`S4-UI-COPY-01`、`S3-UI-ENTRY-01`、`UAT-03` 及 QA/工作区 Deferred 继续保留。

## 4. Git / PR 与下一责任

- 受审产品代码 `d3d785633bc5f6e06673692a2c4d64e17d748017`，本地 T06 Gate 记录提交 `a38a186af89d541ee9d79bb3fc4b4116795b78f8`；分支 `uat/v1.0-s2`，据上次回执 T06 后未 Push。
- 远端 `origin/release/v1.0` **仍为 S4 结项** `f6908c8e3c91f9a1ef8396f4416e77ed5555ec77`，已有 Draft PR #2 Open/Draft（据回执），无 CI PASS 证据；main 未 Merge，未部署。
- **下一执行方**：Cursor/Coding Agent 受已批 S5-T08 任务约束，先核验 Review v0.2、Approved v0.2、批准记录 SHA，保留原字节，补充阶段放行记录及 T06 原证据，整理 S5 CLOSEOUT、Backlog、`docs/CURRENT.md`、审批索引；符合 §4.8 条件才可对既有 release/v1.0 非强制 Push 并更新 Draft PR #2。完成后向组长回执；不得启动未经批准新 Stage Coding。
- **STOP 条件**：P0/P1 重新出现、代码与获批 Gate 不一致、Git 不可恢复冲突、目标数据库身份不明、冻结范围/Schema/人工 Gate 需新变更、越权远端或生产操作。

## Change Log

| 版本 | 日期 | 内容 |
|---|---|---|
| v1.3/v1.4 | 2026-10-26 | S4 CLOSED、S5 初版批准及 T00 待启动；历史快照，已过时 |
| **v1.5** | **2026-10-26** | 记录 S5 T00～T05 实现、T06 双 PASS、S5 验收修订 v0.2 获批、T07 简化阶段放行、T08 下一责任及延期人工 UAT | 
