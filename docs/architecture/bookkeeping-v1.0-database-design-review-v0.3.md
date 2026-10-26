# 04丨记账软件｜v1.0 统一数据库物理设计 Review v0.3

- 状态：**Review / Pending Approval**
- 日期：2026-10-26
- 性质：对 v0.2 的定向修订。不是 Flyway 文件，不批准 DDL，不授权连接业务库。
- 前版：`docs/architecture/bookkeeping-v1.0-database-design-review-v0.2.md`
- 前版 SHA-256：`5b9bc9ad9c68ab3c4cdcd803478837183463dbd6c690460d8cc83b4b6bdcc52d`
- v0.1 SHA-256：`547560c078b055e265bbed694b203efde34c0056c5f6d1d860fd311b6a523bbb`
- 来源提交：`8012a9ef262d2504c1da46f1bea6ddd25af55ac9`
- 正文 SHA-256 不写入本文。交付回执给出定稿 SHA。

v0.1 与 v0.2 原文都不修改。v0.2 已经审过的 ER、记账事实表、索引、兼容顺序和恢复设计在本版保留。本版只收紧 `ACCOUNT` 作用域 CHECK、补上空账户绕过协调唯一键的验证，并改写 EXPLAIN 的通过标准。永久删除账户后的全账本历史报告仍是业务待决，删除 SQL 与外键动作不改。

## 0. 相对 v0.2 的修订

| 项 | v0.2 | v0.3 |
|---|---|---|
| `ACCOUNT` 作用域 CHECK | `period_kind = 'MONTH' AND account_id > 0` | 增加 `account_id IS NOT NULL`，并保留 `account_id > 0`。`analysis_snapshots` 与 `analysis_generations` 使用同一谓词 |
| 空账户的 `ACCOUNT` 行 | 测试写了预期 `3819`。`NULL > 0` 的结果是 UNKNOWN，CHECK 只拒绝 FALSE，所以该行仍可通过；生成列得到 NULL 后，唯一索引不会把它当成重复 | 两张表都必须返回 `3819`。不得留下 `report_scope='ACCOUNT'` 且 `account_id IS NULL` 的行，也不得留下 `ACCOUNT` 且 `scope_account_key IS NULL` 的生成行。同一语句再执行一次仍是 `3819` |
| EXPLAIN | 通过标准写成 `type` 不是 `ALL` 且 `key` 有值 | 结合当时的行数、目标索引是否可用、计划读取的行是否与该数据量相称来判断。小表全表扫描可以接受 |

下列事项相对 v0.1 的结论继续有效，本版不改写：

| 项 | v0.1 | 自 v0.2 起 |
|---|---|---|
| `scope_account_key` | 生成值含 `LEGACY -> -id`，引用本行自增主键 | 只建在 `analysis_generations`。`ACCOUNT` 取 `account_id`，`LEDGER` 取常量 `0`，`LEGACY` 取 NULL |
| 历史行保留 | 靠 `-id` 把每行变成不同非空键 | 靠 InnoDB 唯一索引对 NULL 不去重，多条 `LEGACY` 可以共存 |
| 新唯一键 | 快照表和生成表都加 | 只加在 `analysis_generations`。快照表不加唯一键，同一事实可以有多条历史版本 |
| 旧应用写入 | 只说明读路径仍能看到 `ACCOUNT` | 两列 `NOT NULL DEFAULT`，旧 INSERT 省略新列时得到 `ACCOUNT` + `MONTH`，并规定停写、回填和测试顺序 |
| 年报文本 | 四位年份写入 `analysis_month` | 保留该方案，并把列从 `CHAR(7)` 改为 `VARCHAR(7)`，加上长度、CHECK、解析规则和 EXPLAIN 验证 |
| 永久删除 | 未单列报告留存 | 列为业务语义待决。本草案不改冻结 PRD，不改删除 SQL，不改外键删除动作 |
| DDL | 步骤说明 | 按语句拆开的 V18 草案、预检、冲突检测、锁和恢复。v0.3 只改其中的作用域 CHECK 文本 |

第 2 节至第 4 节的记账表清单、流水索引和“统计缺口是查询而不是新表”的判断，仍以 v0.1 为准，这里不重复。

## 1. 仍成立的结构事实

这些事实来自 V11、V14 和 `JdbcAnalysisGenerationStore`，本版不改写它们。

