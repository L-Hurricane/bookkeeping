# 04丨记账软件｜2026-10-27 审批索引 v0.13

- **状态**：现行索引入口的增量。不是新的 Git 权限类别，不批准合并 main、部署、T08 或 UAT-03 编码。
- **相对 v0.12**：原件字节不改。v0.12 SHA-256 仍是 `31d5ecda150c100c7c513e38ba9d5f7d85283bbd317e044765d77010fd9106cd`。

## S6 UI-A01～A09 与 UAT-03 评估

| 事项 | 原件 | SHA-256 |
|---|---|---|
| UI Freeze Diff Review v0.1 | `docs/implementation/history/bookkeeping-v1.0-ui-freeze-diff-review-v0.1.md` | `48570a6ea649287bbc129c76fa04b27ed6f8bef3bd9a87d012f2099d7855b1dc` |
| S6 Plan Amendment Review v0.5 | `docs/implementation/history/bookkeeping-v1.0-s6-ui-scope-gate-amendment-review-v0.5.md` | `34393969b323ad9c57b05b775cbe64bd4953cd670d5c54b9903c72d269be906d` |
| UI Freeze Diff Approved v0.1 | `docs/implementation/bookkeeping-v1.0-ui-freeze-diff-v0.1-approved.md` | `1bf593791914e7949686c22509ad49a2ea306ca6cb7e762a807dce0ff8dbe02e` |
| S6 Plan Amendment Approved v0.5 | `docs/implementation/bookkeeping-v1.0-s6-ui-scope-gate-amendment-v0.5-approved.md` | `2c0e701fed13247bf443d19dbd3954a59554fdcbd7c1188f7fd15d6fa6721ea6` |
| 批准记录 | `docs/implementation/bookkeeping-s6-ui-scope-approval-record-20261027-v0.1.md` | `5aff15e36ef8312d62d0d4fb145aee0a8ec9486e706b0f448f67c7e535681fc0` |
| UAT-03 评估简报 | `docs/implementation/bookkeeping-s6-uat03-transaction-running-balance-assessment-brief-v0.1.md` | `36182fa58accacf1296887b05644c9e52d2eb29b09b0d31d18801a13d25cbb36` |
| 状态交接 v2.2 | `docs/governance/bookkeeping-current-state-handoff-2026-10-27-v2.2.md` | `f62538853ea9b431c70cb0cd32a5e1a3dd7d0425e3c50d59e740a86926b1f8ae` |

两份 Review 正文里的 Pending 不回写。v0.3 计划和 v0.4 原件不改。本索引登记已批准的 UI-A01～A09。UI-B01 自定义账户分组不编码。UAT-03 只做源码评估，不编码。不批准 T08、S6 CLOSED、真实模型调用、Schema 或数据库操作。旧 T07 PASS 不覆盖这次新产品代码。
