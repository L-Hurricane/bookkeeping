# 04丨记账软件｜v1.1 D01 MoneyWiz 完整重导范围 · Review v0.1

- 日期：2026-11-06
- 状态：**REVIEW / NOT APPROVED**
- 本文件不修改四份已批准原件，也不授权连接个人库、读取真实 CSV、清库、Flyway 或导入。
- 准备记录 `docs/implementation/v1.1-s1-d01-execution-prep-v0.1.md` 仍保留。本 Review 是其后的范围候选。

## 已核对的原件

ZIP `~/Downloads/bookkeeping-d01-lt-t05-approval-evidence-2026-11-06.zip`，SHA-256 `d9ab6fa83ca641d6aecbacbdc009a11368dcabed7a70dabf9db08a684393f008`。内部四份与 `SHA256SUMS.txt` 一致，字节未改：

| 原件 | SHA-256 |
|---|---|
| `bookkeeping-lt-t05-personal-migration-task-plan-review-v0.1.md` | `3c249adb094519398a04c810b103b7e40a9519ed6ee2bdffac8219110cf55654` |
| `bookkeeping-lt-t05-task-plan-approval-record-v0.1.md` | `715d0e7813ad8096222509c39cb6e3e846a6cd08099d2f24de4e26b4b17ee355` |
| `bookkeeping-lt-t05-local-private-uat-fastlane-review-v0.2.md` | `0ecdfea0bf1837eb581680a439f9ac2c7db4cd40835be6c8158b9fb81aaf6c9b` |
| `bookkeeping-lt-t05-local-private-uat-fastlane-approval-record-v0.1.md` | `3ed8044a61741593fe970a1758b041b225ec0f718f3daac4847fb84efc40e245` |

上位计划 `LOCALTRIAL-01-STAGE-TASK-PLAN-v0.4-approved.md` 在 LocalTrial 工作区，SHA-256 `57fa3e3734787edfb93e1899e56eb614dfe2675abea6398f55669113bfb4d244`，与批准记录一致。v1.1 工作区里的 v0.3 是前版，SHA-256 `f86b5335541e267fbead4f9bcbb9a0f4313d686fb4c17adfda3a7306843c9d74`，不覆盖 v0.4。

已生效的范围是：T05-01～T05-03 的隔离研发和合成演练；对**具名且全库可舍弃**的本机 UAT 目标，豁免每次导入前的离盘加密备份。真实查询、清库、DDL 和写入都还要另批。凭据隔离不可豁免。Cursor 不读取真实 CSV、映射或个人库。

## 已批准的快照，不是一张新的全量导出

已绑定的历史 CSV SHA-256 是 `9d17de976e557e451331dfb53af5dad3eaac61e2e81f90425eed518eb031e7a1`。来源逻辑记录 11,608。其中普通交易 9,632，转账 986 笔、两侧 1,972 行，`AMBIGUOUS_TRANSFER` 4 条排除后手工补。25 个来源账户，`a01`–`a25` 只是私有别名。最近约一周有意不在这份 CSV 里。

这就是当前唯一批准的完整账户快照。本轮没有打开该文件。若要换成更新的导出，必须另批新的 SHA、行数和截止时间。不能把「完整重导」理解成自动采用一份未见过的新文件。

## 后补手工交易

批准计划把最近一周和 4 条歧义行留在导入之后由负责人补录，并单独对账。v0.4 记录个人库曾以空库完成 V1–V18。其后的实际内容本轮未知，因为没有连接。

重建前只能由负责人选定一种，不能由执行者默认：

1. 目标库在截止日之后没有要保留的手工账，整库按已批准快照重建。
2. 有要保留的手工账。先由负责人本机导出清单，重建后只补这些记录，不把旧流水叠回。

含有需保留记录的库，按 fastlane v0.2 排除在「全库可舍弃」之外。

## 可复用的金额符号

`e84974712227385d82d47a5d1fa17721a8b2d472` 的一次性规则继续用于这批快照：有分类时收入保留源金额、支出取反；无分类历史行按源符号记成正支出或正收入。不把这条例外写成一般记账规则。批准记录里的合成演练是 `bk_lt01_t05_drill_20261102` 上 5 笔交易和 1 笔转账，不等于这份 11,608 行已经入账。

## 转账描述

`MoneyWizControlledImport.insertTransfer` 仍不写 `transfers.name`。历史「描述」不会进入账本标题。最小适配是：在已有 V19 的库上，把两侧选定的非空描述写入 `name`；空描述保持 NULL，不写入「转账」。这不是增量同步。它还没有编码授权。LocalTrial 分支目前没有 V19。

## 分组和排序

CSV 表头没有分组。分组和组内顺序不能从这份快照重建。两条路都要负责人选定：

1. 舍弃。重建后的账户全部未分组，顺序回到 `COALESCE(display_order, id), id`。
2. 另行提供一份只含账户名、组名和顺序的清单，导入后贴回。清单不在本 Review 里，也不能从 CSV 发明。

## 个人库 V19/V20

V19、V20 已在 v1.1 获批，但批准写明不向个人库执行。个人库若仍停在 V18，写入 `transfers.name` 或分组列之前，要对具名库单独批准这两支现成迁移。不新写 SQL。V19 SHA-256 `962e9d9960b71576ae73c4dfbf984f84e730033dfaa143cd0ec7282d48d30218`。V20 SHA-256 `d045172081fd87dd854c5461d784b778b9bfa64f46be19753277deb8a8064691`。fastlane 的备份例外不包含这次 DDL。

## 演练、身份和核对

下一次获准编码后，最短路径是：

1. 在隔离合成库升到 V1–V20，用虚构夹具走通空库、转账名称、未分组、重复批次拒绝和失败回滚。
2. 固定脚本只接受点名的库、用户和 CSV SHA。身份不符、行数不是 11,608、账户不在 25 个映射内、或出现不可解释的余额差，立即 STOP。
3. 每个账户用当前余额公式核对：期初 + 收入 − 支出 + 余额调整 + 转入 − 转出。负收入和负支出保持原类型。不用余额调整把差额抹平。
4. 真实文件、映射和凭据只留在负责人 Mac。Cursor 只接收去敏计数。

已有合成演练没有覆盖 V19/V20 和转账名称，不能当作这次重导的通过证据。

## 需要负责人批准的事项

本 Review 自身尚未批准。批准它只表示接受上述范围，仍然不授权真实操作。其后还要分别批准：

1. 是否就用 CSV `9d17de97…`。若换新快照，同时给出新 SHA、行数和截止日。
2. 后补手工账是舍弃，还是先导出再补。
3. 分组和排序是舍弃，还是另附清单。
4. 对哪一个具名可舍弃库执行现成 V19、V20。
5. 只读身份核查：库名、用户、Flyway 版本、是否全库可舍弃。`bookkeeping`、`bookkeeping_test`、`bookkeeping_migration_test` 不是候选。
6. 转账名称最小适配的编码范围：只改 LocalTrial 导入，不改 V1–V20 字节，不建设增量平台。
7. 负责人本机执行的一次清空和导入。脚本 SHA、25 个账户映射和 STOP 条件齐备之后才申请。备份例外只免除离盘加密备份，不代替这次操作批准。

## Change Log

| 版本 | 日期 | 状态 |
|---|---|---|
| Review v0.1 | 2026-11-06 | REVIEW / NOT APPROVED。把已批准的 25 账户历史快照、fastlane 可舍弃例外，和尚未授权的转账名称、分组、个人库 V19/V20 分开 |
