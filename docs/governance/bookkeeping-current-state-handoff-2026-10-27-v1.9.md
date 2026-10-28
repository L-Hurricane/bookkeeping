# 04丨记账软件｜唯一现行项目状态与跨 Chat 交接记录 v1.9 · Project 替换候选

- **日期**：2026-10-27；**状态**：`PROJECT REPLACEMENT CANDIDATE — NOT YET SYNCED`。本文件以 v1.8 为依据新增 S6 初版完整计划获批的事实，应作为 Project 中唯一现行状态入口替换旧版；**本 Chat 未自动同步 Project 或业务 Git**。业务 Git `docs/CURRENT.md` 以 Cursor 后续实际 T00 归档和实时执行结果为准；状态不能代替源码/Git 的现场核验。
- **治理**：`bookkeeping-ai-collaboration-governance-v1.8.md` Approved；未修改。Stage 初版完整 Plan、人为里程碑、UI 冻结变化、正式 Schema/高风险数据库/真实模型及外部生产行为仍须负责人批准。一个 Coding Agent 串行，独立 Testing/Supervisor Gate。

## 1. 最新 Stage / Task / Gate

| 对象 | 当前状态与事实 |
|---|---|
| S1～S4 | **CLOSED**（历史已批准的结项与验收证据） |
| S5 | **CLOSED — HUMAN UAT DEFERRED TO v1.0 FINAL GATE**（Cursor 2026-10-26 T08 回执；**非** UAT PASS，**非** v1.0 发布） |
| S5 质量 | T06 Testing PASS、Supervisor PASS，P0/P1/P2=0、P3=1；T07 负责人依据 Approved v0.2 完成**简化阶段放行** |
| V18 | 统一 Schema v0.3 Approved、隔离库 `bookkeeping_migration_test` Flyway V18 演练/安全集成 PASS；**未获授权**自动迁入 `bookkeeping`、`bookkeeping_test` 或生产库 |
| S6 | **初版完整 Stage/Task Plan Approved v0.3**，Review SHA `2ca8c1a2c44a55b85f9e4d09900e2bc39bb85a5cde99973a339b7bd47adb116a`；Approved SHA `8619081f735ed6a73ab2552d5eca6cac8e0299ad6488f83a0a41ddafa68b20d7`；独立批准记录 SHA `172cbc2d5548db848e70029eb819f2c4794275ff99c5ce7cb81e496d14d9c98f`。允许 S6-T00～T05 在获批范围启动，**当前仅授权，尚未收到 Agent 编码回执** |

## 2. 最新本地 Git / 远端交付

- Cursor 报告：本地分支 `uat/v1.0-s2`，S5 结项 HEAD **`676fca14ed834837a306df3f58f8938485d94abf`**，工作树 clean；S5 受审产品源码提交 **`d3d785633bc5f6e06673692a2c4d64e17d748017`**，之后只有原 T06 证据测试及文档，无新的产品功能更改。
- S5 结项文档 SHA：`503ac13f2fea7300f5b811c49a04ef0851c245364c6d5e97526f2927f5c2e7c7`；Backlog SHA `12f4f722daae40e18c8e900b750199ea4f9869b2641cd8ecaba5f2ecfe111c1e`；审批索引 v0.10 SHA `bac9348794d5a690454e1f3acfe286c423cf80f1e4341e930e2f9ae5d0320840`。
- **远端已成功同步 S5**：本地 Mac 代理 `127.0.0.1:7897` 网络预检 HTTP 200，经有界 `fetch` + 祖先确认后非强制快进 `f6908c8` → `676fca1`；最终 `ls-remote` 核实 `origin/release/v1.0` = **`676fca14ed834837a306df3f58f8938485d94abf`**；`origin/main` 仍为 `a393734ad147a5dbe11a83cebfd3381f408db7b2`。Draft PR #2 仍 Open / Draft、head 已包含 S5，检查项 0（非 CI PASS）。没有强推、开新 PR、合并 main 或部署。此前 `REMOTE_SYNC_BLOCKED` **解除**，保留历史故障记录。
- 核实来源为负责人转达的 Cursor 成功回执；ChatGPT **未直接操作** 本机或实时 GitHub。今后 Git 继续遵循 `bookkeeping-github-local-proxy-git-runbook-v0.1.md` 和治理 §4.8，不能把代理可用理解为扩大 Push/合并权限。

## 3. 遗留与到期节点

- **`S5-UAT-DEFER-01`**：真实外部模型、AI 月/年结果与历史/额度/方案 B 的人工体验；**到期：v1.0 最终统一人工 Gate**，禁止冒写已 PASS。
- **`UX-ST-01` P2**：统计使用逻辑/易用性不够好，v1.0 业务功能完成后参考 MoneyWiz，对比当前统计层级、账户/时间筛选、下钻路径，与整体 UI 统一评审；**到期：S6 UI 差异 Review / 最终 UAT 前**。
- **`UI-NAV-01 / S1-T08-OBS-01`**：小程序 `账本／统计／我的` 主导航缺失；**到期：v1.0 最终 UI Gate 前**。
- **`S3-UI-ENTRY-01`**：分类/标签入口遗留；具体判定以 Git 结项 Backlog 原文为准；到期：最终 UI Gate 前。
- **`S4-UI-COPY-01` P3**：统计「净资产」须符合「账本净值」语义；到期：最终 UI Gate 前。
- **`S5T06-D1` P3**：空期间 `EMPTY_PERIOD` 显示通用失败提示；到期：最终 UI Gate 前。
- **`UAT-03`**：流水逐笔后余额增强；原为已延期候选，是否必须进入 S6 实施依已批准原始边界，不凭本状态自动立项。
- **质量/环境**：HTTP MockMvc、完整 `./mvnw test`、真机、真实模型、GitHub CI 均不得伪称已通过；依据 S6 计划选择对最终 Gate 真实阻断的最少必要验证，其他按具体 Gate 延期或保留。
- **工作区 WS-01～WS-07**：整理/清理未授权，继续 Deferred；禁止越权删除、强制覆盖或清理临时工作区。

