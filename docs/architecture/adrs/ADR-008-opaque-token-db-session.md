# ADR-008 · Opaque Token + DB-backed Session

- Status: **Accepted**
- Version: v0.1

## Context

v0.1 使用预创建用户名密码账号，需要主动 Logout、可信 User Context 和数据隔离；没有分布式 IAM、无状态认证或多服务认证驱动。微信小程序更适合 Authorization Token，而非传统浏览器 Cookie。

## Decision

登录成功后生成高熵 **Opaque Token**；服务端在 MySQL 中维护 Session，并从 Session 建立可信 User Context。Logout 主动使 Session 失效。密码使用成熟自适应单向哈希；不使用 JWT、Refresh Token 或 Redis Session。

## Alternatives Considered

- Cookie + Server Session：浏览器自然，小程序不够自然。
- JWT：无需 Session 查询，但撤销、Logout、密钥和刷新生命周期更复杂。

## Consequences

认证生命周期直观、可主动失效、实现复杂度低；每次认证需要查询 Session，当前规模可接受。

## Revisit Triggers

多实例高流量导致 Session 查询成为瓶颈、需要跨服务认证，或引入第三方身份体系时重新评估。
