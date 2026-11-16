# 04丨记账软件｜2026-11-09 审批索引 v0.22

- **状态**：现行索引入口的增量。登记 v1.1 收尾范围和 C11 字段核查。不授权编码、Schema、个人库操作、Push 或部署。
- **相对 v0.21**：原件字节不改。v0.21 SHA-256 仍是 `e81653ddec1083163b625206ac42bc5aa2972da05435e0db593fba8bbed299c1`。S1 仍是 CLOSED。D01 仍是 REVIEW / NOT APPROVED。

## 收尾范围

负责人确认本期交付 C01–C08 与 C11。C09/C10 暂缓。分类、标签同级排序留下一批。

| 事项 | 原件 | SHA-256 | 状态 |
|---|---|---|---|
| UI 差异 v0.2 | `docs/implementation/bookkeeping-v1.1-closeout-ui-diff-v0.2.md` | `d391816ed605eb1bc2885940a60dcfca7ce4de4fbc6d5ce691969ba4c6acf677` | 范围已确认。不是编码授权 |
| Task Plan v0.2 | `docs/implementation/bookkeeping-v1.1-closeout-stage-task-plan-v0.2.md` | `c7a42ff930a8de083abad36afdbff3c565d14bccb16814f8138c6c855e881b04` | 范围已确认。C11 Schema NOT APPROVED |

仓库内没有本收尾批次的 v0.1 文件。已有 v0.1 原件未改，包括 UI Freeze Diff v0.1、S1 结项核对 v0.1 和 S1 遗留 v0.1。

## C11

`transactions` 没有与「交易对方」同义的列。`name` 是交易名称，`remark` 是备注，转账名称是另一张表。候选是下一文件 `V21` 增加可空 `counterparty_name`。V21 本身不含 UPDATE。历史回填已记为待实施方案：不清空、不重导；账户＋描述＋业务时间优先，歧义时再看分类、金额和类型；只回填一对一的支出。先出匹配报告，再由负责人本机执行。该方案和 V21 都尚未批准，本索引不实施。

## 未授权

编码、Flyway、个人库备份或恢复、新建数据库、Push、PR、Merge、部署。
