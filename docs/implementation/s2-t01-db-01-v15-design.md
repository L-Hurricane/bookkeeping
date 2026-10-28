# S2-T01 DB-01 V15 设计（待审，未执行）

Status: Pending owner approval. This document is the DB-01 design. It is not a Flyway migration, and this batch did not execute it.

No DDL, data write, or schema clean was run while preparing this document. No database connection was opened.

## 待审 SQL

稍后单独批准执行时，正式迁移文件才是 `backend/src/main/resources/db/migration/V15__account_lifecycle.sql`。本批不创建该文件。文件内容只含下面两条语句：

```sql
ALTER TABLE accounts
    ADD COLUMN lifecycle VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ALGORITHM=INSTANT;

ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_lifecycle
        CHECK (lifecycle IN ('ACTIVE', 'ARCHIVED'));
```

本方案不增加复合索引，也不增加其他二级索引。不另写 `UPDATE` 去回填余额、名称、排序、流水或分析快照。`ADD COLUMN ... DEFAULT 'ACTIVE'` 本身让已有行得到 `ACTIVE`。

## 依据现有源码的兼容性

`V3__create_accounts.sql` 创建 `accounts`，当时没有生命周期列。列是 `id`、`user_id`、`name`、`currency`、`opening_balance`、`created_time`、`last_first_save_order`、`updated_at_utc`。

`V12__multi_account_kind_and_name.sql` 先追加 `kind VARCHAR(16) NOT NULL DEFAULT 'NORMAL'`，再加 `ck_accounts_kind`，再加 `uk_accounts_user_name (user_id, name)`，最后删除 `uk_accounts_user`。当前仓库最新迁移文件是 `V14__analysis_account_scope.sql`。

`AccountEntity` 只映射上述列加上 `kind`。没有 `lifecycle` 字段。应用配置是 `spring.jpa.hibernate.ddl-auto: validate`，Flyway `clean-disabled: true`，`baseline-on-migrate: false`。Hibernate validate 核对实体已映射的列；未映射的新增列不会让现有插入语句带上 `lifecycle`。省略该列的插入由 MySQL 默认值写成 `ACTIVE`。

`kind` 继续只表示 `NORMAL` / `CREDIT`。`lifecycle` 是另一列，只表示 `ACTIVE` / `ARCHIVED`。归档行仍留在 `accounts`，所以现有 `uk_accounts_user_name (user_id, name)` 已经覆盖归档账户的名称唯一。`uk_accounts_id_user` 和指向 `accounts(id, user_id)` 的外键保持不动。

本地环境文档要求 MySQL 8.4。这台机器记录的程序目录是 MySQL 8.4.3。第一条语句把列加在表尾并写明 `ALGORITHM=INSTANT`。本批没有连接服务器，因此没有测量服务器实际选择的算法或锁时间。第二条语句按待审文本不写 `ALGORITHM`，由服务器选择。MySQL 8.4 会执行 `CHECK`。

## 失败后的部分完成状态

两条语句是两次 DDL。MySQL 对每条 DDL 隐式提交。Flyway 不能在第二条失败后回滚第一条。

部分完成时的事实是：

- `lifecycle` 列已经存在，默认值为 `ACTIVE`。
- `ck_accounts_lifecycle` 还不存在。
- 如果由 Flyway 执行整个 V15 文件，`flyway_schema_history` 中该版本是失败记录。再次执行同一个文件会在第一条重复加列时失败。

后续若单独批准恢复，先查看该库的列和约束，只补上缺失的 `CHECK`。不要盲目重放第一条。补约束之后，Flyway 失败记录的处理也要另一次明确授权。另一条恢复路径是恢复迁移前备份，再整文件重试。

在任何行变成 `ARCHIVED` 之前，经授权的回退是先删除 `ck_accounts_lifecycle`，再删除 `lifecycle`。已经存在 `ARCHIVED` 行之后，本设计不删除该列。

不要用 `Flyway.clean` 处理部分完成。清空只属于下面的专用迁移测试库，而且本批不批准演练。

## 环境边界

本批只读了仓库里的环境说明和守卫代码，没有连接数据库，也没有读取凭证文件。

`docs/development/local-environment.md` 把三个库分开：

- `DB_URL`：开发库 `bookkeeping`。它可能含有真实或开发中的个人数据。本方案不连接、不升级。
- `TEST_DB_URL`：普通测试库 `bookkeeping_test`。不是迁移演练目标。
- `MIGRATION_TEST_DB_URL`：迁移测试库 `bookkeeping_migration_test`。现有迁移测试会清空它。

`MigrationSchemaGuard` 的白名单是精确库名 `bookkeeping_migration_test` 和 `bk_s1t03_6928fd20_migration`。它拒绝非 `jdbc:mysql://` 地址，也拒绝 `bookkeeping` 和 `bookkeeping_test`。通过这个守卫不等于可以选用第二个白名单库。

本 DB-01 后续演练只允许库名恰好是 `bookkeeping_migration_test`。不使用 `bk_s1t03_6928fd20_migration`，不使用 UAT 库 `bk_s1t03_6928fd20_test`，不使用开发库或普通测试库。身份未核实的库一律不连接。

## 后续演练方案

本文件不批准现在执行。单独批准演练后，顺序是：

1. 把 JDBC URL 的库名固定为 `bookkeeping_migration_test`。
2. 调用 `MigrationSchemaGuard.requireDisposableSchema`。连接后核对 catalog 仍是这个库名，并且产品是 MySQL 8.4。这与 `MigrationIntegrationTest` 的核对一致。
3. catalog 不是该库名时停止。不降级到其他库。
4. 只有演练批准明确包含清空时，才允许现有测试对这个库执行 clean 和 V1–V14。清空本身有破坏性，不被本文覆盖。
5. 记录 `accounts` 的列、检查约束和索引。
6. 只对这个库执行上面两条 SQL。
7. 核对列是表尾的 `VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'`，检查约束只接受 `ACTIVE` 和 `ARCHIVED`，索引列表没有新增 `lifecycle` 索引。省略该列的新行读出 `ACTIVE`；`ARCHIVED` 可以写入；其他值被拒绝。测试行只在这个库内创建，不从其他库复制。
8. 部分完成演练需要另一次批准：只执行第一条，确认约束不存在，再只执行第二条。不重放第一条。

## 备份、恢复、验证和十分钟 RTO

演练批准里的 DDL 之前，先对 `bookkeeping_migration_test` 做仅该库的 `mysqldump`。dump 前重复 catalog 核对。不导出其他库。

恢复目标只能是同一个库名。重建该库等同于清空，必须写在同一次演练批准里。恢复后核对 Flyway 版本和 `accounts` 行数与 dump 一致。若 dump 取在 DDL 之前，恢复后的 `accounts` 没有 `lifecycle` 列。

RTO 目标是：从这个小型专用库决定恢复开始，到上述核对通过，不超过十分钟。

本批没有演练，因此下面各项仍未测量：dump 耗时、恢复耗时、两条 `ALTER` 的锁时间，以及十分钟目标是否成立。生产库的数据量和备份设施没有测量，不套用这个十分钟目标。

## 授权边界

开发库审批、本文档审批，以及日后对 `bookkeeping_migration_test` 的演练审批，都不覆盖线上真实数据库、开发库 `bookkeeping` 或 UAT 库。那些升级需要单独授权。

应用代码、实体字段和 API 不在本批修改。写入 `ARCHIVED` 的业务行为留待后续已批准的任务。
