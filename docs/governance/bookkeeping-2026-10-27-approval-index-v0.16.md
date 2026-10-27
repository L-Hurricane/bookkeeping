# 04丨记账软件｜2026-10-27 审批索引 v0.16

- **状态**：现行索引入口的增量。不批准 T08 PASS、S6 CLOSED、合并 main、部署或额外模型调用。
- **相对 v0.15**：原件字节不改。v0.15 SHA-256 仍是 `c2b2394266077e603f000f0d8e6e02814151047c3d162e5a418b9969b4c37b26`。

## S6-T08 模型与局域网授权

| 事项 | 原件 | SHA-256 |
|---|---|---|
| 授权 Review v0.1 | `docs/implementation/history/bookkeeping-s6-t08-remaining-authorizations-review-v0.1.md` | `b15181c76be0b26b3a912cb7b8476bbfc887508488694f5e9a6a13aba9f8c0f7` |
| 批准记录 | `docs/implementation/bookkeeping-s6-t08-remaining-authorizations-approval-record-20261027-v0.1.md` | `9c2089e314d1beeab386c35a2426c82fe46b5c59c309a09f11a5d74977cc6125` |
| Cursor handoff v0.1 | `docs/implementation/history/bookkeeping-s6-t08-authorized-model-lan-cursor-handoff-v0.1.md` | `1204711fd84a124cc88d8521eeb2726a6c53484cf4b0a62c53e843e799fccad0` |
| 状态交接 v2.6 | `docs/governance/bookkeeping-current-state-handoff-2026-10-27-v2.6.md` | `eec3f0e4bac1b840329f7c3187156cd0614a4b7fbfa4d4b6d739ef8ab45c8529` |

Review 正文里的 Pending 不回写。负责人原话是“同意”。本索引不把自动用户的两次成功写成 T08 PASS。试用用户的那一次仍须负责人亲自点击。真机仍是 NOT TESTED。
