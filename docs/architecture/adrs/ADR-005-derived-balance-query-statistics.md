# ADR-005 · Derived Balance and Query-time Statistics

- Status: **Accepted**
- Version: v0.1

## Context

领域基线已确认 Balance、Monthly Summary、Category Statistics、Tag Statistics 都是源业务事实的派生结果，当前没有性能指标要求维护冗余状态。

## Decision

v0.1 采用 **Query-time Aggregation**：Balance 从 Opening Balance + Transaction + BalanceAdjustment 实时派生；统计从 Transaction 实时聚合。不维护 current_balance、汇总表、缓存或异步统计。

## Alternatives Considered

- Persisted Current Balance / Summary Tables：读取更快，但增加写一致性和恢复复杂度。
- Cache / Async：当前无性能和规模驱动。

## Consequences

保持单一真相来源、修改删除语义简单、易重算和验证；未来数据量增大时查询成本可能上升。

## Revisit Triggers

真实监控或压测证明查询成本成为问题时，可以增加可重建缓存/汇总，但源业务事实仍保持最终真相。
