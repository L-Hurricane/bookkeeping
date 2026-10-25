# S3-T00｜DB-CT-01 分类与标签 Schema 草案

- 日期：2026-10-25
- 状态：**DRAFT / DB-CT-01 PENDING**。这是专项审批材料，不是已批准的迁移，也不是 Flyway 历史的一部分。
- 依据：已批准 S3 计划 `docs/implementation/bookkeeping-v1.0-stage-s3-plan-v0.1-approved.md`，SHA-256 `c871888bfa1ee09996e005adcdbe94afb568837699a425631c02a8dc396a79ef`。正式需求是 PRD CT-01～CT-10。
- 本批没有连接数据库，没有读取凭证，没有执行 DDL 或 DML，没有把草案放进 `backend/src/main/resources/db/migration/`。历史迁移文件 V1～V16 未改。

获批执行时，正式文件才是 `backend/src/main/resources/db/migration/V17__category_and_tag_tree.sql`。在 DB-CT-01 批准前不创建该文件。

## 1. 已核对的现有约束

最新迁移文件是 `V16__create_transfers.sql`。应用配置是 Hibernate `ddl-auto: validate`，Flyway `clean-disabled: true`，`baseline-on-migrate: false`。

`V4__create_categories.sql`：

- 列：`id`、`user_id`、`type`（`INCOME`/`EXPENSE`）、`name`、`parent_id`、生成列 `parent_scope_id`、`created_at_utc`。
- `uk_categories_scope_name (user_id, parent_scope_id, name)`：同一父节点下不重名，不区分收支类型。两个根节点父范围都是 0，因此不同收支类型的根节点也不能同名。
- `uk_categories_id_user_type (id, user_id, type)` 被交易外键使用。
- `fk_categories_parent` 只引用 `categories(id)`，不包含 `user_id`。
- 没有深度或防环约束。

`V5__create_tags.sql`：

- 列：`id`、`user_id`、`type`（`INCOME`/`EXPENSE`）、`name`、`created_at_utc`。没有 `parent_id`。
- `uk_tags_user_name (user_id, name)` 已经是同一用户全部标签名称唯一。现有库因此不可能同时存在两条同名标签，包括跨收支类型的同名。
- `idx_tags_user_type (user_id, type)`，`ck_tags_type`。

`V6__create_transactions.sql` 与 `V10__nullable_transaction_category.sql`：

- `transactions.category_id` 可空。
- 外键是 `(category_id, user_id, type) → categories(id, user_id, type)`，未写 `ON DELETE`，即为 `RESTRICT`。

`V7__create_transaction_tags.sql`：

- 主键 `(transaction_id, tag_id)`。
- 交易删除使用 `ON DELETE CASCADE` 清理关联。这是既有行为，本草案不改。
- 标签删除是 `ON DELETE RESTRICT`。本草案保持 `RESTRICT`，不改成 `CASCADE` 或 `SET NULL`。

`CategoryBootstrap` 与 `FixedCategories` 是当前分类的写入来源。分类 API 只有列表。标签 API 只有按收支类型的列表和创建。用户今天不能从产品入口改树。

固定种子里，支出分类「其他」出现两次：父节点「餐饮」下一处，父节点「交通」下一处。其余种子名称在同一收支类型内不重复。收入与支出种子之间没有同名。每个完成过分类初始化的用户都会有这对同名支出分类，除非库里的数据后来被手工改过。这一点来自源码，不依赖本批没有做的库内计数。

`StatisticsService` 的标签汇总按 `tags.type` 过滤。`TagEntity` 映射了 `type`。因此不能在现有程序仍在运行时，把下面的草案应用到它正在使用的库。

## 2. 草案要达到的结构

- 分类继续保留收支类型和 `parent_id`。同一用户、同一收支类型下，名称跨层级唯一。收入与支出允许同名。
- 不删除、不重插种子，不合并分类 ID。交易仍指向原来的 `category_id`。
- 标签增加可空 `parent_id`，已有标签都成为根节点。保留标签 ID 和 `transaction_tags`。删除 `type`，使标签不再按收支类型分开。
- 名称唯一仍用现有 `uk_tags_user_name`。不新增闭包表、触发器或级联删除。
- 三级深度、防环、跨类型移动、有直接交易时不能成为非叶子，都留在后续 T01/T02 的服务事务里。本草案不把这些规则写成数据库触发器。
- 分类表不改 `transactions`，不回填金额、余额、转账或分析快照。