## 3.1 S6 审批前调查结论（历史调查，现已获 S6 v0.3 编码授权）

- **A，冻结 UI 应有的实现**：主导航「账本／统计／我的」；用户级分类/标签管理可达；统计的「账本净值／支出／收入」业务分区，支出/收入中分类与标签独立等权入口；错误「净资产」改为「账本净值」。对齐 UI Freeze §2.1、§3，不另增新报表产品。
- **C，组长可在获批范围内自主做的细节**：`EMPTY_PERIOD` 明确提示，优化标签明细/多选的点击可达性，但不可删除 ST-09 标签去重与多选功能。
- **B，尚未批准的冻结变更**：MoneyWiz 式新报表页面体系、取消单账户旧 AI 可见入口（不改变老 ACCOUNT 数据兼容）、`UAT-03` 每笔交易后余额。需完整具体 UI 差异时再由负责人决定，不把 B 默认纳入本轮编码。
- **T00/T01 已有前置只读调查**：现有 `app.json` 无底栏、无「我的」页、统计堆叠筛选与业务模块、分类/标签入口在单账户账户设置；具体以 Cursor 实核为依据，后续复用而不重复全面调查。MoneyWiz 观察为 Cursor 提供，需在后续设计阶段标明来源和未核实项。

## 4. 批准链与下一步

- S5 现行 Approved Plan v0.2 SHA `c7add0aefce153441ce4d22c0907ec549b362eaedafd713df37cfe33a816f081`；S5 Gate 修订 Review v0.2 SHA `6b31c3cd371ecf66b9a13599aa98149806a3d275cfeba27353519aaa68b4c870`；独立批准记录 v0.2 SHA `7f403b8906311e92aa0cdcac4a30bbfc36fba26605f41ac94bcb8273f6e22914`。
- **最新交付**：S5 T08 本地 CLOSED、远端既有分支已快进包含 S5、Draft PR #2 head 已更新（无 CI PASS）。负责人于 2026-10-27 明确批准 S6 初版完整 Review v0.3（SHA `2ca8c1a2c44a55b85f9e4d09900e2bc39bb85a5cde99973a339b7bd47adb116a`），正式 Approved v0.3 SHA `8619081f735ed6a73ab2552d5eca6cac8e0299ad6488f83a0a41ddafa68b20d7`；v0.1/v0.2 仅为未批准历史 Review。
- **执行暂停已解除**：负责人于 2026-10-27 指示按原计划推进；S5 Git 已合法同步。Cursor 对 S6 完成了只读调查，尚未运行 S6 Coding；目前 S6 完整初版计划已经获批，下一步为 Cursor 获取原件并核验后实施。
- **下一负责人决定**：S6 初版 Stage/Task Plan **已经批准，无需再次审批**。后续仅在 T01/T06 确有具体冻结 UI/产品变更时，或真实付费模型、未获准库操作等保留事项出现时才请求负责人独立决定；T05 体验为非正式试用，最终人工 UAT 仍在 T08。
- **下一执行方**：Cursor（Mac 上的单 Coding Agent），待负责人一次性交接原 Review v0.3、Approved v0.3、独立批准记录后先核验 SHA，在获批范围连续执行 T00～T05，复用既有只读调查；交付冻结 UI 可运行基础产品、Coding 自测与简短负责人体验清单。**当前指令准备交接，尚无 Agent 执行回执**。后续 T06 依实际反馈定向处理，T07 独立 Testing/Supervisor、T08 最终人工 Gate、T09 结项。
- **STOP**：批准原件缺失/SHA 不匹配、冻结 UI B 类差异未经单独批准即实施；未经授权真实/生产/共享数据库操作、真实敏感 AI 调用、任何 Git 历史强推；新增安全 P0/P1、必备独立/人工 Gate 不通过。

## Change Log

| 版本 | 日期 | 内容 | 状态 |
|---|---|---|---|
| v1.5 | 2026-10-26 | S5 T06 PASS、简化放行、T08 待执行 | 已被最新 S5 CLOSEOUT 事实替代 |
| v1.6 | 2026-10-27 | S5 本地 CLOSED、远端 `REMOTE_SYNC_BLOCKED`、S6 候选完整计划与延期 UX/AI 最终 Gate 责任 | 历史替换候选 |
| v1.7 | 2026-10-27 | 补录 GitHub 直连故障与历史代理，记录负责人当时暂停执行；负责人后续确认已放入 Project | 历史现行状态，即将被 v1.8 替换 |
| **v1.8** | **2026-10-27** | 根据 Cursor 实际回执登记 S5 非强制快进 Push 成功、PR #2 head 更新及无 CI PASS；记录 S6 只读调查、A/B/C 裁定和新 Review v0.2 待批准 | PROJECT REPLACEMENT CANDIDATE，未自动同步 |
| **v1.9** | **2026-10-27** | 负责人正式批准 S6 初版完整 Review v0.3，记录 Approved、独立批准 SHA、T00～T05 已授权待 Cursor 执行及冻结 UI 优先的实施顺序 | PROJECT REPLACEMENT CANDIDATE，待负责人放入 Project 与后续合法 Git 归档 |
