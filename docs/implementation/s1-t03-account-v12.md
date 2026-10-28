# S1-T03｜账户多账户领域与 V12 迁移说明

- 日期：2026-10-22
- Task：S1-T03
- 基线提交：`a393734ad147a5dbe11a83cebfd3381f408db7b2`
- 本地恢复点标签：`s1-t03-baseline`（同提交）

## 1. Schema 核验（迁移前事实）

`V3__create_accounts.sql` 实际约束：

- `uk_accounts_user UNIQUE (user_id)`：每用户至多一个账户
- `uk_accounts_id_user UNIQUE (id, user_id)`：供 `transactions` / `balance_adjustments` 复合归属 FK 使用
- `fk_transactions_account` / `fk_adjustments_account` 引用 `(account_id, user_id) → accounts (id, user_id)`

不得重建 Account ID，不得改写 Opening Balance / Adjustment Amount / `first_save_order`。

## 2. V12 增量设计

文件：`backend/src/main/resources/db/migration/V12__multi_account_kind_and_name.sql`

1. 新增 `kind VARCHAR(16) NOT NULL DEFAULT 'NORMAL'`，CHECK：`NORMAL` | `CREDIT`（旧行默认为普通账户）
2. **先**新增 `uk_accounts_user_name UNIQUE (user_id, name)`（S1：同用户账户名全局唯一；归档尚未交付，故暂不引入 status）
3. **后**删除 `uk_accounts_user`
4. 保留 `uk_accounts_id_user` 与既有 FK（含 `fk_accounts_user`、`fk_transactions_account`、`fk_adjustments_account`）

### 2.1 Cross Review 修复：外键索引顺序（2026-10-22）

问题：初版 V12 先 `DROP INDEX uk_accounts_user`，再 `ADD UNIQUE (user_id, name)`。

核实：

- `fk_accounts_user` 在 `accounts.user_id → users(id)`，InnoDB 要求引用列有最左前缀为 `user_id` 的索引
- 迁移前该支撑索引是 `uk_accounts_user (user_id)`
- `uk_accounts_id_user (id, user_id)` 最左列是 `id`，**不能**支撑 `fk_accounts_user`
- 先 DROP 会触发 MySQL「索引被外键依赖、不可删除」类错误；当前版本未上线、无真实业务数据，负责人允许直接修正 V12，不另做数据转换/回填

修复：在同一 V12 内对调顺序——先加 `uk_accounts_user_name (user_id, name)`（最左为 `user_id`，可继续支撑 FK），再 DROP `uk_accounts_user`。不新增 Flyway 版本号；账户 ID / 交易 ID / 归属 FK / Flyway 版本管理均保留。

仅允许在隔离库白名单上跑迁移测试：`bookkeeping_migration_test`，以及本 Task 专用库 `bk_s1t03_6928fd20_migration`（固定全名，非前缀/模式匹配）。禁止对开发库 `bookkeeping`、普通测试库 `bookkeeping_test` 或生产库执行。

## 3. API（最小账户能力）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/accounts` | 返回当前用户全部账户（含 `kind`），按 id 升序 |
| POST | `/api/v1/accounts/initialize` | 兼容旧客户端：无账户时创建「微信」+ `NORMAL`；已有任一账户则 `409 ACCOUNT_ALREADY_INITIALIZED` |
| POST | `/api/v1/accounts` | 创建：`name` / `kind` / `openingBalance` |
| PATCH | `/api/v1/accounts/{accountId}` | 更名；跨用户 `404 RESOURCE_NOT_FOUND`；重名 `409 ACCOUNT_NAME_CONFLICT` |

名称去首尾空白；唯一性由应用预检 + MySQL `utf8mb4_0900_ai_ci` 共同保证。不实现归档/删除/转账（S2）及交易跨账户（T04）。

## 4. AI 月度分析指纹风险（未改代码）

`AnalysisContextFingerprint` 合同明确 **不包含 accountId**。生成协调唯一键为 `(user_id, analysis_month, context_fingerprint)`。多账户下，两账户同月账务事实若指纹相同，可能误复用 Snapshot 或撞 `uk_analysis_generations_scope`。T02 要求：若修复超出本 Task，提交 Plan 变更。T03 **未**修改分析模块；此项记为需负责人决策的后续项，不在本 Task 擅自扩范围。

## 5. 测试意图

