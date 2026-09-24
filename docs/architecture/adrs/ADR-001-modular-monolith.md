# ADR-001 · Modular Monolith

- Status: **Accepted**
- Version: v0.1

## Context

v0.1 业务范围较小，由单个开发者与 AI Coding Agent 协作开发；当前不存在独立团队、独立扩缩容或服务级隔离驱动，同时交易、余额调整、统计具有明确的一致性关系。

## Decision

采用 **Modular Monolith / 模块化单体**：单一后端应用、单一部署单元、单关系数据库事务边界；代码内部维护清晰业务模块边界，但不引入复杂模块化框架。

## Alternatives Considered

- Simple Monolith：更简单，但业务边界更容易在持续 AI Coding 中被侵蚀。
- Microservices：当前缺乏业务和运行驱动，并会引入分布式一致性、部署与观测成本。

## Consequences

获得单体事务与部署简单性，同时通过模块边界控制耦合和 AI 修改范围。需要人为和工程约束持续维护模块边界。

## Revisit Triggers

出现独立团队、独立部署/扩缩容、明显服务隔离需求，或单体边界长期无法维持时重新评估。
