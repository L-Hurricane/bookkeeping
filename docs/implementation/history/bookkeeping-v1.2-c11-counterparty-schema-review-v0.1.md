# 04丨记账软件｜v1.2 C11 交易对方 Schema Review v0.1

- 日期：2026-11-09
- 状态：**REVIEW / NOT APPROVED。本文件不是 Flyway 授权，不是个人库升级授权，不是历史回填授权。**
- 只读核对 HEAD：`v1.1/s1-m01` `50178488877ef5c7601f2ad1f8b8f27b97df2dde`。
- 上位候选：`docs/implementation/history/bookkeeping-v1.2-s1-stage-task-plan-review-v0.1.md` 与产品/UI Review v0.1。两份都还不是已批准计划。
- 复用：已提交收尾 Task Plan v0.2 第 3–5 节，SHA-256 `c7a42ff930a8de083abad36afdbff3c565d14bccb16814f8138c6c855e881b04`。原字节不改。
- 已有迁移止于 `V20__account_groups.sql`。`V19__transfer_name.sql` 已占用转账名称。本候选不改 V1–V20。

## 1. 已核对事实

`transactions` 没有与「交易对方」同义的列。`V6__create_transactions.sql` 的可空文本是 `name` 与 `remark`。`V10__nullable_transaction_category.sql` 把 `category_id` 改为可空。当前创建哈希在 `TransactionRequestHash`，字段依次是账户、类型、金额、分类、标签、业务时间、名称、备注，没有版本前缀。

`transfers.name` 是转账名称，由 `V19__transfer_name.sql` 以 `VARCHAR(255) NULL` 且 `ALGORITHM=INSTANT` 增加。测试里的 counterparty 指转账另一侧账户，不是支出文本。

因此 C11 需要新列。下面的 DDL 只作为批准文本。本轮不创建该文件，不执行它。

## 2. 拟批准的正式 DDL

下一迁移文件只能是 `V21__transaction_counterparty_name.sql`：

```sql
ALTER TABLE transactions
    ADD COLUMN counterparty_name VARCHAR(255) NULL,
    ALGORITHM=INSTANT;
```

- 只加这一可空列。不加索引，不加 CHECK。CHECK 可能使这条 ALTER 离开 INSTANT，个人库锁风险不放进本候选。
- 这条 DDL 没有 UPDATE。旧行迁移后仍是 NULL。
- `foreign_key_checks` 保持开启。禁止 `Flyway.clean()`。
- 若实测不是 INSTANT，STOP。不在失败后改写已执行环境里的 V21 充作热修，也不自行换成 COPY。

## 3. 应用与 API

批准实施后，应用层才可以改代码：

- trim。空串和纯空白存 NULL。最长 255 个字符，超出拒绝。
- 仅普通支出可新建、修改、用空值清空。
- 收入上的非空值返回 400，不写入，不静默丢弃。转账和余额调整不接收该字段。
- 详情、创建和编辑响应带上该字段。流水查询与名称搜索不选它。余额、分类、标签、统计 SQL 不读它。
- Jackson 省略 null 时，客户端把缺字段当作空。空不是交易名称，也不回填备注。

## 4. 幂等兼容

不修改历史 `idempotency_records.request_hash`。

已存创建哈希仍是现在的无前缀八字段哈希。新的创建请求使用前缀 `transaction-create-v2`，随后是原来的八字段，最后是规范化对方文本。NULL 与空白都写成空串。这与转账 `transfer-create-v2` 的前缀方式相同，前缀不同，不能复用转账哈希。

| 请求 | 结果 |
|---|---|
| 已存旧 Key，对方为空，其余字段与旧哈希一致 | 重放 |
| 已存旧 Key，对方非空 | 409 |
| 新 Key，相同规范化内容 | 重放 |
| 新 Key 已用过，对方或任一已哈希字段变化 | 409 |
| 不同 Key | 按普通创建规则各写一条 |
| 创建后的编辑或清空 | 普通 PUT。不改原创建哈希。用原创建请求重放只返回当前记录，不把对方写回旧值 |
| 创建的资源已删除后，再用旧 Key | 不创建第二条 |

编辑不是创建。收入拒绝发生在哈希复用之前，不把拒绝写成一次成功的幂等结果。

## 5. 合成迁移测试

实施批准之后，合成测试只使用 `bookkeeping_test`：

- 空库按 V1–V21 迁完，`counterparty_name` 可空，旧行没有被 UPDATE。
- 支出写入、再次编辑、用空值清空。
- 收入携带非空对方被拒绝，且没有新行。
- 旧行只编辑其他字段时，该列仍是 NULL。
- 上表的重放与 409。
- 搜索和月汇总不因该列变化。
- 其他用户读不到这条对方文本。

不连接个人库。不跑会清空 `bookkeeping_migration_test` 的 `MigrationIntegrationTest`，除非另有批准。合成通过不是个人库已升级。

## 6. 人工生产升级

个人库上的 Flyway 不在本批准之内。以后若负责人专项批准「对个人库执行 V21」，由负责人在本机按下面的顺序做：

1. 从本机私有配置确认库名。库名、口令和 JDBC URL 不写入仓库，不写入回执。
2. 用现有 Bootstrap 的 `mysqldump` 做一次一致性逻辑备份：`--single-transaction --routines --events --triggers`，包含 `flyway_schema_history`。文件放在仓库外。记录时间、字节数和 SHA-256。记录本身不进 Git。
3. 备份完成并核对库名之后，才由负责人启动包含已批准 V21 的应用迁移。
4. 迁移后只做只读核对：Flyway 版本、列可空、抽样旧行仍为 NULL。不在同一次操作里回填历史对方。

本轮没有备份，没有连接个人库。

## 7. 完整数据库回滚

已批准的回滚是恢复第 6 节的整库备份，不是 `DROP COLUMN`。

1. 演练或正式迁移失败时立即停止。不改写 V21，不禁用外键检查，不执行 `Flyway.clean()`。
2. INSTANT 加列若在提交前失败，表结构应保持加列前。仍以备份为恢复手段。
3. 需要证明备份可恢复时，另一次批准才能把该备份恢复进已经存在的 `bookkeeping_migration_test`。这会把个人账务复制进迁移库。不得恢复进 `bookkeeping` 或 `bookkeeping_test`，不得为此新建数据库。
4. 恢复前再次确认目标库名。恢复后核对 Flyway 历史回到升级前版本，再决定是否重试。

## 8. 历史回填专项

历史回填不写进 V21，也不随 Schema 批准或 Stage 批准生效。

待另案授权的顺序仍是：不清空正式交易，不重新导入；账户＋描述＋业务时间优先；歧义时加分类、金额和类型；只更新一对一的支出；先给负责人看匹配报告。报告不是写入。执行人是负责人本人，执行前必须已经有批准的 V21 和完成的个人库备份。

在那份专项下来之前：不读取正式 Excel，不连接个人库，不生成生产匹配报告，不执行 UPDATE。

## 9. 本次请求批准的边界

请求负责人只批准第 2–5 节的结构设计，以及「批准后可以由唯一 Coding Agent 在 v1.2 S1 本地分支实施」。该实施仍依赖 Stage Plan Review 被批准。

不包含：创建或执行 V21 文件的本轮动作、个人库备份、个人库 Flyway、整库恢复演练、历史回填、Push、PR、合并 `main`、部署。

## Change Log

- v0.1（2026-11-09）：把收尾计划 v0.2 里的 V21 候选写成独立 Schema Review。补上当前哈希字段、`transaction-create-v2` 兼容矩阵、合成测试、人工生产升级和整库回滚。历史回填保持在批准边界之外。
