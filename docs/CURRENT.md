# 现行文档入口

本文件只记录路径、版本、SHA-256、批准状态和读取顺序。审批索引不是批准原件。正式正文以各文件原字节为准。

本次归档是 S2-T00 的组长批准实施细化：把已核对的正式文件放入可恢复任务分支。已批准的 S2 初版计划原件未被修改。

基线：`release/v1.0` `663e76d4b1e1ac6a7979211299fd7225dfb3236d`。任务分支从该提交新建。

## 当前执行状态

- S2-T10 / M2-AUTO-GATE：**PASS**。组长 2026-10-24 裁断。未解决 P0/P1/P2 为 0。残留 P3 是模拟完整性异常下的事务回滚，没有直接触发 InnoDB `RESTRICT`，非阻断，未标成已修复。
- S2-T11 / M2-HUMAN-GATE：**未结项**。负责人 2026-10-25 只确认 UAT-06 五项检查 PASS。归档、转账、永久删除、金额颜色和 A+B-lite 去留仍没有负责人总评。独立前端测试 219 项通过，定向复核 P0/P1/P2/P3 为 0。这不代表整项人工 PASS，也不代表 S2 CLOSED。记录：`docs/implementation/s2-t11-closeout-candidate-2026-10-25.md`。
- 已审核代码：`bf7074a15a1e7e2b14b34e3d1ffeef3d48e9b35b`。本状态记录提交在其后，完整 HEAD 以 `git rev-parse HEAD` 为准。
- 已批准计划：`docs/implementation/bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md`，SHA-256 `69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5`。本状态不修改该文件。
- 下一责任人：项目负责人。按 `docs/implementation/s2-t11-uat-guide-2026-10-24.md` 做功能验收和 A+B-lite 去留。组长不代填结论。
- S1-T08-OBS-01 / UI-NAV-01 仍在 Backlog。延期依据是 S1 结项时负责人同意暂缓，以及 2026-10-25 接受不再恢复微信原生主页按钮。后续仍是「账本 / 统计 / 我的」。最晚复核节点是完整 v1.0 产品/UI 人工验收前。
- UAT-03 每笔历史流水的交易后余额仍在 Backlog。延期依据是 2026-10-25 组长安排，不在本轮编码。最晚复核节点是后续相关 Stage 再评估，不由本次 S2 结项代替。
- 仓库内治理原件仍是下表 v1.6。本机 Downloads 的 v1.7 SHA-256 为 `4fcf410092429fa97aa21baaddc91b027fa0d006cee08af59e46b49bac4c56c1`，§4.8 的既有 `release/v1.0` 非强制 Push 授权继续有效，§4.9 不新增 Git 权限。本入口不表示 ChatGPT Project 已同步。

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
| `docs/governance/bookkeeping-ai-collaboration-governance-v1.6.md` | 治理 v1.6 | `364a2dc1c22ea9d3dce7ed209d77fdc36ed90fe759417acdedca10d19c9e59d1` | Approved / 仓库内原件。现行正式版是仓库外 v1.7，见上方执行状态 |
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
- DB-02 批准记录：`docs/implementation/s2-db-02-approval-2026-10-23.md`。批准范围是 T05 提交的 V16 单表 DDL 原文。该记录不修改上面的正式计划 SHA，也不授权对任何库执行 Flyway。
- M1 自动门禁通过后必须暂停，向负责人复盘；确认前不得开始 M2。该暂停已在 M2 开始前完成，本行保留为历史规则。
- T10 证据链：`docs/implementation/s2-t10-gate-evidence-2026-10-24.md`，SHA-256 `9e10841e478534971d532f441ef4a386367a735928b3e12d0d700a0c9300e020`。
- T11 验收指南：`docs/implementation/s2-t11-uat-guide-2026-10-24.md`，SHA-256 `76728ecaac072168e2eeb5c881e6c1bd2145694d18b10843f5498ee31ff7bb16`。
- T11 恢复演练记录：`docs/implementation/s2-t11-restore-drill-2026-10-24.md`，SHA-256 `4c652487c90de040f733c8b59e02d9980b9569822f262177e1fcb375d796ba16`。状态 BLOCKED。
