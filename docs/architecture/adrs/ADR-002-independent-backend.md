# ADR-002 · Independent Backend

- Status: **Accepted**
- Version: v0.1

## Context

首个客户端是微信小程序；核心业务包含密码认证、用户隔离、事务一致性、持久化和备份恢复，未来还可能增加其他客户端。

## Decision

采用 **WeChat Mini Program + Independent Backend**。核心业务规则、认证、用户隔离、事务和持久化由可信后端执行；小程序负责 UI、输入、展示与客户端交互状态。

## Alternatives Considered

- Cloud Functions / Serverless：初始运维较轻，但平台约束、事务与本地调试/备份路径更依赖具体平台。
- 客户端直连数据存储：无法形成可信业务与用户隔离边界。

## Consequences

增加一个后端运行单元和数据库运维责任，但得到稳定可信边界、可测试性和多客户端复用能力。

## Revisit Triggers

若未来确定的平台能显著降低运维成本，同时完整满足事务、认证、隔离、备份恢复和本地开发要求，可重新评估 Serverless。
