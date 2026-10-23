# 现行文档入口

本文件只记录路径、版本、SHA-256、批准状态和读取顺序。审批索引不是批准原件。正式正文以各文件原字节为准。

本次归档是 S2-T00 的组长批准实施细化：把已核对的正式文件放入可恢复任务分支。已批准的 S2 初版计划原件未被修改。

基线：`release/v1.0` `663e76d4b1e1ac6a7979211299fd7225dfb3236d`。任务分支从该提交新建。

## 读取顺序

1. `docs/governance/bookkeeping-ai-collaboration-governance-v1.6.md`
2. `docs/implementation/bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md`
3. `docs/implementation/bookkeeping-s2-two-milestones-scope-v0.1-approved.md`
4. `docs/product/bookkeeping-v1.0-product-baseline-v1.0.md`
5. `docs/product/bookkeeping-v1.0-ui-freeze-v1.0.md`
6. `docs/governance/bookkeeping-current-state-handoff-2026-10-23-v0.8.md`
7. `docs/governance/bookkeeping-2026-10-23-approval-index-v0.5.md`（索引入口，不覆盖上列原件）
8. 已在仓库中的 S1 计划与结项：`docs/implementation/bookkeeping-v1.0-stage-s1-plan-v0.5-approved.md`、`docs/implementation/bookkeeping-s1-stage-closeout-v0.1.md`

## 现行文件

| 路径 | 版本 | SHA-256 | 状态 |
|---|---|---|---|
| `docs/governance/bookkeeping-ai-collaboration-governance-v1.6.md` | 治理 v1.6 | `364a2dc1c22ea9d3dce7ed209d77fdc36ed90fe759417acdedca10d19c9e59d1` | Approved / 当前正式治理 |
| `docs/governance/bookkeeping-2026-10-23-approval-index-v0.5.md` | 审批索引 v0.5 | `b3a950a8223b14d5656e9907ef8c2e95da5539dabad4ee69ef9203c278a8c8f2` | Approved / 现行索引 |
| `docs/implementation/bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md` | S2 Plan Approved v0.1 | `69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5` | Approved / 当前执行计划 |
| `docs/implementation/bookkeeping-s2-two-milestones-scope-v0.1-approved.md` | S2 范围 Approved v0.1 | `b2c4d2abf452d87ab34b1930fe4679d2b8036c8ce8849b9cd4b0b1b0cfc664a7` | Approved / 范围与 Gate |
| `docs/governance/bookkeeping-current-state-handoff-2026-10-23-v0.8.md` | 状态 v0.8 | `65c839c278979f68fa82ea3a6fbe6c04dee0bc8625a4edf0011f5cca7aebbac4` | 现行滚动状态，不是新授权 |
| `docs/product/bookkeeping-v1.0-ui-freeze-v1.0.md` | UI Freeze v1.0 | `791a09b7acb291fb75bd93420500c7c0b15ececcec457cdd10588295f0dbc04a` | UI Review 已通过；索引 v0.5 未单列该文件 SHA |

S2 计划 Review 原件登记 SHA-256 为 `629dd55f3f891b8ae10faebd87f40a55035f91c311719467dc3dc20b4617f9db`。该原件文件不在本次导入清单中，本入口不改写正式计划。

## 当前 S2 任务入口

- 已启动：S2-T00 文档归档与仓库外 A+B-lite 自测；S2-T01 只提交 V15 设计，不执行 DDL。
- DB-01 执行批准记录：`docs/implementation/s2-db-01-approval-2026-10-23.md`。它引用设计提交 `23f705411f1edb10dae3dbb1a3c712670ef42151`，不修改上面的正式计划 SHA。
- DB-01 专用库演练结果：`docs/implementation/s2-db-01-drill-2026-10-23.md`。开发库十分钟 RTO 未测。
- M1 自动门禁通过后必须暂停，向负责人复盘；确认前不得开始 M2。
