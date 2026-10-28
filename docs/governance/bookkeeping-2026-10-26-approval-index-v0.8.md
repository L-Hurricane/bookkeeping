# 04丨记账软件｜2026-10-26 审批索引 v0.8

- **状态**：现行索引入口的增量。不是新的 Git、部署或生产授权。
- **相对 v0.5 / v0.6 / v0.7**：三份原件字节不改。v0.5 SHA-256 仍是 `b3a950a8223b14d5656e9907ef8c2e95da5539dabad4ee69ef9203c278a8c8f2`。v0.6 SHA-256 仍是 `8294176cd2b25fdf7e6316f476035a48df961838ce7fa23858a9857e2dfd3ac2`。v0.7 SHA-256 仍是 `0a9e4c2583f13c970210b54bc256aa6a6e3d3afb97034ff1ae786042edd61c54`。

## S4 结项所依据的已有批准

| 事项 | 原件 | SHA-256 |
|---|---|---|
| S4 Plan Approved v0.1 | `docs/implementation/bookkeeping-v1.0-stage-s4-st-plan-v0.1-approved.md` | `5d014e59de7d5a9a03165720b57abeeeaa1360230d1b424446da210684f20256` |
| S4 Plan Review v0.1 | `docs/implementation/history/bookkeeping-v1.0-stage-s4-st-plan-taskplan-review-v0.1.md` | `25a9876df494f7c5e7c7a2c6947183ff43b7a2c1a977e6a0e01a216398d49c0c` |
| S4 Plan 批准记录 | `docs/implementation/bookkeeping-s4-stage-plan-approval-record-v0.1.md` | `c816212020bf9cb17d0f23824dcb4aad04a4ae88478899bfc96b45d62e9172f7` |
| 仓库外治理 v1.8 | `~/Downloads/bookkeeping-ai-collaboration-governance-v1.8.md` | `a8fb22ac207e06be3341826be5690d91b7c7f6d74e81937b26e5a2cb0bd987b0` |

v1.8 不入库。本索引只登记本次复核过的 SHA。§4.8.3 既有 `release/v1.0` 非强制 Push 授权继续有效，不新增 Git 权限。

## T06 证据

| 证据 | 位置 |
|---|---|
| Gate 报告 | `docs/implementation/bookkeeping-s4-t06-gate-v0.1.md`，提交 `be06303a408ccf09b552595b0906d0082104f1f9` |
| 符号与空字段修复 | `608aca427b53dacd263750fe73081e4c00c68836` |
| 复验测试 | 后端统计单元测试 12 项；小程序 228 项；typecheck 与 lint 通过 |

## 人工 UAT 期间关闭的 P1

| 事项 | 修复 | 复验证据 |
|---|---|---|
| 账本首页统计入口 | `133d3cbcf6b6236be293dac1de2c13a930481145` | `d1dd61daba09315abb4ebe07998077b698172d85` 记录定向 Testing 与 Supervisor PASS |
| 明细中文标题 | `980c150c5737227d00578d199d90c816f4e24fc3` | `c0008bb8f7090d7a3cf0ef71cdb21d92f6de12c1` 记录小程序 230 项、typecheck、lint，以及定向 Testing 与 Supervisor PASS。本结项不重复测试 |

## T07

负责人 2026-10-26 确认五项人工 UAT 全部 PASS，并确认中文标题修复完成。五项是多账户与归档、日期筛选、历史净值含跨期转账、三级分类与未分类、标签父子去重及负或零分母与明细继承。