- `analysis_snapshots.analysis_month` 与 `analysis_generations.analysis_month` 现在都是 `CHAR(7)`。
- 快照没有自然键唯一约束。查找是 `user_id + account_id + analysis_month + context_fingerprint`，再按 `id` 取最早或最晚一条。同一键可以有多行。
- 生成协调的唯一键是 `uk_analysis_generations_account_scope (user_id, account_id, analysis_month, context_fingerprint)`。
- 当前 INSERT 显式列出旧列，不包含尚不存在的范围列。生成插入列为 `user_id, account_id, analysis_month, context_fingerprint, state, fencing_token, permit_date, lease_expires_at_utc, quota_reserved, snapshot_id, created_at_utc, updated_at_utc`。快照插入列在这些身份列之后是四段正文和 provenance。
- `account_id` 为 NULL 的行是 V14 写明的归属不明历史行。现有按 `account_id = :accountId` 的查询不会读到它们。
- 账户外键仍是 `(account_id, user_id) -> accounts(id, user_id)`。`account_id` 为 NULL 时 MySQL 不检查该外键。删除动作仍是默认 RESTRICT。
- 日限额表、用户锁、租约和 fencing token 不在本次变更里。

`CHAR(7)` 会按定长补空格。把 `'2025'` 放进该列后，正则看到的是带尾部空格的 7 字符值。年报若继续用这个类型，`CHAR_LENGTH = 4` 的 CHECK 会拒绝合法年份。因此草案先把两张表的该列改为 `VARCHAR(7)`，再保存 `'YYYY'` 或 `'YYYY-MM'`。

## 2. 修订后的列与键

两张表都增加：

| 列 | 终态 | 写入含义 |
|---|---|---|
| `report_scope` | `VARCHAR(16) NOT NULL DEFAULT 'ACCOUNT'` | `ACCOUNT` 单账户月报；`LEGACY` 仅用于回填既有空账户行；`LEDGER` 全账本 |
| `period_kind` | `VARCHAR(16) NOT NULL DEFAULT 'MONTH'` | `MONTH` 或 `YEAR` |

只有 `analysis_generations` 增加：

| 列 | 终态 | 生成规则 |
|---|---|---|
| `scope_account_key` | `BIGINT NULL`，`STORED` 生成列 | `ACCOUNT` 时等于 `account_id`；`LEDGER` 时等于 `0`；`LEGACY` 时为 NULL |

`0` 是协调键里的常量，不是账户 id，也不读取任何自增主键。预检要求 `accounts.id`、两张分析表的 `account_id` 都没有 `0`。`ACCOUNT` 的 CHECK 同时要求 `account_id IS NOT NULL` 和 `account_id > 0`。后者避免 `0` 与全账本协调键重合。前者是因为 CHECK 只在表达式为 FALSE 时拒绝行：`account_id` 为 NULL 时，`account_id > 0` 的结果是 UNKNOWN，单靠这个比较不会拒绝该行。被放过的 `ACCOUNT` 行会把 `scope_account_key` 生成成 NULL，随后与 `LEGACY` 一样不被协调唯一键去重。

`LEGACY` 不生成替代数字。InnoDB 的唯一索引只在索引列全部非空且全部相等时视为重复；`scope_account_key` 为 NULL 的多行可以保留。隔离演练要用两次相同身份的 `LEGACY` 插入证明这一点。若第二次被拒绝，停止演练，不得改回 `-id`。这个 NULL 语义只允许出现在已经通过 CHECK 的 `LEGACY` 行上，不能作为 `ACCOUNT` 加空账户的入口。

快照表不建 `scope_account_key`，也不建新的唯一键。一次成功仍插入一条新快照；同一用户、范围、期间和 fingerprint 可以留下多个版本。生成表的一行仍通过 `snapshot_id` 指向其中一条。

协调唯一键只替换生成表上的旧唯一键：

`uk_analysis_generations_coordination (user_id, scope_account_key, period_kind, analysis_month, context_fingerprint)`

既有非唯一索引 `idx_analysis_generations_account_scope (user_id, account_id, analysis_month, context_fingerprint)` 保留。旧 SQL 按 `account_id` 查找，优化器不会把该条件自动改写成生成列。

快照新增一条非唯一索引，供全账本按版本取最新或最早一条：

`idx_analysis_snapshots_period (user_id, report_scope, period_kind, analysis_month, context_fingerprint, id)`

v0.1 已有的快照索引全部保留。本草案不删除它们，也不新增交易表索引。

