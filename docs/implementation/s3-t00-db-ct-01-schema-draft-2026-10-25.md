# S3-T00｜DB-CT-01 分类与标签 Schema 草案

- 日期：2026-10-25
- 状态：**DRAFT / DB-CT-01 PENDING**。这是专项审批材料，不是已批准的迁移，也不是 Flyway 历史的一部分。
- 修订：第二次组长 Review 后的同一草案。上一版 SHA-256 `f4603fd66beea262318461dbad04152a2931ae7fa600cf3a168002c6ba94348f`。再上一版 SHA-256 `e75b2217fac9e93657c2a6ae90d7f04f2632b5b79062378795fc47b16d2afe32`。总体方案保留。本修订不表示负责人已经批准 SQL，也不表示已经批准「其他交通」这个显示名。
- 依据：已批准 S3 计划 `docs/implementation/bookkeeping-v1.0-stage-s3-plan-v0.1-approved.md`，SHA-256 `c871888bfa1ee09996e005adcdbe94afb568837699a425631c02a8dc396a79ef`。正式需求是 PRD CT-01～CT-10。
- 本批没有连接数据库，没有读取凭证，没有执行 DDL 或 DML，没有把草案放进 `backend/src/main/resources/db/migration/`。历史迁移文件 V1～V16 未改。

获批执行时，正式文件才是 `backend/src/main/resources/db/migration/V17__category_and_tag_tree.sql`。在 DB-CT-01 批准前不创建该文件。

## 1. 已核对的现有约束

最新迁移文件是 `V16__create_transfers.sql`。应用配置是 Hibernate `ddl-auto: validate`，Flyway `clean-disabled: true`，`baseline-on-migrate: false`。目标服务器是 MySQL 8.4。

`V4__create_categories.sql`：

- 列：`id`、`user_id`、`type`（`INCOME`/`EXPENSE`）、`name`、`parent_id`、生成列 `parent_scope_id`、`created_at_utc`。
- `uk_categories_scope_name (user_id, parent_scope_id, name)`：同一父节点下不重名，不区分收支类型。两个根节点父范围都是 0，因此不同收支类型的根节点也不能同名。
- `uk_categories_id_user_type (id, user_id, type)` 被交易外键使用。
- `fk_categories_parent` 只引用 `categories(id)`，不包含 `user_id`。本修订不改这条既有外键。
- 没有深度或防环约束。

`V5__create_tags.sql`：

- 列：`id`、`user_id`、`type`（`INCOME`/`EXPENSE`）、`name`、`created_at_utc`。没有 `parent_id`。主键是 `id`。
- `uk_tags_user_name (user_id, name)` 已经是同一用户全部标签名称唯一。现有库因此不可能同时存在两条同名标签，包括跨收支类型的同名。
- `idx_tags_user_type (user_id, type)`，`ck_tags_type`。
- 主键只覆盖 `id`。InnoDB 要求外键所引用的列在被引用表上有一个以这些列开头的索引，所以 `(parent_id, user_id)` 不能直接引用现有主键。

`V6__create_transactions.sql` 与 `V10__nullable_transaction_category.sql`：

- `transactions.category_id` 可空。
- 外键是 `(category_id, user_id, type) → categories(id, user_id, type)`，未写 `ON DELETE`，即为 `RESTRICT`。

`V7__create_transaction_tags.sql`：

- 主键 `(transaction_id, tag_id)`。
- 交易删除使用 `ON DELETE CASCADE` 清理关联。这是既有行为，本草案不改。
- 标签删除是 `ON DELETE RESTRICT`。本草案保持 `RESTRICT`，不改成 `CASCADE` 或 `SET NULL`。

`CategoryBootstrap` 与 `FixedCategories` 是当前分类的写入来源。分类 API 只有列表。标签 API 只有按收支类型的列表和创建。用户今天不能从产品入口改树。

固定种子里，支出分类「其他」出现两次：父节点「餐饮」下一处，父节点「交通」下一处。其余种子名称在同一收支类型内不重复。收入与支出种子之间没有同名。每个完成过分类初始化的用户都会有这对同名支出分类，除非库里的数据后来被手工改过。这一点来自源码，不依赖本批没有做的库内计数。

`StatisticsService` 的标签汇总按 `tags.type` 过滤。`TagEntity` 映射了 `type`。当前程序和新结构不能混用，交付顺序见第 7 节。

## 2. 草案要达到的结构

