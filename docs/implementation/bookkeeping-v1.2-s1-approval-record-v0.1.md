# 04丨记账软件｜v1.2 S1 三项正式批准记录 v0.1

- 记录日期：2026-11-09
- 记录状态：**APPROVED**
- 审批人：项目负责人
- 记录责任人：交付主 Chat（组长）
- 批准事实：负责人于 2026-11-09 在交付主 Chat 明确批准下面三个精确原件。
- 本记录只绑定批准事实。三份 Review 与四份 v0.1 原字节不改。v1.1 S1 仍是 CLOSED。

## 批准对象

| 事项 | 原件 | SHA-256 | 决定 |
|---|---|---|---|
| 产品/UI Diff v0.2 | `docs/implementation/history/bookkeeping-v1.2-s1-product-ui-diff-review-v0.2.md` | `ace80cebae68e3ee01682aa278de4b0008da17792cae24fab53455d139efd494` | **APPROVED** |
| 完整 Stage/Task Plan v0.2 | `docs/implementation/history/bookkeeping-v1.2-s1-stage-task-plan-review-v0.2.md` | `30db9c7f018297d978e91d05a5911a705c08c6474029598e0bbcf5aae80f02a7` | **APPROVED** |
| C11 Schema Review v0.2 | `docs/implementation/history/bookkeeping-v1.2-c11-counterparty-schema-review-v0.2.md` | `40de6084f14f7538f8d21a7cea7662367f7472cdcb62cc6b7ea30db418eb3c45` | **APPROVED / 正式专项** |

索引更新方案 v0.2 的 SHA-256 是 `9bef2cad11633c1f556bc106277d61956e2c9c7fb01d253a241ea7c3bf05c81a`。它不是这三项批准对象。

## 适用范围

- 产品范围是 C01–C08 与 C11。C09/C10、分类和标签同级持久排序、投资记账不在本 Stage。
- 一个 Coding Agent 按 Stage Plan v0.2 的切片顺序串行实现。C01 只在集成候选上复验。C07 保持现有跨父级移动，不重复修复。
- 本地分支从已经包含本批准记录和三份已批准文档、且以 `50178488877ef5c7601f2ad1f8b8f27b97df2dde` 为祖先的提交建立。
- C11 的 `V21__transaction_counterparty_name.sql` 只可在已授权隔离环境用合成数据创建并验证。字段是可空 `counterparty_name VARCHAR(255)`，无索引，无历史回填。创建幂等使用 `transaction-create-v2` 与 Review v0.2 第 4 节的旧/新哈希选择路径。
- 独立 Testing、Supervisor 和负责人集中 UAT 仍按计划，只针对冻结后的一个候选 SHA。本记录不是这些 Gate 的 PASS。

## 排除权限

以下事项不因本批准生效：

- 真实个人库升级、个人库 Flyway、生产备份或恢复。Schema Review v0.2 第 7 节仍是将来的恢复方案文本，不是本轮执行授权。
- 历史交易对方回填，包括读取正式 Excel、生成生产匹配报告和 UPDATE。
- 把个人真实备份恢复到开发库或测试库。Cursor 不接触个人数据、凭据或备份。
- 部署、Push、新建远端分支或 PR、合并 `main`。
- 重新打开 v1.1 S1，或把 v1.11 标成现行治理。现行正式治理仍是 Project v1.10，SHA-256 `481a755a00989ee18f9cf140623a86f079d4152cd8714e628ed3d9734a383347`。Git 只归档到 v1.6 是文件缺口，不是 v1.6 重新生效。

## Change Log

- v0.1（2026-11-09）：记录负责人对三个精确 SHA 的批准，并写明隔离环境中的 V21 与个人正式库执行分开。
