# ADR-009 · Idempotent Create Operations

- Status: **Accepted**
- Version: v0.1

## Context

产品要求一次保存操作即使发生重复点击或网络重试，也不能产生多条业务记录。仅靠前端禁用无法形成服务端可验证保证。

## Decision

创建型操作（至少 CreateTransaction、CreateBalanceAdjustment）采用 **Operation ID / Idempotency Key + MySQL 唯一性保证**；同一用户保存意图在重试时复用同一 Operation ID。前端按钮禁用仅作为 UX 防护，不作为最终正确性机制。

## Alternatives Considered

- Frontend Guard Only：无法处理网络重试和重复请求。
- Redis Idempotency：当前无必要。

## Consequences

可以在单数据库事务内验证和保证创建幂等，不增加外部组件。客户端和服务端需要明确 Operation ID 生命周期及重复请求返回语义。

## Revisit Triggers

多服务、多数据库或超高并发导致单数据库唯一性不能覆盖创建边界时重新评估。