- `MigrationIntegrationTest`：V9 灌入旧账户 → 升到 V12 后 **账户 ID 不变**、`kind=NORMAL`、可建第二账户、同名失败、交易 FK 仍有效；V12 后仍存在 `fk_accounts_user`，且 `uk_accounts_user_name` 列序为 `(user_id, name)`
- `AccountMultiAccountIntegrationTest`：创建普通/信用、列表、旧 initialize 兼容、同用户重名负例、跨用户同名允许、更名与归属隔离
- `AccountNamesTest`：纯单元，无 DB
- 既有 `AccountCategoryIntegrationTest` 适配多账户仓储 API 与响应 `kind`

## 6. 本机验证记录（2026-10-22）

执行身份：`ai-runner` / UID 502。分支：`task/s1-t03`。

已执行（T03 初版）：

- `./mvnw -DskipTests compile test-compile` → SUCCESS
- `./mvnw -Dtest=AccountNamesTest,MigrationSchemaGuardTest test` → Tests run: 4, Failures: 0

V12 索引顺序修复后：

- 本轮按任务要求 **不** 访问开发库/生产库，**不** 自行跑需 DB 的迁移集成测试；数据库验证留给后续独立命令
- 仍需验证：`MigrationIntegrationTest`（含 `fk_accounts_user` / `uk_accounts_user_name` 列序断言）在隔离库上 clean → V9 灌数 → migrate to V12 成功

### 6.1 MigrationSchemaGuard 专用库白名单（同日）

问题：外层将 `MIGRATION_TEST_DB_URL` 指向专用库 `bk_s1t03_6928fd20_migration` 时，Guard 与 `MigrationIntegrationTest` 的固定库名断言会拒绝。

修复（仍在 T03 范围内）：

- `MigrationSchemaGuard` 白名单额外允许固定全名 `bk_s1t03_6928fd20_migration`；不接受任意库名或前缀匹配
- `MigrationIntegrationTest` 连接后断言 catalog 等于 URL 解析出的库名（仍先经 Guard）
- `MigrationSchemaGuardTest` 补充该专用库正向用例，以及非白名单任意名负向用例；开发库 / `bookkeeping_test` 拒绝用例保持
- `./mvnw -Dtest=MigrationSchemaGuardTest test` → Tests run: 4, Failures: 0

### 6.2 普通测试库白名单与 Bootstrap V12（同日）

问题：完整回归中，外层将 `TEST_DB_URL` 指向专用库 `bk_s1t03_6928fd20_test` 时，若干旧测试仍硬编码要求 `bookkeeping_test`；`BootstrapIntegrationTest` 仍断言已应用 Migration 为 11，与已批准 V12 不一致。

修复（仍在 T03 范围内；不改账户业务实现、不改 V12 SQL、不进入 T04）：

- 新增 `TestSchemaGuard`：精确白名单仅 `bookkeeping_test` 与 `bk_s1t03_6928fd20_test`（固定全名，非前缀/模式匹配）；继续拒绝开发库、迁移库及未知库名
- 下列测试改用 Guard，保留原安全/业务断言：`BootstrapIntegrationTest`、`DefaultGeneratorAssemblyTest`、`InternalGenerationFaultHttpTest`、`MockGenerationFailureHttpTest`、`MockGenerationHttpTest`、`UnconfiguredProviderGenerationHttpTest`、`ErrorDispatchSecurityHttpTest`、`MockAnalysisStartupTest`
- `BootstrapIntegrationTest` 已应用 Migration 数量断言改为 12（与 V12 一致）
- `TestSchemaGuardTest`：两白名单正向 + 开发/迁移/任意名负向

本小节代码修复完成后按任务要求停止；定向测试与完整回归由外层命令执行，此处不宣称 PASS。

恢复：`git checkout s1-t03-baseline` 或 `git reset --hard s1-t03-baseline`（仅在确认可丢弃工作区改动时）。

### 6.3 T03 任务级验证收口（2026-10-22）

- V9 → V12 迁移集成测试通过。
- 多账户创建、更名、归属隔离和权限负例测试通过。
- 完整后端回归：239 tests，0 failures，0 errors，0 skipped；BUILD SUCCESS。
- ChatGPT 静态 Cross Review：无明确 P0/P1 阻断，遗留 P2 已记录。
- 本记录为 T03 任务级收口；S1-T07 独立 Supervisor 与 T08 人工验收仍待执行。
- 仅测试专用数据库，未对现有开发库或生产库执行迁移。