### 2.1 CHECK

两张表使用同一组语义。范围与期间的组合收紧为当前已经存在的数据，以及冻结 P0 要写入的新数据：

- `ACCOUNT + MONTH`，且 `account_id IS NOT NULL AND account_id > 0`。这是旧应用的默认写入，也是已归属历史行。
- `LEDGER + MONTH` 或 `LEDGER + YEAR`，且 `account_id IS NULL`。这是全账本月报和年报。
- `LEGACY + MONTH`，且 `account_id IS NULL`。这只容纳回填出来的历史行。迁移完成后，应用插入不得再写 `LEGACY`。CHECK 无法区分“回填行”和“新插入的 LEGACY”，该限制放在后续实现，不放进本 DDL。

因此 `ACCOUNT + YEAR` 不在本草案的允许集合里。若负责人要保留这个组合，需要单独改 CHECK。`ACCOUNT` 加空 `account_id` 也不在允许集合里，而且必须由 CHECK 直接拒绝，不能留到生成列或唯一索引。

期间文本：

- `MONTH`：`CHAR_LENGTH(analysis_month) = 7`，且匹配 `^[0-9]{4}-(0[1-9]|1[0-2])$`。
- `YEAR`：`CHAR_LENGTH(analysis_month) = 4`，且匹配 `^[0-9]{4}$`。不截断、不补 `-01`、不接受尾部空格。

后续实现才能采用的解析规则，本阶段不改 Java：

- `period_kind = MONTH` 时才调用 `YearMonth.parse`，并且字符串长度必须已经是 7。
- `period_kind = YEAR` 时按四位数字读取，不调用 `YearMonth.parse`，也不 `trim`。
- 读到 NULL `account_id` 时使用 `ResultSet.getObject`，不把 NULL 收成 `0`。

## 3. 旧应用兼容与升级顺序

当前 Java 的 INSERT 省略新列。终态默认值让这种语句落到 `report_scope='ACCOUNT'`、`period_kind='MONTH'`。账户 id 由旧语句写入，生成列随之等于该账户 id。旧的按 `account_id` 查找、更新租约、完成生成和永久删除计数都不引用新列，可以继续执行。

旧语句不能产生 `LEDGER` 或 `YEAR`。它若显式插入 `account_id NULL`，默认范围是 `ACCOUNT`。`account_id IS NOT NULL` 为 FALSE，整条作用域 CHECK 为 FALSE，插入被拒绝。生成列不会先变成 NULL 再交给唯一索引。当前 `requireAccount` 已经不写这种行。

升级顺序：

1. 负责人批准本草案之后，才允许在点名的隔离库演练。演练前全库逻辑备份。
2. 只保留一个迁移连接。应用和其他写入会话断开，避免回填窗口里又插入空范围的行。
3. 先跑第 4 节预检。任一结果非空或非零，停止，不执行 ALTER。
4. 按第 5 节语句顺序执行。每条 DDL 都会隐式提交，脚本不提供中途重入。
5. 跑第 6 节的 EXPLAIN 和第 7 节的新旧 SQL 测试。通过后，才允许旧版本应用连上该隔离库做兼容写入。
6. 新的全账本读写属于后续业务实现，不在本次演练里发布。

回填在列仍可空时完成：`account_id IS NULL` 写 `LEGACY`，其余写 `ACCOUNT`，`period_kind` 全部写 `MONTH`。然后再改为非空并加上默认值。不能靠 `DEFAULT 'ACCOUNT'` 填历史空值，否则空账户行会被标成单账户报告。

## 4. 数据预检

以下语句只在获准的隔离库、执行 DDL 之前运行。日常库 `bookkeeping` 不在本任务的连接范围内。`account_id IS NULL` 的行数仍是待核对，预检结果出来之前不要在文档里补数字。

