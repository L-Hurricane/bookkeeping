# 04丨记账软件｜S4-T06 独立 Testing + Supervisor Gate v0.1

- 日期：2026-10-26
- 状态：Testing PASS（定向复验后），Supervisor PASS。P0/P1 = 0。
- 本记录不开始 T07，不 Push，不合并 main，不部署。

## 结论

| 项 | 结果 |
|---|---|
| Testing | 首轮 FAIL。定向修复后复验 PASS。最终 P0/P1 = 0 |
| Supervisor | PASS |
| P0 / P1 / P2 / P3 | 0 / 0 / 0 / 1 |
| T07 前置 | 已满足。本 Gate 不进入 T07 |

## 环境与 Git

- 分支 `uat/v1.0-s2`。复验时工作区干净。
- Gate HEAD：`608aca427b53dacd263750fe73081e4c00c68836`。
- Coding 完成：`1c18ce9206c1299474574cc41e8ca9a329d133cf`。
- V18 与 S4 批准原件归档：`4018aa38d61f7a2fe87f808478771b4b1edd10d8`。
- 远程 `origin/release/v1.0` 仍是 `8012a9ef262d2504c1da46f1bea6ddd25af55ac9`。未推送。
- 测试机执行了不启动 Spring 的后端单元测试，以及小程序 `npm test`、typecheck、lint。没有连接 `bookkeeping`、`bookkeeping_test` 或 `bookkeeping_migration_test`。没有运行微信真机。

## 执行过的测试

首轮，HEAD `1c18ce9`：

- `./mvnw -Dtest=StatisticsPeriodTest,StatisticsShareTest,CategoryRollupTest,TagRollupTest test`：退出码 0。11 项，失败 0，错误 0，跳过 0。
- 小程序测试 227 项通过，typecheck 与 lint 通过。

定向复验，HEAD `608aca4`：

- 同一组后端测试：退出码 0。12 项，失败 0，错误 0，跳过 0。
- 小程序测试 228 项通过，typecheck 与 lint 通过。

没有运行 `./mvnw test`，也没有运行任何 `@SpringBootTest`。

## V18

- 文件 SHA-256：`9ddb179ef52bcc8518e6b3b620b259c812205262fd264988e0e15f95f4151005`。
- 设计 Review v0.3 SHA-256：`7844121057e2d14504c9ca21baf82d8b83fff8a9638b2bb7cd56e8c5525b47ae`。
- 12 条可执行语句与 v0.3 第 5 节一致。文件哈希不同只因为文件头有三行注释。
- `application.yml` 开启 Flyway，位置是 `classpath:db/migration`。测试配置默认数据源是 `bookkeeping_test`，没有关闭 Flyway。一次普通 Spring 测试会把 V18 迁入 `bookkeeping_test`。本 Gate 因此拒绝该运行。统计实现不读取 V18 列。

## 首轮缺陷与复验

| ID | 首轮 | 复验 | 说明 |
|---|---|---|---|
| F1 | P0 | 关闭 | 支出金额被统一取负，冻结样例 400/300/300 变成负占比。`signed()` 现返回交易已保存的金额。正支出保持 40%/30%/30%，代数和 100%。保存为负数的金额仍是负占比；净额为负时代数和为 -100%；净额为 0 不给占比。 |
| F2 | P1 | 关闭 | Jackson 省略 null。统计页因此拒绝正常响应和未命名交易。解析器把缺失的占比、空切片和缺失的名称视为空。非法金额和非法占比仍拒绝。 |
| F3 | P3 | 未改 | 统计页模块标题写「净资产 / 历史时点净资产」。冻结文案和 ST-04 使用「账本净值」，并且不把该指标称为完整家庭净资产。页面没有写出「完整家庭净资产」，计算范围仍是账本账户。不阻断本 Gate。 |

修复提交只改了金额符号、对应单元测试、统计响应解析和一条前端测试。没有改 V18、`CurrentBalance` 或原单账户月统计接口。

## 未测范围

下列事项没有在 MySQL 或小程序真机上执行。代码阅读没有看到与冻结口径相反的公式，但不能把它们写成已证明：

- `asOf` 的期初、期末、趋势，以及未来已保存记录不进入历史时点、但仍进入当前账面余额。
- 转出、转入各自的业务时间，以及只一侧落入时点时的净值变化。
- 余额调整进入历史时点，并且不进入收入或支出。
- 信用账户负数进入所选账户合计。
- 默认排除归档账户、自选纳入归档账户、永久删除账户不可选。
- 账户、交易、分类、标签的跨用户隔离。
- 小程序实际渲染一份省略了 null 的响应。
- 原单账户自然月统计的 Spring 集成测试。

## Backlog

| ID | 事实 | 级别 | 最晚节点 |
|---|---|---|---|
| `S4-UI-COPY-01` | 统计页把账本净值模块写成「净资产」。ST-04 禁止把它说成完整家庭净资产。本 Gate 不改文案。 | P3 | v1.0 完整 UI 验收前 |

## T07

前置条件已满足：Supervisor PASS，且 P0/P1 = 0。T07 仍须由负责人人工执行。上面的未测清单不能在 UAT 里被当成已经通过。
