# DB-CT-01 执行批准记录

Status: Owner approved on 2026-10-25. This file records that approval. It does not replace the S3 plan, and it does not edit the schema draft.

草案原件 `docs/implementation/s3-t00-db-ct-01-schema-draft-2026-10-25.md` 保持原字节。批准时 SHA-256：`752e2febcc0b79dc4dd82229c73a2f6219e00571f7807f27d3b4dbdfc4342a03`。草案第 3 节和第 8 节仍写着“尚未批准”。那是获批原件的原文，不因本记录改写。批准事实以本文件为准。

已批准 S3 计划 SHA-256 仍是 `c871888bfa1ee09996e005adcdbe94afb568837699a425631c02a8dc396a79ef`。本记录不修改该文件。

## 批准链

负责人于 2026-10-25 明确回复：“批准方案A”，并强调当前项目尚未上线，v1.0 优先保障功能正确。

方案 A 就是草案第 4 节的九条 SQL，其中包括把支出「交通」下的「其他」改为「其他交通」、保留分类 ID。

## 批准范围

- 草案第 4 节九条 SQL，作为将来的 V17 原文。
- 支出「交通」下的「其他」改为「其他交通」，保留该分类 ID。不合并记录，不改 `transactions.category_id`。
- 仅 `bookkeeping_migration_test`：受控备份、恢复验证、九条只读预检、V17 演练。
- 草案原有 STOP 条件继续有效。

## 不属于本次批准

- 开发库 `bookkeeping`、`bookkeeping_test`、`bk_s1t03_6928fd20_migration`、个人账本和生产库。
- 关闭 `foreign_key_checks`、改用草案未写出的算法、删除 `fk_tags_user` 或 `fk_transaction_tags_tag`、清洗或自动更正异常数据。
- 把 V17 提前应用到日常开发库。旧程序不能连接已经移除 `tags.type` 的库。
- 修改已批准 S3 范围、PRD、UI Freeze 或业务规则。Push、部署、合并 main。

预检或演练出现草案写明的拒绝或异常时停止。不把失败演练写成 T01 的启动条件。

## 获批 SQL

与草案第 4 节原文一致：

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

## 获批预检

九条都只读，预期都是零行。出现任何行即 STOP，不自动更正。适用用户是 `categories` 里出现过的 `user_id`。前三条同时为零行，才表示每个适用用户的两条支出「其他」分别位于同一用户的「餐饮」和「交通」之下。

空库会使九条都得到零行。演练备份里应当已经有分类用户时，适用用户数为 0 也要 STOP。

```sql
-- 1
SELECT user_id, COUNT(*) AS other_count
FROM categories
WHERE type = 'EXPENSE' AND name = '其他'
GROUP BY user_id
HAVING other_count <> 2;

-- 2
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

-- 3
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

-- 4
SELECT user_id, type, name, COUNT(*) AS n
FROM categories
GROUP BY user_id, type, name
HAVING n > 1
   AND NOT (type = 'EXPENSE' AND name = '其他');

-- 5
SELECT id, user_id
FROM categories
WHERE type = 'EXPENSE' AND name = '其他交通';

-- 6
SELECT 'category' AS src, id, user_id, name FROM categories WHERE name <> TRIM(name)
UNION ALL
SELECT 'tag', id, user_id, name FROM tags WHERE name <> TRIM(name);

-- 7
SELECT child.id
FROM categories AS child
JOIN categories AS parent ON parent.id = child.parent_id
WHERE child.user_id <> parent.user_id;

-- 8
SELECT t.id
FROM transactions AS t
LEFT JOIN categories AS c ON c.id = t.category_id
WHERE t.category_id IS NOT NULL AND c.id IS NULL;

-- 9
SELECT tt.transaction_id, tt.tag_id
FROM transaction_tags AS tt
LEFT JOIN tags AS g ON g.id = tt.tag_id
WHERE g.id IS NULL;
```