```sql
SELECT version, success
FROM flyway_schema_history
ORDER BY installed_rank;

SELECT COLUMN_NAME, TABLE_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('analysis_snapshots', 'analysis_generations')
  AND COLUMN_NAME IN ('report_scope', 'period_kind', 'scope_account_key');

SELECT 'snapshots' AS source_table, COUNT(*) AS rows_total,
       SUM(account_id IS NULL) AS null_account,
       SUM(account_id IS NOT NULL) AS with_account
FROM analysis_snapshots
UNION ALL
SELECT 'generations', COUNT(*),
       SUM(account_id IS NULL), SUM(account_id IS NOT NULL)
FROM analysis_generations;

SELECT id FROM analysis_snapshots
WHERE analysis_month NOT REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'
UNION ALL
SELECT id FROM analysis_generations
WHERE analysis_month NOT REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$';

SELECT COUNT(*) AS zero_account_id FROM accounts WHERE id = 0;
SELECT COUNT(*) AS zero_snapshot_account FROM analysis_snapshots WHERE account_id = 0;
SELECT COUNT(*) AS zero_generation_account FROM analysis_generations WHERE account_id = 0;

SELECT user_id, account_id, analysis_month, context_fingerprint, COUNT(*) AS duplicate_rows
FROM analysis_generations
WHERE account_id IS NOT NULL
GROUP BY user_id, account_id, analysis_month, context_fingerprint
HAVING COUNT(*) > 1;

SELECT g.id
FROM analysis_generations g
LEFT JOIN analysis_snapshots s ON s.id = g.snapshot_id
WHERE g.snapshot_id IS NOT NULL AND s.id IS NULL;
```

通过标准：Flyway 最新成功版本是 17；新列不存在；月份格式查询、`id = 0` 计数、重复协调键和悬空 `snapshot_id` 都为空或为零。行数查询只记录，不因行数多少而失败。

现有唯一键理论上已经挡住非空 `account_id` 的重复。预检仍要执行。发现重复时停止，本草案不包含合并或删除这些行的语句。

## 5. V18 DDL 草案

状态：未批准。不得放入 `backend/src/main/resources/db/migration/`，不得对任何库执行。语句按依赖拆开，是为了某一步失败时能对应到备份恢复，不是为了可以跳过中间步骤重跑。

```sql
ALTER TABLE analysis_snapshots
    ADD COLUMN report_scope VARCHAR(16) NULL AFTER account_id,
    ADD COLUMN period_kind VARCHAR(16) NULL AFTER report_scope;

ALTER TABLE analysis_generations
    ADD COLUMN report_scope VARCHAR(16) NULL AFTER account_id,
    ADD COLUMN period_kind VARCHAR(16) NULL AFTER report_scope;

UPDATE analysis_snapshots
SET report_scope = CASE WHEN account_id IS NULL THEN 'LEGACY' ELSE 'ACCOUNT' END,
    period_kind = 'MONTH'
WHERE report_scope IS NULL;

UPDATE analysis_generations
SET report_scope = CASE WHEN account_id IS NULL THEN 'LEGACY' ELSE 'ACCOUNT' END,
    period_kind = 'MONTH'
WHERE report_scope IS NULL;

ALTER TABLE analysis_snapshots
    MODIFY analysis_month VARCHAR(7) NOT NULL,
    ALGORITHM=COPY, LOCK=SHARED;

ALTER TABLE analysis_generations
    MODIFY analysis_month VARCHAR(7) NOT NULL,
    ALGORITHM=COPY, LOCK=SHARED;

ALTER TABLE analysis_snapshots
    MODIFY report_scope VARCHAR(16) NOT NULL DEFAULT 'ACCOUNT',
    MODIFY period_kind VARCHAR(16) NOT NULL DEFAULT 'MONTH',
    ADD CONSTRAINT chk_analysis_snapshots_scope CHECK (
        (report_scope = 'ACCOUNT' AND period_kind = 'MONTH'
            AND account_id IS NOT NULL AND account_id > 0)
        OR (report_scope = 'LEDGER' AND account_id IS NULL AND period_kind IN ('MONTH', 'YEAR'))
        OR (report_scope = 'LEGACY' AND period_kind = 'MONTH' AND account_id IS NULL)
    ),
    ADD CONSTRAINT chk_analysis_snapshots_period_text CHECK (
        (period_kind = 'MONTH' AND CHAR_LENGTH(analysis_month) = 7
            AND analysis_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$')
        OR (period_kind = 'YEAR' AND CHAR_LENGTH(analysis_month) = 4
            AND analysis_month REGEXP '^[0-9]{4}$')
    );

ALTER TABLE analysis_generations
    MODIFY report_scope VARCHAR(16) NOT NULL DEFAULT 'ACCOUNT',
    MODIFY period_kind VARCHAR(16) NOT NULL DEFAULT 'MONTH',
    ADD CONSTRAINT chk_analysis_generations_scope CHECK (
        (report_scope = 'ACCOUNT' AND period_kind = 'MONTH'
            AND account_id IS NOT NULL AND account_id > 0)
        OR (report_scope = 'LEDGER' AND account_id IS NULL AND period_kind IN ('MONTH', 'YEAR'))
        OR (report_scope = 'LEGACY' AND period_kind = 'MONTH' AND account_id IS NULL)
    ),
    ADD CONSTRAINT chk_analysis_generations_period_text CHECK (
        (period_kind = 'MONTH' AND CHAR_LENGTH(analysis_month) = 7
            AND analysis_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$')
        OR (period_kind = 'YEAR' AND CHAR_LENGTH(analysis_month) = 4
            AND analysis_month REGEXP '^[0-9]{4}$')
    );

ALTER TABLE analysis_generations
    ADD COLUMN scope_account_key BIGINT
        GENERATED ALWAYS AS (
            CASE report_scope
                WHEN 'ACCOUNT' THEN account_id
                WHEN 'LEDGER' THEN 0
                ELSE NULL
            END
        ) STORED,
    ALGORITHM=COPY, LOCK=SHARED;

ALTER TABLE analysis_generations
    ADD CONSTRAINT uk_analysis_generations_coordination
        UNIQUE (user_id, scope_account_key, period_kind, analysis_month, context_fingerprint);

ALTER TABLE analysis_generations
    DROP INDEX uk_analysis_generations_account_scope;

ALTER TABLE analysis_snapshots
    ADD INDEX idx_analysis_snapshots_period
        (user_id, report_scope, period_kind, analysis_month, context_fingerprint, id);
```

