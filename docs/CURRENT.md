# 现行文档入口

本文件只记录路径、版本、SHA-256、批准状态和读取顺序。审批索引不是批准原件。正式正文以各文件原字节为准。

本次归档是 S2-T00 的组长批准实施细化：把已核对的正式文件放入可恢复任务分支。已批准的 S2 初版计划原件未被修改。

基线：`release/v1.0` `663e76d4b1e1ac6a7979211299fd7225dfb3236d`。任务分支从该提交新建。

## 当前执行状态

- S2-T10 / M2-AUTO-GATE：**PASS**。组长 2026-10-24 裁断。未解决 P0/P1/P2 为 0。残留 P3 是模拟完整性异常下的事务回滚，没有直接触发 InnoDB `RESTRICT`，非阻断，未标成已修复。
- S2-T11 / M2-HUMAN-GATE：**CLOSED**。结项提交 `62f1910372b6960bf3630d7cb26ff33e01089ecd`。负责人 2026-10-25 确认功能人工 Gate PASS，UAT-06 五项 PASS，并确认 A+B-lite 停止扩建自动交接、保留现有校验和证据目录。这不是 v1.0 发布，不是合并 main，也不是生产部署。结项记录：`docs/implementation/bookkeeping-s2-stage-closeout-v0.1.md`，SHA-256 `2e759e59ca4b4c56deba7672a069fb909581f63e47d9304b7f3244d84203184c`。
- Git 交付：`origin/release/v1.0` 已快进到 `62f1910372b6960bf3630d7cb26ff33e01089ecd`。Draft PR #2 仍是 Open / Draft，`release/v1.0` → `main`，未合并。`main` 仍是 `a393734ad147a5dbe11a83cebfd3381f408db7b2`。其后的本地文档归档未 Push。
- 已批准 S2 计划：`docs/implementation/bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md`，SHA-256 `69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5`。本状态不修改该文件。
- S3 初版完整计划：**APPROVED**（2026-10-25）。`docs/implementation/bookkeeping-v1.0-stage-s3-plan-v0.1-approved.md`，SHA-256 `c871888bfa1ee09996e005adcdbe94afb568837699a425631c02a8dc396a79ef`。Review 原件 `docs/implementation/history/bookkeeping-v1.0-stage-s3-plan-taskplan-review-v0.1.md`，SHA-256 `6ef022619da77202502a5b636ec9be24cd785ee47c75b0946d79cbfd79229b06`。批准记录 `docs/implementation/bookkeeping-v1.0-stage-s3-plan-v0.1-approval-record.md`，SHA-256 `3b4e92b615ac2f3254a46769bc43e7bb585725f965f79a733c412203854ba05d`。
- 当前任务 S3-T00：**IN PROGRESS**。Schema 草案已写入，待组长 Review；未开始 T01，未执行 DDL。草案 `docs/implementation/s3-t00-db-ct-01-schema-draft-2026-10-25.md`，SHA-256 `e75b2217fac9e93657c2a6ae90d7f04f2632b5b79062378795fc47b16d2afe32`。
- DB-CT-01：**PENDING**。正式 Schema 与迁移未批准。
- 质量与发布候选 Backlog：`docs/implementation/v1.0-post-s2-quality-acceptance-backlog-v0.1.md`，SHA-256 `71bf3667ff6d1a929dc0f414c693b6cce5bb3be0ab239faa2a969b2244cce4a4`。状态 Deferred，未批准实施。QA-ENV-01、QA-DATA-01、QA-DATA-02、QA-E2E-01、QA-MULTI-01、QA-COMPAT-01、QA-READY-01、REL-01、OPS-01 全部 Deferred。优先完成 v1.0 剩余业务功能；功能完整后再综合评估。当前不开展质量验收环境、多端自动化、发布稳定性、监控告警等非功能建设。发布稳定性与生产监控在首次正式对外发布前核定。
- S1-T08-OBS-01 / UI-NAV-01 仍在 Backlog。延期依据是 S1 结项时负责人同意暂缓，以及 2026-10-25 接受不再恢复微信原生主页按钮。后续仍是「账本 / 统计 / 我的」。最晚复核节点是完整 v1.0 产品/UI 人工验收前。
- UAT-03 每笔历史流水的交易后余额仍在 Backlog。延期依据是 2026-10-25 组长安排，不在本轮编码。最晚复核节点是后续相关 Stage 再评估，不由本次 S2 结项代替。
- WS-01～WS-07 仍在既有《S2 结项后工作区收敛与自动清理待办》，Deferred。不因 S2 CLOSED 启动目录扫描、清理或治理修订。
- 仓库内治理原件仍是下表 v1.6。本机 Downloads 的 v1.7 SHA-256 为 `4fcf410092429fa97aa21baaddc91b027fa0d006cee08af59e46b49bac4c56c1`，§4.8 的既有 `release/v1.0` 非强制 Push 授权继续有效，§4.9 不新增 Git 权限。负责人已上传 ChatGPT Project 唯一现行状态 v1.1。本仓库入口不替换该 Project 文件。

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
- T11 验收指南：`docs/implementation/s2-t11-uat-guide-2026-10-24.md`，当前 SHA-256 `ba32d5a29dd248d810cce7f0bd10a1a1ed726ea069be6cafb6c785cb6d971f95`。
- T11 恢复阻断记录：`docs/implementation/s2-t11-restore-drill-2026-10-24.md`，SHA-256 `4c652487c90de040f733c8b59e02d9980b9569822f262177e1fcb375d796ba16`。其后的执行记录是 `docs/implementation/s2-t11-restore-execution-2026-10-24.md`。
- S2 结项：`docs/implementation/bookkeeping-s2-stage-closeout-v0.1.md`，SHA-256 `2e759e59ca4b4c56deba7672a069fb909581f63e47d9304b7f3244d84203184c`。结项提交 `62f1910372b6960bf3630d7cb26ff33e01089ecd`。
- S2 后质量与发布候选 Backlog：`docs/implementation/v1.0-post-s2-quality-acceptance-backlog-v0.1.md`，SHA-256 `71bf3667ff6d1a929dc0f414c693b6cce5bb3be0ab239faa2a969b2244cce4a4`。Deferred，未批准实施。
- S3 计划与 DB-CT-01 草案见上方执行状态。治理 v1.7 仍不入库；本批核对的仓库外原件 SHA-256 仍是 `4fcf410092429fa97aa21baaddc91b027fa0d006cee08af59e46b49bac4c56c1`。
- 转账默认选择补充：`docs/changes/v1.0-s2-uat-02-transfer-account-selection.md`，SHA-256 `a4c44839324e9d2470cc2e9648005d0dceab0110e7742b697163d259685cef7d`。不替换 PRD 或 UI Freeze。
- 审批索引 v0.5 原件不改。本入口记录上述结项文件的位置和 SHA。
