# 04丨记账软件｜2026-10-27 审批索引 v0.10

- **状态**：现行索引入口的增量。不是新的 Git、部署或生产授权。
- **相对 v0.9**：原件字节不改。v0.9 SHA-256 仍是 `070456554da63a79cd17eb04d181bac7c297fe43ca230a5a3f407be64879ffd1`。v0.8 SHA-256 仍是 `a06b91c45b2de870d6f9eb95a2197faaf5220d55285546a8bb4abdefefe9040a`。

## S5 结项所依据的批准

| 事项 | 原件 | SHA-256 |
|---|---|---|
| S5 Plan Approved v0.2 | `docs/implementation/bookkeeping-v1.0-stage-s5-ai-plan-v0.2-approved.md` | `c7add0aefce153441ce4d22c0907ec549b362eaedafd713df37cfe33a816f081` |
| S5 人工 Gate 修订 Review v0.2 | `docs/implementation/history/bookkeeping-v1.0-s5-human-gate-adjustment-review-v0.2.md` | `6b31c3cd371ecf66b9a13599aa98149806a3d275cfeba27353519aaa68b4c870` |
| S5 修订批准记录 v0.2 | `docs/implementation/bookkeeping-s5-stage-plan-amendment-approval-record-v0.2.md` | `7f403b8906311e92aa0cdcac4a30bbfc36fba26605f41ac94bcb8273f6e22914` |
| S5 Plan Approved v0.1 | `docs/implementation/bookkeeping-v1.0-stage-s5-ai-plan-v0.1-approved.md` | `1958595f6ff480273fb460d3e70245167f8aff6adf7d19cd7332ef1708ecf964` |
| 状态交接 v1.5 | `docs/governance/bookkeeping-current-state-handoff-2026-10-26-v1.5.md` | `7506feaffa531cdc127a6720c86c7ae24ea7db5b5087d43536e3d378c15b715a` |
| 仓库外治理 v1.8 | `~/Downloads/bookkeeping-ai-collaboration-governance-v1.8.md` | `a8fb22ac207e06be3341826be5690d91b7c7f6d74e81937b26e5a2cb0bd987b0` |

v0.1 计划、v0.1 Review、v0.1 批准记录和 v1.4 交接原字节不改。Review v0.2 正文仍是 Pending，不回写。v1.8 不入库。§4.8.3 既有 `release/v1.0` 非强制 Push 授权继续有效，不新增 Git 权限。

方案 B 不变：永久删除任一账户后，该用户已保存的全账本月报和年报不再可读。已消耗次数不退回。不提供账户恢复。

## T06 与阶段放行

| 证据 | 位置 |
|---|---|
| Gate 报告 | `docs/implementation/bookkeeping-s5-t06-gate-v0.1.md`，SHA-256 `7ebb394c4c7d3018a4f9b5855d8d418767be200e8a2a392bc665b90a7cc3269c`，提交 `a38a186af89d541ee9d79bb3fc4b4116795b78f8` |
| 受审产品代码 | `d3d785633bc5f6e06673692a2c4d64e17d748017` |
| 结项记录 | `docs/implementation/bookkeeping-s5-stage-closeout-v0.1.md`，SHA-256 `503ac13f2fea7300f5b811c49a04ef0851c245364c6d5e97526f2927f5c2e7c7` |
| 结项 Backlog | `docs/implementation/v1.0-s5-closeout-backlog-v0.1.md`，SHA-256 `12f4f722daae40e18c8e900b750199ea4f9869b2641cd8ecaba5f2ecfe111c1e` |

T06 Testing PASS，Supervisor PASS，P0/P1 = 0。负责人 2026-10-26 确认修订并作阶段放行。S5 人工功能 UAT 未执行。结项状态是 `S5 CLOSED — HUMAN UAT DEFERRED TO v1.0 FINAL GATE`。