`DROP INDEX` 放在新唯一键之后。`fk_analysis_generations_user (user_id)` 需要一个以 `user_id` 开头的索引；新唯一键和保留的 `idx_analysis_generations_account_scope` 都满足。`fk_analysis_generations_account (account_id, user_id)` 依赖以 `account_id` 开头的索引，本草案不删除该外键自己的索引。

若服务器拒绝 `ALGORITHM=COPY, LOCK=SHARED`，演练停止并恢复。不得删掉这两个子句再跑，也不得把 `foreign_key_checks` 设为 0。

本仓库的 Flyway 迁移没有使用存储过程。草案因此不写 `DELIMITER` 或 `SIGNAL`。失败关闭靠两条结构性语句：违反期间 CHECK 的旧行会让 `ADD CONSTRAINT` 失败；非空账户键的重复行会让 `ADD CONSTRAINT UNIQUE` 失败。`ACCOUNT` 加空 `account_id` 由作用域 CHECK 在插入时拒绝，不靠唯一索引补救。

## 6. 外键、锁与索引验证

不改变的外键：

- `fk_analysis_snapshots_user`、`fk_analysis_generations_user`、`fk_analysis_generations_snapshot`
- `fk_analysis_snapshots_account`、`fk_analysis_generations_account`
- 删除规则仍是 RESTRICT。全账本和 `LEGACY` 的空 `account_id` 继续跳过账户外键检查。`ACCOUNT` 与空账户的组合由作用域 CHECK 拒绝；该谓词包含 `account_id IS NOT NULL`，空值比较得到 FALSE，而不是 UNKNOWN。

锁的预期，以隔离库实际输出为准：

| 语句 | 预期 |
|---|---|
| 增加可空列 | 只取元数据锁。若实际演变成复制表，记录耗时，不据此追加索引 |
| 回填 `UPDATE` | 扫描并锁定两张分析表中尚未填写的行。行数待核对，所以不预估时长 |
| `CHAR(7)` 改为 `VARCHAR(7)`，以及增加 `STORED` 生成列 | 按复制表准备，使用 `LOCK=SHARED` |
| 非空、CHECK、唯一索引、快照二级索引 | 构建或校验期间持有元数据锁，并可能锁整张分析表 |
| 记账交易、分类、标签、额度、用户锁 | 语句不接触这些表 |

`analysis_generations.snapshot_id` 引用快照主键。复制 `analysis_snapshots` 时，子表外键会一起被校验。演练窗口按两张分析表同时不可写来安排。

索引验证在同一隔离库、第 7 节测试数据回滚之后执行。先记下两张分析表当时的行数，再看下面四条 `EXPLAIN`，并记录 `type`、`possible_keys`、`key`、`rows`、`filtered`、`Extra`。通过与否同时看三件事：表有多大、目标索引是否存在且能被优化器选用、计划读取的行数是否和这个数据量相称。