- 分类继续保留收支类型和 `parent_id`。同一用户、同一收支类型下，名称跨层级唯一。收入与支出允许同名。
- 不删除、不重插种子，不合并分类 ID。交易仍指向原来的 `category_id`。
- 标签增加可空 `parent_id`，已有标签都成为根节点。保留标签 ID 和 `transaction_tags`。删除 `type`，使标签不再按收支类型分开。
- 标签父节点使用复合外键 `(parent_id, user_id) → tags(id, user_id)`。`parent_id` 为空时，MySQL 不检查这行外键，因此根节点仍然合法。父节点一旦有值，就必须是同一 `user_id` 下的标签。跨用户挂接会被数据库拒绝。
- 为此增加 `uk_tags_id_user (id, user_id)`，供复合外键引用；增加 `idx_tags_parent_user (parent_id, user_id)`，作为子表侧索引。不再单独建只含 `parent_id` 的索引。
- 这条外键不防止节点把自己或自己的后代当成父节点。深度和防环仍留在后续 T01/T02 的服务事务里。本草案不写触发器，也不建闭包表。
- 名称唯一仍用现有 `uk_tags_user_name`。不新增级联删除。
- 分类既有的 `fk_categories_parent` 本修订保持原样。跨用户的分类父子只由预检拦住；以后的分类服务必须按同一用户校验。不把分类父外键扩进本次 V17，避免扩大既有约束的重建范围。
- 分类表不改 `transactions`，不回填金额、余额、转账或分析快照。

## 3. 推荐但尚未批准的数据步骤

支出种子的两个「其他」不能同时满足 `UNIQUE (user_id, type, name)`。不改显示名，就无法加上这条约束。

推荐方案，也是下面 SQL 中唯一的数据语句。负责人尚未批准：

- 只把父节点名称为「交通」、自身名称为「其他」的支出分类改名为「其他交通」。
- 保留该行 ID。餐饮下的「其他」保持原名。
- 不合并两条记录，不改 `transactions.category_id`。
- 「其他交通」在固定种子中尚不存在。若目标库已经有同名支出分类，预检必须使迁移停住。

这是显示名称变化，不是账务变化。拒绝该改名时，不要批准本 SQL；此时不能添加正式唯一约束。备选是暂不添加该唯一索引，只在日后的应用层拒绝新的重名，并让两个「其他」继续存在。备选不满足 CT-03 对已有数据的唯一要求，因此不作为本草案的正式语句。

标签不需要合并 ID。现有唯一索引已经排除同名标签。删除 `type` 不改标签 ID，也不改交易关联。

## 4. 待审 SQL

下面的语句按顺序构成将来的 V17。MySQL 对 DDL 隐式提交，Flyway 不能在后一条失败时回滚前一条。

```sql
ALTER TABLE tags
    ADD UNIQUE INDEX uk_tags_id_user (id, user_id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE tags
    ADD COLUMN parent_id BIGINT NULL,
    ALGORITHM=INSTANT;

ALTER TABLE tags
    ADD INDEX idx_tags_parent_user (parent_id, user_id),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE tags
    ADD CONSTRAINT fk_tags_parent
        FOREIGN KEY (parent_id, user_id) REFERENCES tags (id, user_id),
    ALGORITHM=COPY, LOCK=SHARED;

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
    ADD CONSTRAINT uk_categories_user_type_name UNIQUE (user_id, type, name),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE tags DROP INDEX idx_tags_user_type;

ALTER TABLE tags DROP CHECK ck_tags_type;

ALTER TABLE tags
    DROP COLUMN type,
    ALGORITHM=INSTANT;
```

`uk_tags_id_user` 因为 `id` 已是主键，不会遇到重复键。它存在的原因只是让复合外键有可引用的 `(id, user_id)` 索引。

`parent_id` 单独加在表尾，才能使用 `ALGORITHM=INSTANT`。二级索引不能使用该算法，所以索引用 `ALGORITHM=INPLACE, LOCK=NONE`。

复合外键按 MySQL 8.4 文档写成 `ALGORITHM=COPY, LOCK=SHARED`，并且不关闭 `foreign_key_checks`。文档写明：只有关闭 `foreign_key_checks` 时，`ADD FOREIGN KEY` 才支持 `INPLACE`；检查开启时只支持 `COPY`。本草案拒绝关闭检查来换取原地执行。`LOCK=SHARED` 允许并发读取，不允许并发写入。已有行的 `parent_id` 都是空，因此逐行父节点查找会被跳过，但整句仍然是表复制，不是只改元数据。外键动作保持默认 `RESTRICT`。

`tags` 已经是 `users` 的子表，也是 `transaction_tags.tag_id` 的父表，子表动作为 `ON DELETE RESTRICT`。本批没有执行这条语句，不能证明 MySQL 8.4 会接受对这张父表做 `COPY`。获批执行时若服务器拒绝 `COPY`、要求关闭 `foreign_key_checks`、要求改成 `INPLACE`，或要求删除 `fk_tags_user` / `fk_transaction_tags_tag`，立即停止。不改用未写出的算法，不关闭外键检查，不改子表上的既有外键。复制期间是否改写 `transaction_tags` 的内部引用，本批也无法验证；成功后必须用 `SHOW CREATE TABLE` 确认 `transaction_tags` 的外键仍指向 `tags`，且 `tags` 同时保留 `fk_tags_user` 与 `fk_tags_parent`。

