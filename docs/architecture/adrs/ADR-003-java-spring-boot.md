# ADR-003 · Java + Spring Boot

- Status: **Accepted**
- Version: v0.1

## Context

项目核心难点是领域约束、关系型事务、认证、数据一致性和长期维护；开发者长期使用 Java 企业应用，并以 AI Coding Agent 协作开发。

## Decision

后端采用 **Java + Spring Boot**。精确 Java/Spring Boot 版本、构建工具和包结构在工程初始化阶段确定。

## Alternatives Considered

- Node.js + TypeScript：开发轻量，但当前没有前后端统一语言的团队收益。
- 其他语言：没有明确 Architecture Driver 支持切换。

## Consequences

可直接利用成熟事务、安全、测试和数据库生态，并降低开发者 Review 与排障成本；相较极小脚本型项目工程结构略重。

## Revisit Triggers

团队能力结构明显变化、Spring 生态不再满足核心需求，或未来出现当前技术栈难以承载的新约束时重新评估。