- 目标索引必须已经存在。第一条是 `idx_analysis_snapshots_account_scope` 或 `idx_analysis_snapshots_account_month`，第二条是 `idx_analysis_snapshots_period`，第三条是 `idx_analysis_generations_account_scope`，第四条是 `uk_analysis_generations_coordination`。`possible_keys` 里没有对应索引时，视为索引不可用，停止并报告。
- 行数很少时，优化器选择全表扫描是可接受的计划。这时 `type = ALL` 或 `key` 为空，不单独构成失败。
- 行数已经使索引具有选择性，计划却不用上述可用索引，并且读取的行数远多于匹配行时，视为未通过。停止并报告，不在同一次演练里追加索引。
- 不把 `type`、`key` 或 `rows` 中的任何一个字段写成脱离数据量的机械阈值。

```sql
EXPLAIN SELECT id
FROM analysis_snapshots
WHERE user_id = 1 AND account_id = 1 AND analysis_month = '2026-10'
  AND context_fingerprint = 'x'
ORDER BY id DESC
LIMIT 1;

EXPLAIN SELECT id
FROM analysis_snapshots
WHERE user_id = 1 AND report_scope = 'LEDGER' AND period_kind = 'YEAR'
  AND analysis_month = '2025' AND context_fingerprint = 'x'
ORDER BY id DESC
LIMIT 1;

EXPLAIN SELECT id
FROM analysis_generations
WHERE user_id = 1 AND account_id = 1 AND analysis_month = '2026-10'
  AND context_fingerprint = 'x';

EXPLAIN SELECT id
FROM analysis_generations
WHERE user_id = 1 AND scope_account_key = 0 AND period_kind = 'YEAR'
  AND analysis_month = '2025' AND context_fingerprint = 'x';
```

常量 `1` 和 `'x'` 只用于查看计划，隔离库中可以换成演练行的实际值。

## 7. 隔离库 SQL 测试

DML 测试放在事务里，结束时 `ROLLBACK`。DDL 不能靠事务回滚。测试使用隔离库里已有的用户和账户，不新建业务事实。

旧 SQL，列清单与当前 `JdbcAnalysisGenerationStore` 一致：

1. 插入一条生成行，带真实 `account_id` 和 `'2026-10'`。读回 `report_scope='ACCOUNT'`、`period_kind='MONTH'`、`scope_account_key` 等于该 `account_id`。
2. 再插入同一 `user_id、account_id、analysis_month、context_fingerprint`。预期重复键错误 `1062`，然后回滚。
3. 用旧列清单插入两条相同身份的快照。两条都在，`id` 不同。这确认快照唯一键没有被加上。
4. 对 `analysis_generations` 和 `analysis_snapshots` 各做一次：用旧列清单把 `account_id` 写成 NULL。默认范围是 `ACCOUNT`，两处都预期 CHECK 错误 `3819`。同一语句再执行一次，仍是 `3819`，不是成功，也不是 `1062`。随后查询 `report_scope = 'ACCOUNT' AND account_id IS NULL` 必须为空。生成表上 `report_scope = 'ACCOUNT' AND scope_account_key IS NULL` 也必须为空。拒绝发生在 CHECK，不能先写成 NULL 协调键再被唯一索引放过。
5. 旧查找 `WHERE user_id AND account_id AND analysis_month AND context_fingerprint ORDER BY id` 仍能返回第 3 步的快照，并且不返回 `LEGACY` 或 `LEDGER`。

新 SQL：

1. 插入 `report_scope='LEDGER'`、`period_kind='YEAR'`、`analysis_month='2025'`、`account_id NULL` 的生成行。`scope_account_key` 为 `0`。同一键再插入一次，预期 `1062`。
2. 同一全账本年份插入两条快照，两条都保留。
3. `YEAR` 配 `'2025-01'`、`MONTH` 配 `'2025'`、`YEAR` 配 `'2025 '`、`LEDGER` 配非空账户、`ACCOUNT` 配 `YEAR`，全部预期 `3819`。
4. 对两张表显式插入 `report_scope='ACCOUNT'`、`period_kind='MONTH'`、`account_id NULL`、`analysis_month='2026-10'`。两处都预期 `3819`。再各插入一次，仍是 `3819`。插入失败后，两张表都不存在 `report_scope='ACCOUNT' AND account_id IS NULL` 的行；生成表不存在 `report_scope='ACCOUNT' AND scope_account_key IS NULL` 的行。
5. 显式插入两行相同身份的 `LEGACY + MONTH + account_id NULL`。两行都成功，`scope_account_key` 都是 NULL。随后删除这两行测试数据，或整事务回滚。不得用这个测试去清理真实历史行。
6. `SHOW CREATE TABLE analysis_generations` 中的生成表达式只出现 `report_scope`、`account_id` 和常量 `0`，不出现主键列 `id`。`SHOW CREATE TABLE` 中两张表的作用域 CHECK 都同时含有 `account_id IS NOT NULL` 和 `account_id > 0`。

