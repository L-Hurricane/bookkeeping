# 04丨记账软件｜2026-10-25 审批索引 v0.6

- **状态**：现行索引入口的增量。不是新的 Git、部署或生产授权。
- **相对 v0.5**：`docs/governance/bookkeeping-2026-10-23-approval-index-v0.5.md` 原字节不改，SHA-256 仍是 `b3a950a8223b14d5656e9907ef8c2e95da5539dabad4ee69ef9203c278a8c8f2`。本文件只登记其后的 DB-CT-01 批准，不覆盖 v0.5 已登记的批准原件。

## 新增批准

| 事项 | 原件 | SHA-256 | 批准事实 |
|---|---|---|---|
| DB-CT-01 | `docs/implementation/s3-t00-db-ct-01-schema-draft-2026-10-25.md` | `752e2febcc0b79dc4dd82229c73a2f6219e00571f7807f27d3b4dbdfc4342a03` | 负责人 2026-10-25 回复“批准方案A”。独立记录 `docs/implementation/s3-db-ct-01-approval-2026-10-25.md`，SHA-256 `cbef969c52d6c5cd3a79d8e76472a28e79428fef5899044be9c018b63c63ccc0`。范围是草案九条 V17 SQL、支出「交通 / 其他」改名为「其他交通」并保留分类 ID，以及仅 `bookkeeping_migration_test` 的备份、恢复验证、九条只读预检和 V17 演练。 |

草案原件不因批准而改写。开发库、其他数据库、Push、部署和合并 main 不在本索引新增范围内。
