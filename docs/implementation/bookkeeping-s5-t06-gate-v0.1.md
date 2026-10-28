# 04丨记账软件｜S5-T06 独立 Testing + Supervisor Gate v0.1

- 日期：2026-10-26
- 状态：Testing PASS，Supervisor PASS。P0/P1 = 0。
- 本记录不开始 T07，不 Push，不合并 main，不部署。S5 未结项。

## 结论

| 项 | 结果 |
|---|---|
| Testing | PASS。独立会话 [S5-T06 independent testing](bc63f8e1-a061-4522-95f6-bec060961e8e) |
| Supervisor | PASS。独立会话 [S5-T06 independent supervisor](8d8079ea-0188-433a-98da-4c47c58f42c9) |
| P0 / P1 / P2 / P3 | 0 / 0 / 0 / 1 |
| 修复提交 | 无。P3 不阻断，未改产品代码 |
| T07 前置 | 已满足。本 Gate 不进入 T07 |

## 环境与 Git

- 分支 `uat/v1.0-s2`。受审产品 HEAD：`d3d785633bc5f6e06673692a2c4d64e17d748017`。
- 批准计划 SHA-256：`1958595f6ff480273fb460d3e70245167f8aff6adf7d19cd7332ef1708ecf964`。
- 方案 B：永久删除任一账户后，该用户已保存的全账本 AI 月报和年报不再可读。额度不退回。不恢复账户。
- 没有连接或迁移 `bookkeeping`、`bookkeeping_test`。没有运行 `./mvnw test` 或 `MigrationIntegrationTest`。没有调用付费模型。

## 隔离库

- MySQL `8.4.3`。`DATABASE()` 为 `bookkeeping_migration_test`。Flyway 1～18 全部 `success=1`。基线表数 15。
- 场景前只有合成用户 `v18-drill`。
- 执行前转储：`/Users/limingyuan/Documents/code/personal/bookkeeping-v18-drill/evidence/s5-t06/pre-s5-t06.sql`
- 转储 SHA-256：`c5f6878fc759bc9840141f24ebe9bbf54cc51f8e4222e3dee9ab9e3619d40088`
- 恢复证明：把该用户名改成标记后再载入同一转储，库名、Flyway 18 和用户名回到场景前。
- 场景后再次载入该转储。Supervisor 只读复核：用户仅 `v18-drill`，用户 1、账户 1、快照 2、生成 2，其余业务表 0。两条快照仍是 `ACCOUNT / 2026-10` 与 `LEGACY / 2024-01`。外键检查为 1。
- Spring 上下文在启动前把数据源固定到该库，并关闭 Flyway。应用库用户无权访问该库，门禁用本地管理连接。目录核对为 `bookkeeping_migration_test` 后才写入。

## 执行过的测试

- `./mvnw -Dtest=MonthlyAnalysisServiceTest,LedgerAnalysisRulesTest,AccountPermanentDeletionTest test`：33 项，失败 0，错误 0，跳过 0。
- `S5T06IsolationGateTest`（`S5_T06_RUN=1`）：1 项通过。九段：统计、事实与期间、V18、额度、服务生成、fencing、方案 B、回滚、演练数据未被动过。
- 小程序 `npm test`：235 通过，0 失败；typecheck 与 lint 通过。
- 模型输出使用本地 Stub。

Supervisor 没有重跑这些测试。隔离库的终态与转储身份一致。

## 关键观察

- 2026-10 全账本：收入 `90.00`，支出 `-20.00`（已保存的 `-30.00` 与 `10.00` 直接相加）。期初 `94.00`，期末 `176.50`，变化 `82.50`。归档收入计入 `ALL`，正常账户范围不含归档。跨月转账按转出、转入时间分别计入。空账本为零。负净值保持负数。永久删除后，被删账户的收入不再出现。
- `LEDGER` 要求 `account_id` 为空。第二次成功快照不改写第一次文本。`LEGACY` 的空账户不会被读成全账本。非法作用域或期间文本为 MySQL `3819`，重复协调键为 `1062`。
- 账户分析与全账本共用每日 3 次。查看历史和相同指纹复用不增加 `consumed_count`。第 4 次为 `QuotaExceeded`。失败不留成功快照，也不计成功次数。过期 fencing token 不能再写快照。并发获取为 1 个 `Granted`、1 个 `Busy`。
- 删除预览计入全账本报告。执行后该用户的全账本月报和年报消失，保留账户的 `ACCOUNT` 报告和 `LEGACY` 行仍在，其他用户的全账本报告仍在，已消耗次数不变。引用冲突导致事务回滚，账户、交易和全账本行都留在原处。
- 其他用户读取或删除本用户账户得到 `404`。非法期间得到 `400`。模型载荷含分类汇总，不含预埋备注、交易名、账户名和令牌。

## 缺陷

| ID | 级别 | 处置 | 说明 |
|---|---|---|---|
| `S5T06-D1` | P3 | Backlog，不阻断 | 主动生成时空期间返回 `409 EMPTY_PERIOD`。前端只把 `EMPTY_MONTH` 显示成空期间提示，因此这条生成错误路径显示“分析没有完成。这不是账本数据出错。”已加载的空期间使用 `EMPTY_MONTH`，页面不提供生成。批准计划和 UI 冻结没有规定这条生成错误必须使用另一句固定文案。 |

## 未测范围

- 微信真机和人工 UAT。
- HTTP MockMvc、会话过滤器和 Jackson 响应体。控制器和异常处理原样传出 `ApiFailure`，本 Gate 没有在 HTTP 层再执行一遍。
- 完整 `./mvnw test`。
- 付费模型，以及模型返回带金额句子时的校验路径。
- 空自然年沿用 `EMPTY_MONTH` 的月份措辞。批准计划没有单独冻结年的空期间句子。

## T07

前置条件已满足：Testing PASS，Supervisor PASS，P0/P1 = 0。T07 仍须由负责人人工执行。上面的未测清单不能在 UAT 里被当成已经通过。
