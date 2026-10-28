# S2-T11 恢复演练授权记录

状态：**BLOCKED**。没有恢复，没有迁移，没有启动应用。人工 UAT 仍未开始，S2 未结项。

负责人于 2026-10-24 批准把 `bookkeeping-pre-v12.sql` 恢复到空库 `bk_s1t03_6928fd20_migration`，成功后再对 `bookkeeping` 执行已批准的 V12–V16。本记录不修改正式计划，计划 SHA-256 仍是 `69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5`。

基线 HEAD：`21d539fa76a849efb35b9d4eb3246d389392b9b5`，分支 `uat/v1.0-s2`。

## 只读核验

| 项 | 结果 |
|---|---|
| 原备份 SHA-256 | `c2201ba943b7b5c9409f9bc96b2e1ec276a077936e0ea9956389a84f45ea005f`，与批准值一致，文件未改 |
| 开发库 | `bookkeeping_local@localhost`，`127.0.0.1:3306/bookkeeping`，MySQL 8.4.3。Flyway 仍是 1–11 且校验和未变。用户 9、账户 8、交易 28、调整 3、分类 162、会话 46、分析快照 2、生成 3。用户名和账户指纹与备份前一致 |
| 应用配置 | `DB_URL` 仍是 `jdbc:mysql://localhost:3306/bookkeeping`。`MIGRATION_TEST_DB_URL` 未设置 |
| 转储语句 | 14 条 `DROP TABLE`。没有 `DROP DATABASE`、`CREATE DATABASE`、`USE`、`DEFINER`、`GRANT`、`INTO OUTFILE` 或跨库限定名。这些 `DROP TABLE` 没有执行 |
| 恢复库权限 | `bookkeeping_local` 和 `bookkeeping_test` 连接 `bk_s1t03_6928fd20_migration` 都是 `ERROR 1044`。`bookkeeping_local` 的 `SHOW DATABASES` 看不到该库。没有使用 root，没有提权，没有改口令 |

历史记录写过账号 `bk_t03_6928fd20` 拥有该库权限。本机现有 `bootstrap.env`、`mysql.cnf` 和验收环境文件里没有这个账号的凭证。不能把它当成当前可执行账号。

因此本次不能复核该库仍然为空，也不能确认没有会话正连着它。代码里 `MigrationSchemaGuard` 仍把这个库名放在可清空白名单中；`TestSchemaGuard` 拒绝它。当前没有环境变量把测试指过去。这只说明没有现行配置依赖，不能代替本次的空库核验。

## 未执行

- 没有生成安全副本，因为恢复在连接前停止。原备份保持原 SHA。
- 没有对 `bk_s1t03_6928fd20_migration` 写入。
- 没有对 `bookkeeping` 执行 V12–V16。开发库仍是 Flyway 11。
- 没有启动后端，没有 Hibernate validate。
- 没有 `DROP DATABASE`、`DROP TABLE`、`Flyway.clean`、覆盖原库或清理演练库。
- 没有 Push，没有合并。

## 结论

恢复演练 **BLOCKED**。开发库迁移 **BLOCKED**。最终状态 **BLOCKED**，不是 `UAT_READY`。

开发库里的验收样例没有被这次操作改动。启动本机后端仍会让 Flyway 自动迁移，在恢复演练完成前不要启动 UAT。
