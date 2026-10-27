# 04丨记账软件｜S6 初版完整 Stage/Task Plan v0.3 · 独立批准记录

- **日期**：2026-10-27
- **状态**：**APPROVED / S6 初版完整 Stage + Task Plan 获批**
- **审批人**：项目负责人
- **负责人明确批准原话**：“批准v0.3，你生成正式版，我放入Project，如果需要传入Cursor，你说明即可”
- **审批原件**：`bookkeeping-v1.0-stage-s6-ui-final-plan-taskplan-review-v0.3.md`
- **审批原件 SHA-256**：`2ca8c1a2c44a55b85f9e4d09900e2bc39bb85a5cde99973a339b7bd47adb116a`（原字节保留，正文历史 Pending 不改）
- **正式计划**：`bookkeeping-v1.0-stage-s6-ui-final-plan-v0.3-approved.md`
- **正式计划 SHA-256**：`8619081f735ed6a73ab2552d5eca6cac8e0299ad6488f83a0a41ddafa68b20d7`
- **历史 Review**：v0.1 SHA `4f7b11a858944e7b7123b54cfc7160b7bb35533bf0baebed8171a35087bed259`；v0.2 SHA `7372358aae9f025dbdfb0b57d749a5b1ed2a023025ac475116137388649eba8a`。二者均属未批准的历史草案，不替代 v0.3。

## 一、批准内容与适用版本

负责人正式批准 S6 v0.3 **T00～T09 完整初版 Stage/Task Plan**：首先严格落地已确认的冻结 UI，包括主导航、分类标签全账本管理入口、统计净值／收入／支出分区及已登记的冻结 UI 缺口；在 T05 完成 Coding 自测、必要回归并提供可运行基础功能体验版本；T06 依据实际反馈定向修复并决定是否提出独立 UI/产品变更，**不是预先批准 UI 升级**；T07 保留一次独立 Testing + Supervisor Gate；T08 保留负责人一次 v1.0 最终统一人工 UAT；T09 在 Gate 通过后结项及现有 Git 的合法交付。

这项批准授权依既有治理执行合法 **S6-T00～T05**，无需因普通技术选择、内部任务调整和范围内修复逐条请示。负责人在 T05 观察基本功能不构成新增强制独立 Gate，不可冒写正式 v1.0 最终 UAT PASS。该 Stage 的具体实施结果、Git HEAD、测试仍以 Cursor 真实回执为准。

## 二、未获批准的保留事项

- 不批准 B 类冻结 UI 变更（MoneyWiz 式重建报表、取消旧单账户 AI 入口、增加 `UAT-03` 逐笔后余额等）；只有形成**具体差异**且得到负责人独立批准后才可编码。无确证必要则延期，不制造工作。
- 不变更 PRD、UI Freeze 或核心账务语义（包括金额正红负绿、统计正确性、标签去重、AI 额度、永久删除方案 B）；S1～S5 已经通过的核心功能优先保证稳定。
- 不授权未审批 Schema/历史迁移、`bookkeeping` 或 `bookkeeping_test` 等日常/真实/共享库操作、未经授权付费模型调用与数据外传、生产部署、Agent 合并 main、新 PR／共享分支、强推或不可逆清理。
- 继续一个 Coding Agent 串行；保留独立 T07 和人工 T08。现有 `release/v1.0` 非强制 Push 仅在获批 Gate 和 v1.8 §4.8 边界内执行。

## 三、批准时实际项目事实与归档

- S1～S5 已 CLOSED；S5 **HUMAN UAT DEFERRED TO v1.0 FINAL GATE**，并非已经完成最终人工 UAT。S5 已在现有 `release/v1.0` 非强制快进同步，回执 SHA `676fca14ed834837a306df3f58f8938485d94abf`；现有 Draft PR #2 Open/Draft、CI 检查数 0 不等于 PASS；main 未合并。
- S6 在审批前只做只读调查，**尚未开始 S6 Coding**。Cursor 报告本地尚无上述 S6 Review 原件；需要由负责人将必要批准材料带入 Mac Cursor 可访问位置，Cursor 核验 SHA 后再合法启动任务。
- 本记录、正式 Approved v0.3 及原 Review v0.3 随下一合法 T00 归档到 Git `docs/implementation/`，更新 `docs/CURRENT.md` 与批准索引，并记录文件 SHA。Project 与 Git 不自动同步。

## Change Log

| 版本 | 日期 | 变化 | 状态 |
|---|---|---|---|
| v0.3 | 2026-10-27 | 负责人明确批准 Review v0.3，绑定原件 SHA 和正式 Approved v0.3、记录实施与保留审批边界 | APPROVED |
