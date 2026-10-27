# 04丨记账软件｜S5 功能/Stage 正式结项记录 v0.1

- 日期：2026-10-27
- 结项状态：**S5 CLOSED — HUMAN UAT DEFERRED TO v1.0 FINAL GATE**
- 这不是 S5 人工 UAT PASS，不是 v1.0 发布，不是合并 `main`，也不是生产部署。
- 远端是否已同步，以推送完成后的 `origin/release/v1.0` 和 `docs/CURRENT.md` 为准。本文件不把尚未发生的推送写成已经完成。
- 本记录不修改冻结 PRD、UI Freeze、已归档计划原件、V1–V18 或业务源码。

## 批准依据

- 现行计划 `docs/implementation/bookkeeping-v1.0-stage-s5-ai-plan-v0.2-approved.md`，SHA-256 `c7add0aefce153441ce4d22c0907ec549b362eaedafd713df37cfe33a816f081`。
- 人工 Gate 修订 Review `docs/implementation/history/bookkeeping-v1.0-s5-human-gate-adjustment-review-v0.2.md`，SHA-256 `6b31c3cd371ecf66b9a13599aa98149806a3d275cfeba27353519aaa68b4c870`。正文仍是 Pending，不回写。
- 独立批准记录 `docs/implementation/bookkeeping-s5-stage-plan-amendment-approval-record-v0.2.md`，SHA-256 `7f403b8906311e92aa0cdcac4a30bbfc36fba26605f41ac94bcb8273f6e22914`。
- 初版计划 `docs/implementation/bookkeeping-v1.0-stage-s5-ai-plan-v0.1-approved.md`，SHA-256 `1958595f6ff480273fb460d3e70245167f8aff6adf7d19cd7332ef1708ecf964`。原字节保留。AI-01～AI-07 与方案 B 继续有效。
- 初版 Review 与批准记录原字节保留。SHA-256 分别是 `7b8663797e0bd5d68c73a4fd04eff97208fd8cb724cb5fa1c7a734dd3c389cba`、`af7dda910da54b97fecf9331dac8960f8ded52f2ef0d200099f66af4b12db6ba`。
- 治理现行文件在仓库外：v1.8 SHA-256 `a8fb22ac207e06be3341826be5690d91b7c7f6d74e81937b26e5a2cb0bd987b0`。本结项已复核该文件。§4.8.3 对既有 `release/v1.0` 的非强制 Push 授权仍在。仓库内原件仍是 v1.6。

## 关闭条件核对

| 条件 | 证据 | 判定 |
|---|---|---|
| T06 Testing PASS | `docs/implementation/bookkeeping-s5-t06-gate-v0.1.md`，SHA-256 `7ebb394c4c7d3018a4f9b5855d8d418767be200e8a2a392bc665b90a7cc3269c`。本结项不重跑 | 达标 |
| T06 Supervisor PASS，P0/P1=0 | 同一报告。P3 = 1，编号 `S5T06-D1`，不阻断 | 达标 |
| T07 负责人阶段放行 | 2026-10-26 负责人确认修订 Review v0.2。这是简化阶段放行，不是人工功能 UAT | 达标 |
| 无未关闭 P0/P1 | T06 之后到本结项，`d3d785633bc5f6e06673692a2c4d64e17d748017` 之后没有业务源码改动 | 达标 |
| 无未经验证的产品改动 | `d3d7856..a38a186` 只增加隔离门禁测试、小程序门禁测试、T06 报告和一行状态。本结项只归档文档 | 达标 |

## 实际功能

S5 交付全账本 AI 月报和年报。范围是自然月 `YYYY-MM` 与自然年 `YYYY`，包含仍存在的正常账户和归档账户。数字来自已有统计。模型只解释汇总。同一用户每天最多 3 次外部生成，与旧的单账户分析共用。查看历史不占次数。

永久删除任一账户后，该用户已保存的全账本月报和年报不再可读。已消耗次数不退回。不提供已永久删除账户的恢复。其他账户的旧单账户报告和 `LEGACY` 行保留。`account_id` 为空不会被当成全账本。

## 阶段放行

负责人 2026-10-26 确认修订文件。S5 不单独做人工功能 UAT。真实模型效果、微信真机和报告使用体验记为 `S5-UAT-DEFER-01`，留到 v1.0 全部业务功能完成后的统一人工 Gate。那个 Gate 的计划尚未批准。本状态不得写成 S5 人工 UAT PASS。

## 未测试范围

- 真实外部模型调用，以及模型返回带金额句子时的校验路径。
- 微信真机。
- HTTP MockMvc、会话过滤器和 Jackson 响应体。T06 通过生产服务和 JDBC 在 `bookkeeping_migration_test` 上执行持久化。
- 完整 `./mvnw test`。该运行会把 V18 迁入 `bookkeeping_test`，本 Stage 拒绝执行。
- V18 没有迁入 `bookkeeping` 或 `bookkeeping_test`。
- GitHub CI。Draft PR 没有检查报告时，零检查不是 CI PASS。

## Git

结项文档提交前的 HEAD 是 `a38a186af89d541ee9d79bb3fc4b4116795b78f8`。受审产品代码是 `d3d785633bc5f6e06673692a2c4d64e17d748017`。

S5 本地提交，自 S4 结项之后：

| SHA | 说明 |
|---|---|
| `3af675715665b3ffb877eafb634c952c54c5b7a4` | 归档 S5 v0.1 计划与安全起点 |
| `d3d785633bc5f6e06673692a2c4d64e17d748017` | 全账本月报和年报，含方案 B |
| `a38a186af89d541ee9d79bb3fc4b4116795b78f8` | T06 Gate 与隔离库证据测试 |

写入本文件时，本地缓存的 `origin/release/v1.0` 仍是 S4 结项 `f6908c8e3c91f9a1ef8396f4416e77ed5555ec77`。`origin/main` 仍是 `a393734ad147a5dbe11a83cebfd3381f408db7b2`。

## 下一步

S5 已按上述状态关闭。不开始下一 Stage 编码，不合并 `main`，不部署生产。真实 AI 体验、`S5T06-D1`、统计体验、导航和文案遗留留在 `docs/implementation/v1.0-s5-closeout-backlog-v0.1.md`。