## 3. 必须由 DB-CT-01 一并决定的数据步骤

支出种子的两个「其他」不能同时满足 `UNIQUE (user_id, type, name)`。不改显示名，就无法加上这条约束。

推荐方案，也是下面 SQL 中唯一的数据语句：

- 只把父节点名称为「交通」、自身名称为「其他」的支出分类改名为「其他交通」。
- 保留该行 ID。餐饮下的「其他」保持原名。
- 不合并两条记录，不改 `transactions.category_id`。
- 「其他交通」在固定种子中尚不存在。若目标库已经有同名支出分类，获批执行前的预检必须使迁移停住。

这是显示名称变化，不是账务变化。拒绝该改名时，不要批准本 SQL；此时不能添加正式唯一约束。备选是暂不添加该唯一索引，只在日后的应用层拒绝新的重名，并让两个「其他」继续存在。备选不满足 CT-03 对已有数据的唯一要求，因此不作为本草案的正式语句。

标签不需要合并 ID。现有唯一索引已经排除同名标签。删除 `type` 不改标签 ID，也不改交易关联。

## 4. 待审 SQL

下面的语句按顺序构成将来的 V17。MySQL 对 DDL 隐式提交，Flyway 不能在后一条失败时回滚前一条。

```sql
ALTER TABLE tags
    ADD COLUMN parent_id BIGINT NULL,
    ALGORITHM=INSTANT;

ALTER TABLE tags
    ADD INDEX idx_tags_parent (parent_id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE tags
    ADD CONSTRAINT fk_tags_parent
        FOREIGN KEY (parent_id) REFERENCES tags (id);

UPDATE categories AS child
    INNER JOIN categories AS parent
        ON parent.id = child.parent_id
       AND parent.user_id = child.user_id
       AND parent.type = 'EXPENSE'
       AND parent.name = '交通'
SET child.name = '其他交通'
WHERE child.type = 'EXPENSE'
  AND child.name = '其他';

ALTER TABLE categories
    DROP INDEX uk_categories_scope_name,
    ADD CONSTRAINT uk_categories_user_type_name UNIQUE (user_id, type, name);

ALTER TABLE tags DROP INDEX idx_tags_user_type;

ALTER TABLE tags DROP CHECK ck_tags_type;

ALTER TABLE tags
    DROP COLUMN type,
    ALGORITHM=INSTANT;
```

第一条只在表尾增加可空列，并写明 `ALGORITHM=INSTANT`。二级索引不能使用该算法，所以第二条单独写 `ALGORITHM=INPLACE, LOCK=NONE`。获批执行时若服务器拒绝写出的算法，停在该语句，不改用本草案没有写出的算法。已有标签的 `parent_id` 都是空，第三条外键不改任何标签归属。外键使用默认的 `RESTRICT`。

第四条只匹配父节点为支出「交通」的支出「其他」。匹配不到时更新零行，不创造新行。

第五条先去掉同父唯一，再加同类型全局唯一。分类的 `parent_id` 与生成列 `parent_scope_id` 保留，避免为删除生成列重建分类表。`uk_categories_id_user_type` 和交易外键不动。

后三条先去掉依赖 `type` 的索引和检查，再删除该列。`uk_tags_user_name` 保留。删除列若被服务器拒绝 `INSTANT`，停止，不自动改成复制整表。

## 5. 获批执行前必须先跑的只读预检

本批没有跑这些语句。将来只在被点名的目标库上执行，而且只读。任一结果非空，或结果与第 3 节的已知种子冲突不一致，就停止，不执行 V17。

```sql
SELECT user_id, type, name, COUNT(*) AS n
FROM categories
GROUP BY user_id, type, name
HAVING COUNT(*) > 1
   AND NOT (type = 'EXPENSE' AND name = '其他' AND COUNT(*) = 2);

SELECT child.id, child.user_id, parent.name AS parent_name
FROM categories AS child
JOIN categories AS parent ON parent.id = child.parent_id
WHERE child.type = 'EXPENSE' AND child.name = '其他';

SELECT id, user_id FROM categories
WHERE type = 'EXPENSE' AND name = '其他交通';

SELECT 'category' AS src, id, user_id, name FROM categories WHERE name <> TRIM(name)
UNION ALL
SELECT 'tag', id, user_id, name FROM tags WHERE name <> TRIM(name);

SELECT child.id
FROM categories AS child
JOIN categories AS parent ON parent.id = child.parent_id
WHERE child.user_id <> parent.user_id;

SELECT t.id
FROM transactions AS t
LEFT JOIN categories AS c ON c.id = t.category_id
WHERE t.category_id IS NOT NULL AND c.id IS NULL;

SELECT tt.transaction_id, tt.tag_id
FROM transaction_tags AS tt
LEFT JOIN tags AS g ON g.id = tt.tag_id
WHERE g.id IS NULL;
```

