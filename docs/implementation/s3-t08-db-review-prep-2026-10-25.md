# 04丨记账软件｜v1.0 数据库评审只读事实清单

- 日期：2026-10-25
- 状态：只读准备。不是评审结论，不批准新的 DDL，不开始下一业务 Stage 编码。
- 对照：已冻结 v1.0 PRD `docs/product/bookkeeping-v1.0-product-baseline-v1.0.md`，历史数据模型 `docs/design/v0.1-data-model.md`，仓库内 Flyway V1–V17。本轮没有改这些原件。

## 库的实际版本

2026-10-25 20:41 左右的只读查询：

| 库 | Flyway | 说明 |
|---|---|---|
| `bookkeeping` | 17，`success=1` | V16 记录于 2026-10-24 21:05:19。V17 记录于 2026-10-25 20:33:21。T06 复验在 20:03 结束时，该库仍是 16。本结项没有执行这条迁移。 |
| `bookkeeping_test` | 17 | 自动测试库。表集合与日常库同名。 |
| `bookkeeping_migration_test` | 无表 | `MigrationIntegrationTest` 自己的 `finally` 已清理。表计数为 0。 |

日常库 `tags` 当前列是 `id`、`user_id`、`name`、`created_at_utc`、`parent_id`。`categories` 的唯一约束包含 `uk_categories_user_type_name` 和 `uk_categories_id_user_type`。这与 V17 成功后的形状一致。旧程序不能再把 `tags.type` 当作存续列。

## 源码与 V17 已经对齐 PRD 的部分

v1.0 PRD CT-01～CT-08 覆盖了 v0.1 数据模型里“标签区分收入/支出、分类名称只在同一父节点下唯一”的写法。V17 落实的是 PRD，不是那份 v0.1 数据模型：

- 分类名称唯一变为 `(user_id, type, name)`。收入和支出可以同名。同一类型跨层级不能同名。
- 标签删除 `type`、`ck_tags_type`、`idx_tags_user_type`。名称仍由 `uk_tags_user_name (user_id, name)` 约束。
- 标签增加可空 `parent_id`，以及同用户复合外键 `fk_tags_parent (parent_id, user_id) -> tags (id, user_id)`。
- 支出种子里，交通下的「其他」改为「其他交通」，分类 ID 保留。
- 深度、成环、分类“已有直接交易不能再变父节点”在服务层。数据库没有触发器或闭包表。

## 留给评审的结构差异

这些是事实，不是本轮提出的修改方案。

1. `categories.parent_scope_id` 仍是生成列。V17 删除了使用它的 `uk_categories_scope_name`，没有删除该列。
2. `fk_categories_parent` 仍只引用 `categories(id)`。`fk_tags_parent` 已经是同用户复合外键。分类的同用户父子关系靠服务层。
3. `transaction_tags` 对标签仍是 `ON DELETE RESTRICT`。删除叶子标签时，应用先删除关联行，再删除标签。
4. 分类统计按根节点和直接子节点汇总。三级叶子的金额不会沿这条查询自动卷到根。标签统计按每个标签自己的显式关联分组，没有做 PRD ST-09 的“该标签加全部后代、按交易 ID 去重”。这是读模型与 PRD 的差距，不是一张缺表。
5. JPA 实体有 10 个，含 `TransferEntity`。`analysis_snapshots`、`analysis_quota_days`、`analysis_generations`、`analysis_user_locks` 没有对应实体，由 JDBC 使用。
6. v0.1 数据模型文档仍写着标签的 `type` 列和 `uk_categories_scope_name`。它没有被 V17 或 v1.0 PRD 改写。评审要单独决定这份历史文档如何标注，不能在评审前直接改冻结正文。

当前 15 张表：`flyway_schema_history`、`users`、`sessions`、`accounts`、`categories`、`tags`、`transactions`、`transaction_tags`、`idempotency_records`、`balance_adjustments`、`analysis_snapshots`、`analysis_quota_days`、`analysis_generations`、`analysis_user_locks`、`transfers`。V17 没有新建表。
