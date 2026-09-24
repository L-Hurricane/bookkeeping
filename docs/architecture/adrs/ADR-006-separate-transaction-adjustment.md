# ADR-006 · Separate Transaction and BalanceAdjustment

- Status: **Accepted**
- Version: v0.1

## Context

Transaction 与 BalanceAdjustment 在领域上有不同不变量、统计规则和编辑生命周期，但都出现在 Ledger 并影响 Balance。

## Decision

二者保持 **独立领域对象并分开持久化**；Ledger 在查询层组合。Ledger Entry 仅为 Read Model 语义，不建立为了复用而产生的实体继承。

## Alternatives Considered

- 单一统一记录表：排序方便，但产生大量可空字段并弱化领域约束。
- 领域继承层次：当前共同业务行为不足以支持额外抽象。

## Consequences

领域边界和数据库约束更清晰；Ledger 查询需组合两类来源，并要求共享 First Save Order。

## Revisit Triggers

如果未来出现多种高度一致的 Ledger Record 类型并形成真实共享行为，可重新评估统一抽象；不能只因查询方便改变领域模型。