更新语句只匹配同一用户下、父节点为支出「交通」的支出「其他」。匹配不到时更新零行，不创造新行。

分类语句去掉同父唯一，再加同类型全局唯一，并写明在线算法。`parent_id` 与生成列 `parent_scope_id` 保留。`uk_categories_id_user_type` 和交易外键不动。

最后先去掉依赖 `type` 的索引和检查，再按 `INSTANT` 删除该列。`uk_tags_user_name` 保留。

获批执行时，若服务器拒绝某条语句写出的算法，停在该语句。不改用本草案没有写出的算法，也不继续后面的语句。

## 5. 迁移前只读预检

本批没有跑这些语句。将来只在被点名的隔离库上执行，而且只读。适用用户是 `categories` 里出现过的 `user_id`。没有分类行的用户不在这组里。

每条语句的预期都写在语句前面。凡是预期为零行的语句返回了任何行，就 STOP。不裁剪空白，不合并记录，不改名，不补父节点。

```sql
-- 预期：零行。出现任何行即 STOP，不增删分类。
-- 只检查已经有支出「其他」的用户，他们必须恰好两条。
-- 适用用户一条都没有时，本查询没有行，由第三条拦住。
SELECT user_id, COUNT(*) AS other_count
FROM categories
WHERE type = 'EXPENSE' AND name = '其他'
GROUP BY user_id
HAVING other_count <> 2;

-- 预期：零行。出现任何行即 STOP，不改父节点、不改名。
-- 本查询只看见已经挂到同一用户支出「餐饮」或「交通」上的「其他」。
-- 一个用户两条都挂对：没有行。一条挂对、另一条挂错，或两条都挂在同一个父名称下：出现该用户。
-- 一个用户两条都挂错：本查询没有行，由下一条「用户数 × 2 = 配对行数」拦住。
SELECT child.user_id,
       SUM(parent.name = '餐饮') AS under_food,
       SUM(parent.name = '交通') AS under_transport
FROM categories AS child
JOIN categories AS parent
    ON parent.id = child.parent_id
   AND parent.user_id = child.user_id
   AND parent.type = 'EXPENSE'
   AND parent.name IN ('餐饮', '交通')
WHERE child.type = 'EXPENSE'
  AND child.name = '其他'
GROUP BY child.user_id
HAVING under_food <> 1 OR under_transport <> 1;

-- 预期：零行。有分类的用户数的两倍，必须等于挂在「餐饮」「交通」下的支出「其他」行数。
SELECT users.user_count, pairs.pair_count
FROM (SELECT COUNT(DISTINCT user_id) AS user_count FROM categories) AS users
CROSS JOIN (
    SELECT COUNT(*) AS pair_count
    FROM categories AS child
    JOIN categories AS parent
        ON parent.id = child.parent_id
       AND parent.user_id = child.user_id
       AND parent.type = 'EXPENSE'
       AND parent.name IN ('餐饮', '交通')
    WHERE child.type = 'EXPENSE'
      AND child.name = '其他'
) AS pairs
WHERE users.user_count * 2 <> pairs.pair_count;

-- 预期：零行。除这对支出「其他」以外，不允许同一用户、同一收支类型下还有别的重名。
SELECT user_id, type, name, COUNT(*) AS n
FROM categories
GROUP BY user_id, type, name
HAVING n > 1
   AND NOT (type = 'EXPENSE' AND name = '其他');

-- 预期：零行。推荐的新名称尚不能存在。
SELECT id, user_id
FROM categories
WHERE type = 'EXPENSE' AND name = '其他交通';

-- 预期：零行。不自动裁剪空白。
SELECT 'category' AS src, id, user_id, name FROM categories WHERE name <> TRIM(name)
UNION ALL
SELECT 'tag', id, user_id, name FROM tags WHERE name <> TRIM(name);

-- 预期：零行。分类现有父外键不含 user_id，这里单独拦住跨用户父子。
SELECT child.id
FROM categories AS child
JOIN categories AS parent ON parent.id = child.parent_id
WHERE child.user_id <> parent.user_id;

-- 预期：零行。不自动清理悬空分类。
SELECT t.id
FROM transactions AS t
LEFT JOIN categories AS c ON c.id = t.category_id
WHERE t.category_id IS NOT NULL AND c.id IS NULL;

-- 预期：零行。不自动清理悬空标签关联。
SELECT tt.transaction_id, tt.tag_id
FROM transaction_tags AS tt
LEFT JOIN tags AS g ON g.id = tt.tag_id
WHERE g.id IS NULL;
```

