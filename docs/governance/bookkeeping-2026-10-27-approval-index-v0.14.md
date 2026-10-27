# 04丨记账软件｜2026-10-27 审批索引 v0.14

- **状态**：现行索引入口的增量。不是新的 Git 权限类别，不批准合并 main、部署、T08、S6 CLOSED 或真实模型调用。
- **相对 v0.13**：原件字节不改。v0.13 SHA-256 仍是 `3f72033772fa56702819930f7586aa1068b393366f7748ed5451a4d1f439bf76`。

## S6 家庭账本隔离库

| 事项 | 原件 | SHA-256 |
|---|---|---|
| 隔离库授权 Review v0.1 | `docs/implementation/history/bookkeeping-s6-isolated-family-e2e-db-authorization-review-v0.1.md` | `d3959800aa6eff3dd370fbd306c54233bd4dbcf607197b8078b3a6603cc1f968` |
| 批准记录 | `docs/implementation/bookkeeping-s6-family-e2e-db-approval-record-20261027-v0.1.md` | `117dc94ccf53973952621c16d0f7afd186b3298409f730ec7f07e7180712e3f6` |
| Cursor handoff v0.1 | `docs/implementation/history/bookkeeping-s6-t07-family-e2e-unblock-cursor-handoff-v0.1.md` | `e8b27cb26e22ed11c67d6d372e5b6a1f72643ef243084a053c0e033c9367d30b` |
| 端到端设计 v0.1 | `docs/implementation/bookkeeping-v1.0-s6-e2e-family-uat-test-design-v0.1.md` | `b4d42224aa3a92c716dc9aa1a69b74114eba02cd255895fd1e402675c9eaba32` |
| 状态交接 v2.4 | `docs/governance/bookkeeping-current-state-handoff-2026-10-27-v2.4.md` | `4abb937d0a7d2217cb62e318aa6c867f173557a4c8f3db665c7e9b8e020473fe` |

Review 正文里的 Pending 不回写。批准范围只有新库 `bk_s6_family_e2e_20261027`。不写 `bookkeeping`、`bookkeeping_test`、`bookkeeping_migration_test`、`bk_s1t03_6928fd20_test`、`bk_s1t03_6928fd20_migration`。UI-B01 与 UAT-03 不编码。负责人已决定 UAT-03 在 v1.0 核心交付后再评估。本索引不把 T07 记成 PASS。
