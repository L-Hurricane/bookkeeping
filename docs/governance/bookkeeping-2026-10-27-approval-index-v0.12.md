# 04丨记账软件｜2026-10-27 审批索引 v0.12

- **状态**：现行索引入口的增量。不是新的 Git 权限类别，不批准合并 main、部署或 T08。
- **相对 v0.11**：原件字节不改。v0.11 SHA-256 仍是 `6319c26bb753af0eaf4e02309e2e90c073c1f16e5ae9901ba577344ac9cdfa9c`。

## S6 v0.4 中间同步批准原件

| 事项 | 原件 | SHA-256 |
|---|---|---|
| S6 中间同步 Review v0.4 | `docs/implementation/history/bookkeeping-v1.0-s6-interim-draft-pr-timing-review-v0.4.md` | `6552ff6a85834ec6e9f751fdd2f9620ebbed8acf385d576a2fc3a1f0f055aec5` |
| S6 中间同步 Approved v0.4 | `docs/implementation/bookkeeping-v1.0-s6-interim-draft-pr-timing-v0.4-approved.md` | `20cf53e6ed041e58c206f955bf51c432c97c09f17131e3027194b14e57ca0044` |
| S6 中间同步批准记录 v0.4 | `docs/implementation/bookkeeping-s6-interim-draft-pr-approval-record-v0.4.md` | `6e1e6f0c220a8477bca5af00c94e10c4f91b06fab1657752e796bace35652a91` |
| 状态交接 v2.0 | `docs/governance/bookkeeping-current-state-handoff-2026-10-27-v2.0.md` | `953c84217ef1d488d94a23998cfbefb68ef445ebefa4f969ecc6380c745cbf8b` |

Review v0.4 正文里的历史 Pending 不回写。v0.3 计划原件不改。本索引只批准在 T07 双 PASS、P0/P1=0 且远端可快进时，对现有 `release/v1.0` / Draft PR #2 做一次非强制同步。不批准 T08 PASS、S6 CLOSED、真实模型调用或数据库操作。
