# S1-T04｜普通交易跨账户与余额调整时间说明

- 日期：2026-10-22
- Task：S1-T04
- 前置：S1-T03 PASS（恢复点 `s1-t03-pass`）；分支 `task/s1-t04`
- 依据：已批准 v1.0 PRD（TX-04/TX-06/BAL-05）、T02 架构、S1 Task Plan

## 1. 范围

本 Task 只做后端：

1. 新建普通交易继续通过路径账户选择归属（`POST /api/v1/accounts/{accountId}/transactions`）。
2. 编辑可把交易从账户 A 改归属到 B：`PUT` 请求体可选 `accountId`（缺省=路径账户）。
3. 跨账户一次本地事务完成；失败不留部分效果。
4. 保留 Transaction ID、类型、首次保存时间、首次保存序、标签关联。
5. 余额仍为派生值；余额调整绑定路径账户；允许业务时间早于账户创建时间（去掉 v0.1 `BEFORE_ACCOUNT_CREATION`）。

不做：T05 小程序 UI、S2 转账、账户归档/删除、S5 AI 分析重构。不改已批准 PRD/UI/架构/Task Plan。

## 2. 跨账户编辑语义

- 路径 `{accountId}` = 当前所属账户；体 `accountId` = 目标账户（可省略）。
- 服务端按账户 id **升序** 加悲观锁，避免 A↔B 死锁。
- 原/目标账户均须属于当前用户；否则 `404 RESOURCE_NOT_FOUND`（不泄露跨用户存在性）。
- `reassignAccount` 只改 `account_id`；不触碰 `type` / `first_saved_at_utc` / `first_save_order`；不递增目标账户的 `last_first_save_order`。
- 标签集合与字段更新同一事务提交。

## 3. 稳定平局排序与 V13

`first_save_order` 仍是账户内递增语义（创建时由 `accounts.last_first_save_order` 在账户写锁下分配）。跨账户搬迁**不**重分配序、**不**递增目标账户计数器；因此目标账户可能出现相同 `first_save_order`。

v0.1 的 `uk_transactions_account_order UNIQUE (account_id, first_save_order)` 会阻断该迁移。T02/TX-04 要求保留历史序并接受撞序，故 **V13** 仅 `DROP INDEX uk_transactions_account_order`。`idx_transactions_ledger` 与 `uk_adjustments_account_order` 不变（余额调整不跨账户）。新建交易仍靠账户写锁 + 单调计数避免同账户新建撞序。

主排序不变：`business_time DESC, first_save_order DESC`

平局兜底（不改写历史序）：

| 场景 | 附加键 |
|---|---|
| 交易列表 / 名称搜索 | `first_saved_at_utc DESC, id DESC` |
| Ledger（交易∪调整） | `first_saved_at_utc DESC, kind DESC, id DESC` |
| 关键支出并列 | 同上交易附加键 |

实现集中在 `LedgerOrdering`。调用方用显式 `" ORDER BY " + …` / `" … LIMIT n"` 拼接，避免 text block 边界吞掉空格。

## 4. 余额调整业务时间

`AdjustmentService` 删除“不得早于账户创建时间”校验。差额仍按提交时当前账面余额计算并固定保存。调整继续走 `POST /accounts/{accountId}/balance-adjustments`，归属即路径账户。

## 5. AI 月度分析指纹（跨任务风险，未改代码）

与 T03 相同：`AnalysisContextFingerprint` 不含 `accountId`；协调唯一键为 `(user_id, analysis_month, context_fingerprint)`。多账户下同月账务若指纹碰撞，可能误复用 Snapshot。修复超出 T04；本 Task **仅记录证据，不扩范围**。

## 6. 测试意图

- `CrossAccountTransactionIntegrationTest`：选账户创建与幂等、A→B 身份/标签/余额、跨用户越权、失败回滚、撞序后列表与 Ledger 稳定序
- 既有 `TransactionIntegrationTest` / `AdjustmentIntegrationTest`：早于创建时间改为允许；失败回滚用例去掉对该校验的依赖
- `LedgerOrderingTest`、`TransactionEntityTest`：无 DB 单测（含 SQL 拼接空格）
- `MigrationIntegrationTest`：V13 后无 `uk_transactions_account_order`，同账户可存两笔相同历史序；ledger 索引仍在

数据库集成回归由外层隔离测试命令执行；本机 Coding Agent 默认只跑编译与无凭据单测。
