# 04丨记账软件｜S6-T07 独立 Testing + Supervisor Gate v0.1

- 日期：2026-10-27
- 状态：Testing PASS，Supervisor PASS。P0/P1 = 0。
- 本记录不开始 T08，不 Push，不合并 main，不部署。S6 未结项。真实供应商生成不是 PASS。

## 结论

| 项 | 结果 |
|---|---|
| Testing | PASS。独立会话 [S6-T07 independent testing](4be91dae-aa56-44d5-90ba-525993612b08) |
| Supervisor | PASS。独立会话 [S6-T07 independent supervisor](45dc0398-e245-47b0-b455-05003a82dfb1) |
| P0 / P1 / P2 / P3 | 0 / 0 / 0 / 0 |
| 受审 HEAD | `8d0ddb2604b708912347384908a601aa89ade65f` |
| T08 | 可由组长决定。本 Gate 不进入 T08 |

## 环境与 Git

- 分支 `uat/v1.0-s2`。受审 HEAD 相对产品提交 `5ba4aabfc8820fdb851691f11a417bab0075c842` 只增加三份文档：`docs/CURRENT.md`、`docs/implementation/s6-t06-regression-2026-10-27.md`、`docs/implementation/v1.0-s6-ai-generation-backlog-v0.1.md`。
- 没有连接或迁移 `bookkeeping`、`bookkeeping_test`。没有运行未过滤的 `./mvnw test`。没有调用付费模型。8080 上的现有进程没有被重启。

## 执行过的测试

- 小程序 `npm test`：238 通过，0 失败；typecheck 与 lint 通过。
- 后端单元测试 88 项，失败 0，错误 0。未配置供应商的生成在发出 HTTP 之前失败。
- `S5T06IsolationGateTest`（`S5_T06_RUN=1`）：1 项通过。库是 `bookkeeping_migration_test`，Flyway 关闭。失败的 Stub 生成是 502 `GENERATION_FAILED`，没有成功快照，消耗额度仍是 0。`LEDGER` 与 `ACCOUNT` 分开。历史快照可以再次读到。
- 隔离转储 SHA-256 `c5f6878fc759bc9840141f24ebe9bbf54cc51f8e4222e3dee9ab9e3619d40088`，与执行前一致。恢复后 Supervisor 只读复核：只有用户 `v18-drill`，Flyway 1～18 全部成功，快照仍是 `ACCOUNT / 2026-10` 与 `LEGACY / 2024-01`。

Supervisor 没有重跑这些测试。

## 未测范围

- 真实 DashScope 生成、成功快照保存和再次读取。`S6-AI-GEN-01` 仍是 v1.0 最终人工验收前必须验证的事项，不能写成已通过。
- 微信真机。
- HTTP MockMvc。
- 完整 `./mvnw test`。
- `bookkeeping` 与 `bookkeeping_test`。

## T08

前置条件已满足：Testing PASS，Supervisor PASS，P0/P1 = 0。T08 仍须由负责人执行。上面的未测清单不能在最终验收里被当成已经通过。
