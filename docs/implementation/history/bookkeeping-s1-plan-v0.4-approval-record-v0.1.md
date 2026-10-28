# 04丨记账软件｜S1 Stage + Task Plan v0.4 独立批准记录 v0.1

- 批准日期：2026-10-22
- 状态：**Approved / v0.4 正式替代 v0.3**
- 审批人：项目负责人
- 批准依据：负责人在审阅 S1 Plan Review v0.4 后明确回复 **“同意，开始执行”**。
- 获批原件：`bookkeeping-v1.0-stage-s1-plan-taskplan-review-v0.4.md`
- 获批原件 SHA-256：`e1615d7913334fc3b26024a9de4c5139380cfb1c081d62245116a9e3135ad12b`
- 前一获批版本：S1 Plan v0.3，SHA-256：`e13ef4f40fdba7c9a4258336e8f56a9441b900453808819552feb6c12515543e`。
- 当前正式治理：《04丨记账软件｜AI 研发组织与协作治理基线 v1.3》（Approved）。

## 一、批准范围

1. 仅在既有 **S1-T07R** 内，额外允许修改 `backend/src/test/java/com/limingyuan/bookkeeping/BootstrapIntegrationTest.java` 中因合法新增 V14 而失效的 Flyway 迁移数量/版本断言。
2. 必须保留精确测试约束；不得用宽松下限或动态数量替代。不得修改该测试文件的其他逻辑或扩大其他业务、数据库、UI 代码范围。
3. 继续在获批隔离工作区、`task/s1-t07r` 分支由唯一 Coding Agent 串行处理；核验 Plan 原件 SHA、原 v0.3 审批链、V14 已成功及现有工作树差异。
4. 仅执行必要定向验证、后端广泛回归（沿用现有安全排除项）、前端 typecheck/lint/test；**不再执行 V14 数据库恢复/DDL、Flyway.clean/repair**，不操作真实或生产数据库，不自动 Git commit、push、PR、merge 或部署。
5. T07R 编码和本地测试通过**不等于** Stage 门禁通过：仍需先完成 T07R 独立审查，再由独立 Testing Agent 复验 T06，独立 Supervisor 复审 T07，只有明确 PASS 后才进入 T08 人工验收。若新缺陷需要计划越界，停止并提交负责人决策。

## 二、批准事实与原件保存

获批 v0.4 原件保持 **原始字节及上述 SHA-256**；原文中的 `Review/Pending` 仅说明编制时状态，由此独立批准记录证明其后获得授权。归档到隔离工作区 `authorization/` 不等于 Git `docs/` 已入库或 Project 已自动同步。

## Change Log

| 版本 | 日期 | 变更 | 状态 |
|---|---|---|---|
| v0.1 | 2026-10-22 | 独立记录 S1 Plan v0.4 的批准、原件 SHA、v0.3 替代关系、仅一处 Bootstrap 测试适配及不变门禁 | Approved Record |
