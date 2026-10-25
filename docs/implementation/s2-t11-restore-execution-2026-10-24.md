# S2-T11 恢复演练执行记录

状态：**UAT_READY**。恢复演练 PASS，开发库已迁移到 Flyway 16，最小应用启动通过。人工 UAT 未开始，S2 未结项，没有 Push，没有合并。

负责人已批准的权限操作只包括：用本机 `mysql-admin.cnf` 给 `bookkeeping_local@localhost` 临时授予 `bk_s1t03_6928fd20_migration` 的 SELECT、INSERT、CREATE、INDEX、ALTER、REFERENCES、LOCK TABLES，演练结束后撤销。没有其他 root 写入。

先前的阻断记录 `docs/implementation/s2-t11-restore-drill-2026-10-24.md` 保持原样。已批准计划未改，SHA-256 仍是 `69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5`。

## 0. 执行前只读核对

| 项 | 结果 |
|---|---|
| 目标库 | `127.0.0.1:3306/bk_s1t03_6928fd20_migration` 存在。表、视图、例程、触发器、事件都是 0。没有会话连着它 |
| 现行依赖 | 应用默认库和 `DB_URL` 都是 `bookkeeping`。`MIGRATION_TEST_DB_URL` 没有把测试指到该库。另有进程连着 `bk_s1t03_6928fd20_test`，Flyway 关闭，不是本库 |
| 原始权限 | `bookkeeping_local`：全局 USAGE，以及对 `bookkeeping`.* 的 ALL。没有目标库权限。`bookkeeping_test` 只有 `bookkeeping_test` 和 `bookkeeping_migration_test` |
| 原备份 | `bookkeeping-s2-ab-lite/evidence/s2-t11-dev-backup/bookkeeping-pre-v12.sql`，SHA-256 `c2201ba943b7b5c9409f9bc96b2e1ec276a077936e0ea9956389a84f45ea005f`，47206 字节，未改 |
| 其他库表数 | `bk_s1t03_6928fd20_test` 14，`bookkeeping` 14，`bookkeeping_migration_test` 15，`bookkeeping_test` 14 |

恢复副本只从原文件去掉 14 条 `DROP TABLE IF EXISTS`，原文件未改。副本路径 `bookkeeping-pre-v12-nodrop.sql`，SHA-256 `01d6356f2c6770c101c388128685d0e321355a02e8cf9781be14c7227419677d`，46658 字节，权限 0600。副本含 14 条 `CREATE TABLE`、14 条 `LOCK TABLES`、14 条 `INSERT`，以及 28 条只做 `DISABLE KEYS` / `ENABLE KEYS` 的 `ALTER TABLE`。没有 `DROP`、`TRUNCATE`、`DELETE`、`GRANT`、`REVOKE`、`CREATE DATABASE`、`USE`、`DEFINER`、`INTO OUTFILE` 或跨库限定名。

开发库迁移前指纹：Flyway 1–11 全部成功，校验和依次为 `-552215395`、`-1518294820`、`-1368050961`、`290019739`、`822686895`、`2115863220`、`340944797`、`-12954886`、`-1684672650`、`1121301242`、`-638070238`。用户 9、账户 8、交易 28、调整 3、分类 162、会话 46、分析快照 2、生成 3。用户名和账户 HEX、期初余额与备份前一致。没有 `transfers`，账户没有 `kind` 或 `lifecycle`。

V12–V16 文件 SHA-256 与已批准字节一致。

## 1. GRANT

对象：`bookkeeping_local@localhost`。范围：仅 `bk_s1t03_6928fd20_migration`.*。

执行后 `SHOW GRANTS` 新增一行，权限集合正好是 SELECT、INSERT、CREATE、INDEX、ALTER、REFERENCES、LOCK TABLES。`bookkeeping` 上的 ALL 和 `bookkeeping_test` 的授权没有变化。没有异常。

## 2. 恢复

对象：只把上述副本导入 `bk_s1t03_6928fd20_migration`。执行账号是 `bookkeeping_local`。没有执行原备份里的 `DROP TABLE`。

结果：退出码 0。耗时 0.151 秒，低于 10 分钟。没有错误输出。

恢复后该库 14 张表：`accounts`、`analysis_generations`、`analysis_quota_days`、`analysis_snapshots`、`analysis_user_locks`、`balance_adjustments`、`categories`、`flyway_schema_history`、`idempotency_records`、`sessions`、`tags`、`transaction_tags`、`transactions`、`users`。Flyway 1–11 校验和与开发库迁移前一致，全部成功。用户 9、账户 8、交易 28、调整 3、分类 162、会话 46、分析快照 2、生成 3、标签 4、交易标签 6、幂等记录 33、配额日 2、分析锁 2、Flyway 历史 11。用户名 HEX 与账户名 `E5BEAEE4BFA1`、币种 CNY、期初余额顺序 `50.00, 0.00, 50.00, -100.00, 1000.00, 0.00, 0.00, 100.00` 与开发库一致。账户没有 `kind`、`lifecycle`。

同一时刻开发库的表数、Flyway 1–11、记录数和指纹没有变化。

## 3. REVOKE

无论恢复结果如何都要撤销。恢复已经 PASS，随即撤销同一组权限。

撤销后 `bookkeeping_local` 恢复为全局 USAGE 和对 `bookkeeping`.* 的 ALL。`information_schema` 里不再有该账号对演练库的权限。再用该账号连接演练库得到 `ERROR 1044 (42000)`。`bookkeeping_test` 的授权未变。

演练库保留刚恢复的 14 张表，没有清理。其他库表数仍是：演练库 14、`bk_s1t03_6928fd20_test` 14、`bookkeeping` 14、`bookkeeping_migration_test` 15、`bookkeeping_test` 14。

## 4. 开发库迁移与启动

恢复 PASS，且开发库前置条件仍成立，才启动本机后端。配置是 profile `local`，`bookkeeping_local`，库 `bookkeeping`。端口用 `18080`，避免和另一进程声明的 `8080` 重叠。没有打开 bootstrap 用户、验收数据重置或真实模型调用。

Flyway 从版本 11 依次应用到 12、13、14、15、16，日志为成功应用 5 个迁移，当前版本 v16，耗时 0.228 秒。Hibernate 初始化了 `bookkeeping` 的 EntityManagerFactory，`ddl-auto` 为 validate，应用没有因结构校验失败退出。`Started BookkeepingApplication`，`/actuator/health` 返回 HTTP 200 `{"status":"UP"}`。核对后进程已停止，`18080` 不再监听。

迁移后开发库 15 张表，新增空的 `transfers`（0 行）。Flyway 1–11 校验和未变。12–16 全部成功，校验和为 `158404451`、`-2091583423`、`-2024210779`、`1211737661`、`-387499547`。原记录数和用户名、账户指纹未变。8 个账户都是 `NORMAL` / `ACTIVE`。

其他库表数变为：演练库 14、`bk_s1t03_6928fd20_test` 14、`bookkeeping` 15、`bookkeeping_migration_test` 15、`bookkeeping_test` 14。变化只在 `bookkeeping` 增加 `transfers`。授权仍是撤销后的状态。

## 结论

恢复演练 **PASS**。开发库迁移和最小启动 **PASS**。最终状态 **UAT_READY**。

人工验收由项目负责人按更新后的指南进行。本记录不代表 UAT PASS，也不代表 S2 CLOSED。