前三条必须同时符合各自的零行预期，才表示每个适用用户的两条支出「其他」分别位于同一用户的「餐饮」和「交通」之下。第一条只证明条数。第二条不覆盖「两条都挂错」的用户。第三条用全体适用用户数封住这个缺口。九条里预期为零行的语句只要出现一行，就 STOP，不自动更正。

空库会使这九条都得到零行，更新语句也会更新零行。零行只表示没有发现不合格数据，不表示种子已经被看过。演练备份里应当已经有分类用户时，适用用户数为 0 也要 STOP。本批不为此增加第十条改写语句。

## 6. 失败后的恢复

默认恢复只有一条：用执行前已经校验过的隔离库备份，把该库整库恢复到 V17 开始之前。

执行任何语句之前，只导出被点名的隔离库，记录 dump 文件的 SHA-256，并确认 dump 能识别出该库名。校验没有完成时，不开始 V17。

任一条语句失败后：

- 立即停止，不执行后面的语句。
- 不使用 `Flyway repair` 把失败版本标成成功或清除失败记录。
- 不把尚未执行的语句补跑一遍，也不写反向 `UPDATE` 或 `DROP` 来拼回原结构。
- 用这份已校验备份恢复整个库，包括其中的 `flyway_schema_history`。
- 不使用 `Flyway.clean`。开发库 `bookkeeping` 不在本草案的演练范围内。

MySQL 会在每条 DDL 上隐式提交，所以失败点之前的语句可能已经留下。这不改变恢复方式。局部结构不是可接受的中间状态，也不为它准备一套自动补语句。

## 7. MySQL 8.4 算法、锁和交付顺序

本批没有连接服务器，没有测量耗时。算法以 MySQL 8.4 的在线 DDL 能力为准，并写进了第 4 节。

| 语句 | 写出的算法 | 锁 |
|---|---|---|
| 增加 `uk_tags_id_user`、`idx_tags_parent_user` | `INPLACE`，`LOCK=NONE` | 开始和结束时短暂元数据锁；期间允许读写。需要扫描 `tags` 建索引 |
| 增加可空 `parent_id` | `INSTANT` | 短暂元数据锁，不重建表。列加在表尾 |
| 增加复合外键 | `COPY`，`LOCK=SHARED`。不关闭 `foreign_key_checks` | 复制 `tags`。并发可读，不可写。拒绝时停止，不改为 `INPLACE` |
| 改名 | 普通 `UPDATE` | 只锁被匹配到的分类行 |
| 替换分类唯一索引 | `INPLACE`，`LOCK=NONE` | 短暂元数据锁，扫描 `categories`。仍有重名时本条失败 |
| 删除 `idx_tags_user_type`、`ck_tags_type` | 元数据变更 | 短暂元数据锁 |
| 删除 `type` | `INSTANT` | 短暂元数据锁，不复制全表。列必须已经不在索引、检查和外键里 |

上述语句都不改 `transactions` 的列。复合外键这一条会复制 `tags`；其余写出 `INSTANT` 或 `INPLACE` 的语句若被服务器拒绝，不改成未写出的算法。复合外键若被拒绝，不改成 `INPLACE`，也不关闭 `foreign_key_checks`。任何拒绝都按第 6 节恢复。

V17 和当前程序不能同时使用。当前 `TagEntity`、标签统计 SQL 和 `CategoryBootstrap` 依赖 `tags.type` 与固定种子。V17 完成后这些假设不再成立。尚未编写的 T01/T02 程序则会依赖 `parent_id` 和没有 `type` 的标签表，因此也不能先于 V17 启动。

兼容顺序只有一种：同一次交付同时带上 V17 和新的分类/标签程序。由这次新程序启动时的 Flyway 先迁移，再由 Hibernate `validate` 核对新结构。不要用当前程序连接已经执行过 V17 的库，也不要在 Flyway 关闭时单独启动新程序。

本文件仍不批准把 V17 应用到开发库或个人账本。DB-CT-01 批准 SQL 之后，演练还要单独点名 `bookkeeping_migration_test`。不使用 `bookkeeping`、`bookkeeping_test` 或 `bk_s1t03_6928fd20_migration`。`CategoryBootstrap` 的固定集合核对由 T01 在同一次交付里解除，不属于本批。

## 8. 仍需负责人决定的事项

请负责人决定是否批准第 4 节的全部 SQL，作为将来的 V17 原文。其中包含尚未批准的推荐改名：支出「交通」下的「其他」改为「其他交通」，分类 ID 保持不变。

同时批准的执行边界仍然只是：随后单独点名的 `bookkeeping_migration_test` 演练，并且先有第 6 节的已校验备份。本文件不批准开发库、个人账本或生产库。

未批准前：不创建 V17 文件，不执行迁移，不开始 T01～T05。
