S2-T09R
日期: 2026-10-24
状态: 组长已批准的 M2 / AC-09 范围内必要修复。未改获批计划原件。未改产品、Schema 或人工 Gate。本记录不宣告 T10 PASS。

授权依据:
- 获批计划 docs/implementation/bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md SHA-256 69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5。字节未改。
- 治理 v1.7 文件 SHA-256 4fcf410092429fa97aa21baaddc91b027fa0d006cee08af59e46b49bac4c56c1。里程碑内必要修复由组长记录版本、Diff、SHA 与原因，不削减 Gate。
- 组长 2026-10-24 在 S2-T09R 任务中确认 T10 FAIL，接受独立 Supervisor 的 P1，并直接批准该缺陷的范围内修复。
- 父提交 ec3dc2c09a60c8588830d881579254aa106baf7e。
- V16 文件 SHA-256 24c286fc5ef44f4581c0e21c35db4c54e02427f2b1d49f276a399e2ee81c9f9f。字节未改。

相对获批计划的 Diff:
- 不改 T10、T11、AC-09、DB-03 删除范围或人工 Gate。
- 永久删除执行改为：加锁前的普通查询只选择账户锁集合；加锁后的复核和删除依据改为当前读，并只按读到的 id 删除。
- 入站异常引用把 `account_id IS NULL` 写成明确条件。该行拒绝删除，不进入删除集合。

源码 Diff SHA-256: b3caf5b7cd5060e9ba3a158489f47d83006cb4924160415a4687a055a26cf790
计算范围是相对父提交的 backend 源码 diff，加上当时尚未跟踪的 AccountPermanentDeletionConcurrencyTest.java。不含本记录。

验证:
- 无数据库回归 119 项，失败 0，退出码 0。其中 AccountPermanentDeletionTest 18 项。
- AccountPermanentDeletionConcurrencyTest 已写好并保持禁用。本轮没有执行，也没有连接数据库或运行 Flyway。
