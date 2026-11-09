# 04丨记账软件｜2026-11-09 审批索引 v0.23

- **状态**：现行索引入口的增量。登记 v1.2 S1 三项正式批准。
- **相对 v0.22**：原件字节不改。v0.22 SHA-256 仍是 `fb415b3e04e844ac91cd6b91ce3c2ba388f32f1c1eb56db0abdc592edd8e2f0b`。v0.22 里的「C11 Schema 未批准」是当时的句子，不回写。
- **现行正式治理**：Project v1.10，SHA-256 `481a755a00989ee18f9cf140623a86f079d4152cd8714e628ed3d9734a383347`。Git 目前只归档到 v1.6，SHA-256 `364a2dc1c22ea9d3dce7ed209d77fdc36ed90fe759417acdedca10d19c9e59d1`。这是文件归档缺口，不表示 v1.6 重新生效。v1.11 未核准正式生效前不是现行治理。

## v1.2 S1

负责人于 2026-11-09 在交付主 Chat 批准三个精确原件。批准记录 `docs/implementation/bookkeeping-v1.2-s1-approval-record-v0.1.md`，SHA-256 `490c008a71867ba22c1c1558ff0a951ea06257275cab9f6ff88cd3215bd31016`。

| 事项 | 原件 | SHA-256 | 状态 |
|---|---|---|---|
| 产品/UI Diff v0.2 | `docs/implementation/history/bookkeeping-v1.2-s1-product-ui-diff-review-v0.2.md` | `ace80cebae68e3ee01682aa278de4b0008da17792cae24fab53455d139efd494` | APPROVED |
| Stage/Task Plan v0.2 | `docs/implementation/history/bookkeeping-v1.2-s1-stage-task-plan-review-v0.2.md` | `30db9c7f018297d978e91d05a5911a705c08c6474029598e0bbcf5aae80f02a7` | APPROVED |
| C11 Schema Review v0.2 | `docs/implementation/history/bookkeeping-v1.2-c11-counterparty-schema-review-v0.2.md` | `40de6084f14f7538f8d21a7cea7662367f7472cdcb62cc6b7ea30db418eb3c45` | APPROVED / 正式专项 |

四份 v0.1 REVIEW 原字节保留，不是本次批准文本。v1.1 S1 仍是 CLOSED。

Schema 专项批准覆盖隔离环境中的 V21 设计与合成验证。个人正式库升级、历史回填、生产备份或恢复不在本次批准内。

## 未授权

真实个人库升级、历史交易对方回填、生产备份或恢复、部署、Push、新远端分支、PR、合并 `main`。独立 Testing、Supervisor 和负责人集中 UAT 尚未执行，本索引不记 PASS。
