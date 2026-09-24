# ADR-004 · MySQL

- Status: **Accepted**
- Version: v0.1

## Context

关系型持久化需要支持 ACID、本地事务、用户作用域唯一性、分类/标签关系、月度统计与备份恢复。Architecture Options 曾推荐 PostgreSQL，主要额外优势是原生 Sequence 可低成本提供跨 Transaction / BalanceAdjustment 的共享 First Save Order。人工 Review 更看重长期维护与排障成本。

## Decision

正式选择 **MySQL**。理由是其能力足以满足 v0.1，同时开发者在实际工作中使用更频繁，项目属于个人、低数据量、低频维护场景。First Save Order 的共享顺序通过 MySQL 内部的 Account-scoped monotonic counter 解决，而不因此切换数据库。

## Alternatives Considered

- PostgreSQL：原生 Sequence 对共享顺序更方便，但该单点优势不足以覆盖长期熟悉度和维护成本差异。
- SQLite：本地部署简单，但与独立后端、远程部署和未来多客户端形态不如服务型关系数据库匹配。

## Consequences

保留熟悉稳定的数据库栈；需要显式实现账户范围共享保存顺序。项目不依赖 PostgreSQL 特有能力。

## Revisit Triggers

如果未来出现 MySQL 无法低复杂度满足的明确能力需求、托管平台明显偏向其他数据库，或数据/查询规模发生结构性变化时重新评估。
