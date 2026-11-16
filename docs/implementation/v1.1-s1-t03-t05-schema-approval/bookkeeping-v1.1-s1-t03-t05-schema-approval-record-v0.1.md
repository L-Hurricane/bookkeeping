# 04丨记账软件｜v1.1 S1-T03/T05 正式 Schema 专项批准记录 v0.1

- 记录日期：2026-11-05
- 记录状态：**APPROVED / A、B 分别生效；实施受下述条件约束**
- 审批人：项目负责人
- 记录责任人：交付主 Chat（组长）
- 批准对话原文：负责人在组长明确解释「A：M03 转账名称，推荐批准；B：M02 账户分组，推荐附条件批准，须先在隔离合成库完整验证四步 DDL；不改变个人库」并请其回复「批准 A 和 B」后，回复：**「这个同意，没啥问题」**。
- 正式批准对象原件：`bookkeeping-v1.1-s1-t03-t05-formal-schema-approval-review-v0.1.md`。
- 该 Review 原件 SHA-256：`842dfac4cc16d0215b98151cc7936c1bdc5a9c09a4669372e1aefc93227c0c26`。
- 上位 Stage 初版完整 Task Plan 原件 SHA-256：`ddc6aaab074c345239b7967a9d0b2ff2d9b42ed9deff4b3223879998c3ba0603`。
- S1 产品/UI 独立批准记录 SHA-256：`019b9a703dd69170e418f3829bca69d5f68da7bb20c86976fc130d51ff463e05`。
- 本记录只绑定批准事实，不改写 Review、上位原件或已执行的 Flyway。

## 一、批准对象 A｜S1-T03 / M03 转账可选名称

**APPROVED：**Review v0.1 §2 的正式数据库设计与 DDL 候选：在 `transfers` 末尾新增 `name VARCHAR(255) NULL`、`ALGORITHM=INSTANT`，不新增索引，旧行 NULL；以及五字段旧创建哈希 v1 保留，新请求使用含版本前缀与规范化名称的 v2 幂等策略、旧 Key 重放/409 等兼容矩阵。

授权组长在已批准 S1 范围和隔离开发/获准白名单测试环境安排唯一 Coding Agent 实施 T04 相关源码及正式新 Flyway 文件的开发、合成验证和提交。具体数据库环境执行仍受现行批准边界限制：**不授权个人/生产数据库实际 DDL 或真实数据操作**。

## 二、批准对象 B｜S1-T05 / M02 一级账户分组与持久排序

**APPROVED WITH CONDITIONS：**Review v0.1 §3 的最终四步结构 DDL（B1 建表，B2 独立 INSTANT 加两可空列，B3 独立 INPLACE 索引，B4 COPY 新增检查约束和跨用户复合外键），可空 `accounts.display_order` 和 `COALESCE(display_order,id),id` 回退排序，旧账户保持未分组及原有 id 顺序；**没有历史数据 DML 回填**，不改变冻结的财务计算、账户/交易事实。

**硬条件：**在经授权的独立合成 MySQL 8.4.3 + 实际 V18 结构上完整验证 B1–B4 和约束/账户被引用情况，`foreign_key_checks` 保持 ON；特别核查 B4 COPY 对 accounts 写入阻断、磁盘/锁风险和失败中间状态。SQL 或算法与 Review 方案不兼容时 **STOP**，不得自行禁用 FK 检查、换算法、删约束或扩大迁移范围；如需调整获批正式 Schema，另提交修订获负责人批准。不得向个人/生产库执行这些 DDL。

## 三、执行顺序与单 Coding Agent

1. 负责人确认时，v1.0 Cursor 正在修复标签脚本并做合成验证；**在该 Coding Task 完成或合法停止并释放编码队列以前，不能启动 v1.1 的编码**。
2. v1.0 结束后，由交付主 Chat 向 v1.1 Cursor 一次下发 S1-T04、T06、T07（依赖与白名单条件允许时）的串行功能开发包；每个功能小 Commit、定向合成测试、统一回执。
3. 已批准的 S1-T09/T10/T11 集成、独立 Testing/Supervisor 与人工 UAT 仍照原计划执行；批准 Schema ≠ 功能 DEV PASS ≠ 独立 Gate PASS ≠ Stage CLOSED。

## 四、未授权与保持开放的事项

- 个人正式库/生产库真实 Schema DDL、Flyway 运行、真实迁移/回填/覆写、MoneyWiz 新快照再次导入、标签数据修复 DELETE、个人库/真实数据副本创建或删除，均需各自适用的专项批准并遵守 owner 本机执行边界。
- 新远端 Git 分支、PR、Push、Merge main、部署、破坏恢复点、重写已执行 Flyway V1–V18 不获授权。
- v1.0 标签脚本修正仍是单独 Coding 工作；v1.1 M01 最新定向 DEV PASS `4c6e785d2263a35ed0a34dc271e71d572ac6cf9b` 不等于独立 Stage Gate。

## 五、Change Log

- v0.1 / 2026-11-05：依据负责人在本 Chat 的明确同意，独立绑定 A 批准与 B 附条件批准；限定实施环境、合成 DDL 验证、唯一 Coding Agent 顺序及所有高风险操作保留边界。原 Review 文本与 SHA 不变。
