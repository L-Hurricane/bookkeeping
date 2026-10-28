# 04丨记账软件｜2026-10-28 审批索引 v0.18

- **状态**：现行索引入口的增量。不批准 T08 PASS、S6 CLOSED、Push、合并 main 或部署。
- **相对 v0.17**：原件字节不改。v0.17 SHA-256 仍是 `685a5e260c2e771c837f9ea70218cbf4f05236d97c6b754b7a6a31ffcdec675c`。

## S6-T08 UI-FB-016～019

| 事项 | 原件 | SHA-256 |
|---|---|---|
| UI 差异与批准记录 v0.1 | `docs/implementation/bookkeeping-s6-t08-fb016-019-ui-diff-and-approval-v0.1.md` | `db34c71e39d5959c422972624097c6ed1c87bd3ed5a478f02900671cf916c3e5` |
| 交易详情 Design Review v0.1 | `docs/implementation/history/bookkeeping-s6-t08-transaction-detail-edit-design-review-v0.1.md` | `829d06b34bad2bf3567983f7753039a22bd2df1c285f88e37779df665034a3c9` |
| Cursor handoff v0.1 | `docs/implementation/history/bookkeeping-s6-t08-fb016-019-cursor-handoff-v0.1.md` | `be4bc1623c4689dad82dc88dedbff055cecd297654dfa3120f6e40ecff18dc3d` |
| 状态交接 v3.1 | `docs/governance/bookkeeping-current-state-handoff-2026-10-28-v3.1.md` | `7167f8f5b50ffa9cdf92db4cf79e02d2a8ca20f804879850558b0cea3992b7f0` |
| 详情/编辑预览 | `docs/implementation/history/bookkeeping-s6-t08-transaction-detail-edit-preview-v0.1.html` | `fc43433ad78837b2b020978395721fe2887b54749447cadda41147da150b7b7e` |

Review 正文里的 DESIGN REVIEW / PENDING 不回写。负责人原话是“同意你的这个建议。其他暂时没发现问题，对这一轮排查做一下调整修复。”预览只作视觉依据。产品代码是 `f8ee149a83654fd910677dae606827dc03bb6793`。本索引不把本轮写成 T08 PASS。
