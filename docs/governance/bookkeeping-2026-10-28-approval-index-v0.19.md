# 04丨记账软件｜2026-10-28 审批索引 v0.19

- **状态**：现行索引入口的增量。登记 T08 PASS 与 S6 结项依据，并登记 LocalTrial-01 的已批准计划。不合并 `main`，不创建个人正式库，不授权真实数据导入。
- **相对 v0.18**：原件字节不改。v0.18 SHA-256 仍是 `fde53fe9d34326a4b4f01160999673502ca4dc6d0b5a7418f885df52505a3bb3`。

## S6-T08 PASS 与 T09

| 事项 | 原件 | SHA-256 |
|---|---|---|
| T08 负责人决定 | `docs/implementation/bookkeeping-s6-t08-owner-decision-20261028.md` | `3e55300d27ec4c8d7abf349da9ce879e12f0a02e496eecb84f09110d4f93faca` |
| S6 结项 | `docs/implementation/bookkeeping-s6-stage-closeout-v0.1.md` | `f7a2adbfc557431db7820c2ef8d5164b3658fe989dfb9b2ae3fb6377016a03a6` |
| S6 结项遗留 | `docs/implementation/v1.0-s6-closeout-backlog-v0.1.md` | `9c0f9b0d8e394772b6dc67e621e41a271f5b61f0ac953b67ef5fc2767c480406` |

负责人原话是「达到标准，现在我期望真实使用」。这是 T08 PASS。结项记录另列未测项。

## LocalTrial-01

| 事项 | 原件 | SHA-256 |
|---|---|---|
| 批准记录 v0.1 | `docs/implementation/LOCALTRIAL-01-APPROVAL-RECORD-v0.1.md` | `f4b494c8faedea7d17cd100bb21e2e5896a558d6bf923257ad60194f8eda15e0` |
| 计划 Approved v0.3 | `docs/implementation/LOCALTRIAL-01-STAGE-TASK-PLAN-v0.3-approved.md` | `f86b5335541e267fbead4f9bcbb9a0f4313d686fb4c17adfda3a7306843c9d74` |

负责人原话是「批准，调整为先保证启动可用，再迁移数据。」v0.2 原件 SHA-256 `bd516bdf57561630d576453b4afa29cc2d3560ebd657dfbe75c82582bd46d091` 未随本包入库，原字节不在本索引中改写。该批准不代替 `main` 合并，也不授权创建 `bk_personal_local` 或读取真实账单。
