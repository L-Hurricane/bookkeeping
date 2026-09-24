# ADR-010 · Time and Ledger Ordering

- Status: **Accepted**
- Version: v0.1

## Context

产品明确区分 Business Record Time 与首次保存语义：业务时间决定月份和主排序；同一业务秒内后首次保存者在前；编辑不得改变首次保存排序依据。Transaction 与 BalanceAdjustment 分开持久化，因此共享排序顺序不能依赖各自表内 AUTO_INCREMENT。

## Decision

保留四种语义：Business Record Time、First Saved Time、First Save Order、Updated Time。Ledger 排序固定为 `Business Record Time DESC, First Save Order DESC`。

**First Save Order 采用 Account-scoped monotonic database order**：所有首次创建 Transaction / BalanceAdjustment 的写操作已按 ADR-007 获取 Account 行级锁；在同一事务内从该 Account 的持久化计数状态分配下一个单调顺序，再保存业务记录。具体是 Account 字段还是辅助表由 Data Model 决定。

该顺序只需账户范围唯一且单调，不要求全局连续、无 gap 或具有展示意义。Updated Time 仅为 Technical Metadata，不参与业务排序。

## Alternatives Considered

- 高精度 timestamp：不能作为绝对稳定同秒排序兜底。
- 两表各自 AUTO_INCREMENT：无法表达跨两类记录统一先后。
- 独立全局 sequence/counter 表：可行，但当前已有 Account 锁串行化点，额外全局协调没有收益。
- Redis / 分布式 ID：明显过度设计。

## Consequences

复用 Account 写串行化机制，低复杂度满足跨两类记录稳定顺序；将排序范围限定在实际 Ledger 所属 Account，避免全局热点。未来若出现跨账户统一 Ledger，需要重新定义排序作用域。

## Revisit Triggers

出现跨账户统一流水排序需求、Account 写锁被替换、单账户高并发成为瓶颈，或数据迁移要求改变排序机制时重新评估。