第一条允许且只允许「每个用户恰好两条支出『其他』」这一组重复。其他重复、未裁剪空白、跨用户父子、悬空分类或悬空标签都不在推荐改名的覆盖范围内。出现这些行时，本草案不自动合并、不自动裁剪。

## 6. 部分完成与恢复

主恢复路径是恢复该库在 V17 之前的备份，而不是重放已成功的语句，也不是 `Flyway.clean`。开发库 `bookkeeping` 不在本草案的演练范围内。

| 停在 | 已提交的事实 | 获批后的窄恢复 |
|---|---|---|
| 第一条失败且列不存在 | 无变化 | 不重试其他语句 |
| 第二条或第三条失败、列已在 | 标签多了空的 `parent_id`，索引或外键可能已在 | 先删外键（若已留下），再删 `idx_tags_parent` 和 `parent_id`。标签 ID 未变 |
| 第四条已执行、第五条失败 | 符合条件的「其他」已改名为「其他交通」，旧唯一索引仍在 | 用备份恢复。只有确认没有其他「其他交通」时，才把刚才那一行改回「其他」 |
| 第五条已成功、后面失败 | 分类唯一约束已换成同类型全局唯一；标签可能仍有 `type` | 不要重放前面的语句。先核对 `idx_tags_user_type`、`ck_tags_type` 和 `type` 是否还在，只补尚未成功的子句 |
| 最后一条成功 | 草案结构完成 | 现有程序仍读取 `tags.type` 时不能启动。代码未一起交付时，用备份恢复 |

`flyway_schema_history` 中的失败版本不能靠盲目重跑整个文件修复。失败记录的处理需要另一次明确授权。

## 7. 验证、备份与不适用的库

DB-CT-01 即使批准 SQL，也不自动批准对开发库或个人账本执行。后续演练仍须单独点名目标库。现有迁移守卫只承认 `bookkeeping_migration_test` 与 `bk_s1t03_6928fd20_migration`；本草案的后续演练只使用前者。不使用开发库 `bookkeeping`、普通测试库 `bookkeeping_test`，也不从其他库复制账本。

获批演练的顺序：

1. 核对 JDBC 库名恰好是 `bookkeeping_migration_test`，产品是 MySQL 8.4。
2. 只导出这个库。导出文件和校验值留在演练记录里。
3. 跑第 5 节的预检。非空则停止。
4. 只执行第 4 节的 SQL。
5. 核对：标签 `parent_id` 可空且现有行均为空；`type` 与 `ck_tags_type`、`idx_tags_user_type` 不在；`uk_tags_user_name` 仍在；分类 `uk_categories_user_type_name` 在，`uk_categories_scope_name` 不在；支出「交通」下的原「其他」只改了名称、ID 不变；交易行数、`category_id`、`transaction_tags`、账户余额没有被本迁移改写。
6. 同一迁移文件在当前程序下不能当作开发库升级。`CategoryBootstrap` 仍会按旧种子核对分类集合，改名后会试图补回旧的「其他」并因新唯一约束或数量核对失败。这个启动阻断由 T01 在获批后的代码里解除，不属于本批。

锁影响限于 `categories` 与 `tags`。草案不改 `transactions`。两张表相对流水表更小，但仍会取元数据锁。本批没有测量实际耗时。

## 8. 审批请求

请负责人决定是否批准：

1. 第 4 节 SQL 作为将来的 V17 原文。
2. 其中把支出「交通」下的「其他」改名为「其他交通」，并保留分类 ID。
3. 执行仍限于随后单独点名的 `bookkeeping_migration_test` 演练；本文件不批准开发库、个人账本或生产库。

未批准前：不创建 V17 文件，不执行迁移，不开始 T01～T05。
