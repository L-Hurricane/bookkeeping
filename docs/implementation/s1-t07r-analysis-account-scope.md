# S1-T07R｜账户级 AI 月度分析隔离纠偏

- 日期：2026-10-22
- Task：S1-T07R
- 基线提交：`caab76c577cfa7d9ca5d75f0a8fa966a12639047`（T05 HEAD）
- 批准 Plan：S1 v0.3 §3.1；Plan SHA-256 `e13ef4f40fdba7c9a4258336e8f56a9441b900453808819552feb6c12515543e`

## 1. 问题

多账户下，V11 协调/快照唯一键与查询为 `(user_id, analysis_month, context_fingerprint)`，而 `AnalysisContextFingerprint` 故意不含 `accountId`。同用户不同账户在相同事实指纹下会 REUSED / 共享 `IN_PROGRESS`；`findLatest(userId, month)` 还会把 A 的报告作为 B 的 STALE。

## 2. 最小修复

1. **保持** `/api/v1/accounts/{accountId}/monthly-analysis`、账户事实构造、指纹纯内容散列、日限额（用户级）不变。
2. **持久化与协调作用域**改为 `(userId, accountId, month, fingerprint)`：`findSuccessful` / `findLatest` / `findCoordination` / `acquire` 均带 `accountId`；`findLatest` 必须账户限定。
3. **V14**（仅分析表）：`analysis_snapshots` / `analysis_generations` 增加可空 `account_id`；删除 `uk_analysis_generations_scope`；新增 `uk_analysis_generations_account_scope (user_id, account_id, analysis_month, context_fingerprint)`；复合 FK `(account_id, user_id) → accounts (id, user_id)`；新增账户作用域索引。不改 V1–V13，不改账务表。
4. **遗留行**：`account_id` 保持 `NULL`，不猜测归属、不删除。账户级查询要求 `account_id = :accountId`，故旧报告对账户 UI 不可见，需重新生成。

## 3. 变更文件（实现）

- `backend/src/main/resources/db/migration/V14__analysis_account_scope.sql`
- `AnalysisGenerationStore` / `JdbcAnalysisGenerationStore` / `MonthlyAnalysisService`
- `AnalysisSnapshot` / `GenerationPermit` / `GenerationStatus`
- 相关单测、集成测与本说明

## 4. 验证边界

Coding Agent：编译 + 无 DB 单测。隔离库集成、全量回归、T06 复验与 T07 复审由外层独立角色执行；本任务不声明 Stage PASS。

## 5. 文档注意

Architecture A27 原文写生成范围为 User + Month + Fingerprint。T07R 批准 Plan 在**持久化/协调层**增加 Account，且保留指纹纯事实语义；未改写 A01–A66 正文，后续若需正式 Architecture Change 另议。
