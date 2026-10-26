# 04丨记账软件｜S4 功能/Stage 正式结项记录 v0.1

- 日期：2026-10-26
- 结项状态：**CLOSED**
- 不是 v1.0 发布，不是合并 `main`，也不是生产部署。
- 远端是否已同步，以推送完成后的 `origin/release/v1.0` 和 `docs/CURRENT.md` 为准。本文件不把尚未发生的推送写成已经完成。
- 本记录不修改冻结 PRD、UI Freeze、S4 计划原件、V1–V18 或业务源码。

## 批准依据

- 获批计划 `docs/implementation/bookkeeping-v1.0-stage-s4-st-plan-v0.1-approved.md`，SHA-256 `5d014e59de7d5a9a03165720b57abeeeaa1360230d1b424446da210684f20256`。本结项不改该原件。
- Review 原件 SHA-256 `25a9876df494f7c5e7c7a2c6947183ff43b7a2c1a977e6a0e01a216398d49c0c`。批准记录 SHA-256 `c816212020bf9cb17d0f23824dcb4aad04a4ae88478899bfc96b45d62e9172f7`。
- 治理现行文件在仓库外：v1.8 SHA-256 `a8fb22ac207e06be3341826be5690d91b7c7f6d74e81937b26e5a2cb0bd987b0`。本结项已复核该文件，§4.8.3 对既有 `release/v1.0` 的非强制 Push 授权仍在。仓库内原件仍是 v1.6。
- T06 报告 `docs/implementation/bookkeeping-s4-t06-gate-v0.1.md`。本结项不把该报告改写成 T07 或 T08 结论。

## 关闭条件核对

| 条件 | 证据 | 判定 |
|---|---|---|
| T06 Testing PASS | 首轮 FAIL 后，定向修复 `608aca427b53dacd263750fe73081e4c00c68836` 复验 PASS。报告记录后端统计单元测试 12 项、小程序 228 项、typecheck 与 lint 通过 | 达标 |
| T06 Supervisor PASS，当时 P0/P1=0 | 同一报告。P3 = 1，编号 `S4-UI-COPY-01`，不阻断 | 达标 |
| T07 负责人明确 PASS | 负责人 2026-10-26 确认五项人工 UAT 全部 PASS | 达标 |
| 人工期间的 P0/P1 已关闭 | 入口 P1：`133d3cbcf6b6236be293dac1de2c13a930481145`，定向 Testing 与 Supervisor 均为 PASS。标题 P1：见下节，本轮不重复测试 | 达标 |
| 无未关闭 P0/P1 | T06 的符号 P0 与空字段 P1 已在 `608aca4` 关闭。入口与标题两件 P1 已关闭。P3 文案留在 Backlog | 达标 |

## 标题 P1

状态：**关闭**。本结项没有重跑这项测试。

根因是明细路由对标题编码一次，新的解析没有解码，页面显示百分号编码。`980c150c5737227d00578d199d90c816f4e24fc3` 在进入页面前解码一次。证据提交 `c0008bb8f7090d7a3cf0ef71cdb21d92f6de12c1` 记录：小程序测试 230 项通过，typecheck 与 lint 通过；独立 Testing 与 Supervisor 定向复验均为 PASS。负责人已确认分类、标签明细的中文标题修复完成。

## 实际功能

S4 交付账本统计。范围是获批计划的 ST-01～ST-10：所选账户与期间上的历史净值、收入、支出、三级分类与未分类、标签自身加后代去重、筛选，以及沿用这些条件的明细。

账本页工具栏有「统计」，进入现有统计页，系统返回回到账本。这不实施延期中的 UI-NAV-01，也不改冻结主导航。

统计不读取 V18 新列。V18 没有在本 Stage 迁入 `bookkeeping` 或 `bookkeeping_test`。

## 负责人 Human Gate

负责人 2026-10-26 确认 S4-T07 五项全部 PASS，并确认中文标题修复完成。五项按获批计划 T07 的覆盖要求记载：

1. 多账户与归档。
2. 日期筛选。
3. 历史净值，含跨期转账。
4. 三级分类与未分类。
5. 标签父子去重、负或零分母，以及明细继承筛选。

这是负责人的人工结论。它不把 T06 未执行的 MySQL 自动化改写成已经跑过。

## 未测试范围

- 真实 MySQL 上的自动化证明：期初、期末、趋势，未来已保存记录与当前账面余额的差别，转账两侧时间，余额调整，信用账户负数，归档默认排除与显式纳入，永久删除账户，跨用户隔离。
- 会启动 Spring 的后端全量测试。该运行会把 V18 迁入 `bookkeeping_test`，本 Stage 拒绝执行。
- 原单账户自然月统计的 Spring 集成测试。
- 微信真机没有单独记录。
- 既有 8 项 S2-T10 环境守卫。未设置 `S2_T10_JDBC_URL` 时保持跳过。
- GitHub CI。Draft PR 没有检查报告时，零检查不是 CI PASS。

## Git

结项文档提交前的 HEAD 是 `c0008bb8f7090d7a3cf0ef71cdb21d92f6de12c1`。结项文档提交以提交后的 `git rev-parse HEAD` 为准。

S4 本地提交，自 S3 结项之后：

| SHA | 说明 |
|---|---|
| `4018aa38d61f7a2fe87f808478771b4b1edd10d8` | 归档 S4 计划与 V18 隔离证据 |
| `1c18ce9206c1299474574cc41e8ca9a329d133cf` | 账本统计 |
| `608aca427b53dacd263750fe73081e4c00c68836` | 金额符号与省略空字段 |
| `be06303a408ccf09b552595b0906d0082104f1f9` | T06 Gate 报告 |
| `133d3cbcf6b6236be293dac1de2c13a930481145` | 账本页可见统计入口 |
| `d1dd61daba09315abb4ebe07998077b698172d85` | 入口修复的定向复验记录 |
| `980c150c5737227d00578d199d90c816f4e24fc3` | 明细标题解码 |
| `c0008bb8f7090d7a3cf0ef71cdb21d92f6de12c1` | 标题修复的定向复验记录 |

写入本文件时，本地缓存的 `origin/release/v1.0` 仍是 S3 结项 `8012a9ef262d2504c1da46f1bea6ddd25af55ac9`。`origin/main` 仍是 `a393734ad147a5dbe11a83cebfd3381f408db7b2`。

## 下一步

S4 已关闭。不开始下一 Stage 编码，不合并 `main`，不部署生产。P3 文案、主导航和其他未测范围留在 `docs/implementation/v1.0-s4-closeout-backlog-v0.1.md`。
