# ADR-007 · Account-level Balance Write Serialization

- Status: **Accepted**
- Version: v0.1

## Context

BalanceAdjustment 必须按提交时 Current Balance 计算差额。任何 Transaction / BalanceAdjustment 的新增、修改或删除都可能改变同一 Account Balance。当前单账户写并发低。

## Decision

所有改变 Account Balance 的核心写操作在 MySQL 本地事务中遵守统一 **Account-level Database Pessimistic Lock Protocol**。BalanceAdjustment 获得账户锁后再计算 Current Balance 与 Adjustment Amount。

## Alternatives Considered

- Optimistic Version：可行，但在 Balance 不持久化时需要额外协调状态和重试语义。
- Serializable：对全局事务过重。
- Distributed Lock：没有多服务/分布式驱动。

## Consequences

语义直接、低并发下成本低，同时为 First Save Order 的账户级分配提供自然串行化点。所有余额写路径必须统一遵守锁协议。

## Revisit Triggers

出现单账户高并发导致锁竞争、架构拆分为多个独立写服务，或数据库锁无法覆盖一致性边界时重新评估。