通过后核对：`analysis_quota_days`、`analysis_user_locks` 的列与 DDL 前一致；Flyway 历史在正式迁移文件落地前不应被这次草案脚本改写。本草案还不是 Flyway 版本。

## 8. 恢复

1. 演练前用逻辑备份保存整个隔离库，包括 `flyway_schema_history`。
2. 任一条 DDL 或回填失败后，停止后续语句。不执行 `flyway repair`，不手写 `DROP COLUMN`、`UPDATE` 或反向 `DELETE` 来补救。
3. 用该备份恢复整个隔离库。部分 ALTER 已经隐式提交，所以不从失败语句往下续跑。
4. 恢复后重复第 4 节预检，确认结构回到 V17。
5. 日常库不使用这份恢复流程，因为它不参加演练。

## 9. 永久删除与历史 AI 报告

这是业务语义，本草案不替冻结 PRD 作决定。

PRD AC-07 要求永久删除归档账户及其关联的普通交易、余额调整和转账，删除后不能作为普通业务记录继续访问。PRD AI-07 要求已生成报告反映生成当时的输入，之后的交易变更不能悄悄改写报告。两处都没有写明：账户被永久删除后，一份已经保存、并且覆盖过该账户的全账本报告是否仍可阅读。

当前实现只删除 `account_id` 等于该账户的快照和生成行；空 `account_id` 的行不在这个删除条件里。全账本行按本草案也是空 `account_id`，因此现有删除 SQL 不会移除它们，账户外键也不会因为这些空账户行而阻止删除账户。这是现状，不是本评审新作出的保留决定。

本 DDL 不增加 `ON DELETE CASCADE`，不增加留存标志，不修改 `AccountPermanentDeletionAnalysisCounter` 的 SQL，也不修改 Product Baseline。负责人确定语义之前，后续实现不得自行选择“删除历史全账本报告”或“永久保留历史全账本报告”。

## 10. 仍须负责人审批

组长已经通过、本版不重新打开的事项：核心 ER；记账事实表原样复用；余额、净值和统计不建事实表；现有流水、转账、分类和标签索引不追加；分类父外键与 `parent_scope_id` 不动；信用额度、注销、手机号合并和交易后余额列延期。v0.2 已审的协调键形状、快照多版本、`VARCHAR(7)` 年报、旧 INSERT 默认值、升级顺序和整库恢复，除第 0 节所列 CHECK 与验证标准外，也不重新打开。

尚未通过、批准前不得执行的事项：

1. 是否批准第 5 节作为唯一的 v1.0 P0 Schema 增量。批准演练不等于批准在日常库执行，也不自动生成 Flyway 文件。
2. 是否同意 `LEGACY` 使用可空 `scope_account_key`，并用唯一索引的 NULL 语义保留多条历史行。空账户行数仍待核对。
3. 是否同意全账本协调键使用常量 `0`，并用 `account_id IS NOT NULL AND account_id > 0` 约束 `ACCOUNT` 行。
4. 是否同意唯一键只加在 `analysis_generations`，快照表维持多版本。
5. 是否同意年报把 `'YYYY'` 存入改为 `VARCHAR(7)` 的 `analysis_month`，并采用第 2.1 节的 CHECK 与解析规则。
6. 是否同意 CHECK 只允许 `ACCOUNT+MONTH`、`LEDGER+MONTH`、`LEDGER+YEAR` 和 `LEGACY+MONTH`。
7. 是否同意升级时先停写入，先回填 `LEGACY`，再依赖默认值兼容旧 INSERT。
8. 永久删除账户之后，已保存的全账本报告是否仍可阅读。此项未决。它不阻塞列设计的审阅，但在决定写回 PRD 或删除实现之前，相关行为保持现状。
