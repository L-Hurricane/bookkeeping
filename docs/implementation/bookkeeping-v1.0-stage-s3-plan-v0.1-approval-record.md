# 04丨记账软件｜Stage S3 初版完整 Stage / Task Plan 独立批准记录 v0.1

- 日期：2026-10-25
- 状态：**Approved / 独立批准事实记录**
- 批准对象：`bookkeeping-v1.0-stage-s3-plan-taskplan-review-v0.1.md`
- **获批审阅原件 SHA-256**：`6ef022619da77202502a5b636ec9be24cd785ee47c75b0946d79cbfd79229b06`
- 负责人批准原话：**“看了一下计划顺序，没有问题，按你所述进行开始吧”**
- 关联正式版：`bookkeeping-v1.0-stage-s3-plan-v0.1-approved.md`

## 批准范围

负责人批准原审阅稿所列 S3（CT-01～CT-10）范围与完整初版 T00～T08、依赖关系、S3-T06 独立 Testing/Supervisor Gate 及 S3-T07 唯一强制人工 UAT。按治理 v1.7 在既定 Gate 和范围内由组长自主安排合法任务执行、测试与范围内修复，保持一个 Coding Agent 串行。

当前启动任务：**S3-T00 / DB-CT-01 审批前置的只读调查与设计**。允许核实既有 Schema/关联/约束/历史兼容问题并撰写 **Flyway 增量 SQL 草案、迁移安全/验证/恢复方案**，提交组长 Review 与负责人专项审批。

## 不属于本次批准的事项

- **DB-CT-01 正式 Schema / DDL 与额外历史数据迁移**：尚未独立审批，不能执行迁移 SQL，也不能提前开展依赖新 Schema 的实际编码。
- 未批准的真实/生产数据读写、任意数据库清理与恢复性不明的覆盖操作、提权、不可逆清理。
- 改动 PRD、UI Freeze、冻结领域/架构、Stage 范围与人工 Gate。
- 新基础设施类别、额外 AI 平台或 QA 环境建设，以及全部已 Deferred 非功能 Backlog。
- 新共享远端分支或 PR、Force Push、合并 main、部署；既有 release/v1.0 有界权限仍由治理 v1.7 和必要 Gate 单独约束。

## 原件与归档规则

原 Review v0.1 仍保留 `Review / PENDING OWNER APPROVAL` 原文和原始 SHA；本文件是批准事实依据，不修改旧字节、不得以旧文稿内的 Pending 字样否定后续明确批准。正式 Approved 版另存并独立计算 SHA。

当前本 Chat 仅生成上述文件，不代表文件已进入负责人 Mac 本地 Git 或 Project。Cursor 须核验附件实物与 SHA，在合法的本地资料归档范围内存放，不得自动 Push。

## Change Log

| 版本 | 日期 | 内容 | 状态 |
|---|---|---|---|
| v0.1 | 2026-10-25 | 登记负责人批准 S3 初版完整 Stage/Task Plan v0.1，界定 T00 当前范围与 DB-CT-01 专项保留审批 | Approved |
