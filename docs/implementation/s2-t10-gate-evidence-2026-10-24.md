# S2-T10 / M2-AUTO-GATE 证据链

- 状态：组长 2026-10-24 裁断 **PASS**。未解决 P0/P1/P2 为 0。
- 已审核代码：`bf7074a15a1e7e2b14b34e3d1ffeef3d48e9b35b`（`task/s2-t00-t01`）。
- 已批准计划 SHA-256：`69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5`。本记录不修改该计划。
- 治理：本机 Downloads 的 v1.7 SHA-256 `4fcf410092429fa97aa21baaddc91b027fa0d006cee08af59e46b49bac4c56c1`。仓库内治理原件仍是 v1.6。本记录不把 v1.7 抄入仓库，也不表示 ChatGPT Project 已同步。
- 证据原件留在仓库外，本文件只做索引。不覆盖历史报告。

## 同一门禁的三轮

| 轮次 | 当时 HEAD | Testing | Supervisor | 组长当时之后的处理 |
|---|---|---|---|---|
| 首轮 `s2-t10-m2-auto-gate` | `ec3dc2c09a60c8588830d881579254aa106baf7e` | BLOCKED | FAIL。P0=0，P1=1，P2=0，P3=1 | 不进入 T11。随后 T09R 修可重复读 |
| ENV-01 `s2-t10-env01` | `70b38faa680a6459c971db241b25b0991d2a745b` | 隔离库测试完成，明确不宣告 Gate PASS | BLOCKED。P0=0，P1=0，P2=1，P3=0 | P2：MySQL 上未调用 `execute` |
| T10R `s2-t10r-execute` | `bf7074a15a1e7e2b14b34e3d1ffeef3d48e9b35b` | 定向 5 项 PASS | 原 P2 关闭。P0=0，P1=0，P2=0，P3=1 | 组长裁断 T10 PASS，授权 T11 |

根目录：`/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite/evidence/`。

## SHA-256

首轮 `SHA256SUMS` 已按文件重算，16 项全部一致。该清单文件本身的 SHA-256 是 `4351030c2fa051fe04d775deee22b4bc2a5d27174b3efaad4abb9ec9522379ea`。

| 路径 | SHA-256 |
|---|---|
| `s2-t10-m2-auto-gate/TESTING.md` | `979d72244122e57be80a73b36d06ed88b61da3a22e208474844815ee9d3be2f4` |
| `s2-t10-m2-auto-gate/SUPERVISOR.md` | `0b509f44a84acaa71de2867361faa198666559ad16bb8e1c145b8d6edde30a69` |
| `s2-t10-env01/TESTING.md` | `e35a3330e77f26e50effaac89e59ea932636c8669b3b00a63d81bf30d2c671a8` |
| `s2-t10-env01/SUPERVISOR.md` | `f0f8a32b01655ecef656cc14385094a7f271ffc3cd3cdca552b9301623b38f1f` |
| `s2-t10-env01/pre-v16.sql` | `eb1a81905e2a1c196db7bfd6ee6d3d7cbb880ca8ea41b4ee46bf4132fb33fe9e` |
| `s2-t10r-execute/TESTING.md` | `6212ac091c61723aa9b2231d8a3e8cac4e61baadbd7f03670e66e08dac744ef0` |
| `s2-t10r-execute/SUPERVISOR.md` | `929b322f2a15030ba29f4dd1927212f1e01eff3b71e3ef851fc12cc4a2a14c74` |
| `s2-t10r-execute/before.txt` | `c4de2ecd73811d7838fbdc00c36181b30e49c25872979e63c7cb5bfb76945dfa` |
| `s2-t10r-execute/after.txt` | `6838f56a2f5ce15db38a3b27f64acda112cf027e77f0c84c64cf50d88433a470` |
| `s2-t10r-execute/maven-account-permanent-deletion-execute.log` | `cdce8b4021a1ca6b852709331a3cb0fb3c5e02adc9a60d236a316b95c25a1a41` |

## 已执行且本轮不重跑

| 轮次 | 结果 | 退出码 |
|---|---|---|
| 首轮无库 | 96 项失败 0；另有 `MoneyTest` 18 项失败 0。合计报告中的无库 114 | 0 |
| 首轮前端 | `npm test` 207 通过；typecheck 与 lint 通过 | 0 |
| ENV-01 并发 SQL | `AccountPermanentDeletionConcurrencyTest` 3 项通过。未调用 `execute` | 0 |
| ENV-01 迁移库 Spring | 100 项失败 0。含转账 5、归档 3，以及流水、调整、身份、分类、多账户、标签 | 0 |
| ENV-01 无库 | 122 项，跳过 3，失败 0。含永久删除单测 18 | 0 |
| ENV-01 前端 | 207 通过；typecheck 与 lint 通过 | 0 |
| T10R | `AccountPermanentDeletionExecuteIntegrationTest` 5 项失败 0。真实 `execute` | 0 |

ENV-01 写入范围只有 `127.0.0.1:3306/bookkeeping_migration_test`。演练用户 `db01_drill` 在 T10R 前后仍是 1 个用户、1 个账户、0 笔转账，Flyway 16 成功。

## 未测范围

- 微信开发者工具和真机。本记录不把它写成 UAT PASS。
- 真实月度分析模型。
- 开发库 `bookkeeping` 的十分钟恢复。T11 只读看到它仍是 Flyway 11，`transfers` 不存在。本轮没有迁移它。
- `MigrationIntegrationTest`。它会 `flyway.clean()`，继续禁止。
- `BootstrapIntegrationTest` 以及会写入 `bookkeeping_test` 的守卫测试。
- GitHub CI。首轮已说明不能把零检查当成 PASS。
- 真实 InnoDB `RESTRICT` 外键错误。T10R 用测试拦截器在 `DELETE FROM transactions` 上抛出完整性异常，证明同一事务回滚。这是残留 P3，不是已修复项。

## 仍开放的 Backlog

- **P3**：模拟删除故障证明了回滚，没有直接制造 InnoDB `RESTRICT`。非阻断，未关闭。
- **S1-T08-OBS-01 / UI-NAV-01**：账户列表仍没有「账本 / 统计 / 我的」主导航。最晚完整 v1.0 产品/UI 人工验收前修复。S2 没有修它。原记录是 `docs/implementation/bookkeeping-s1-stage-closeout-v0.1.md`。
- S1 结项保留的其余历史 P2/P3 覆盖边界不因 T10 PASS 消失。
