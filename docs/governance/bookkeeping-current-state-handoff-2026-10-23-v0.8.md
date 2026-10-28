# 04丨记账软件｜现行项目状态与跨 Chat 交接记录 v0.8 · 唯一现行入口

- **事实截点：2026-10-23**。本轮新增：负责人明确批准 S2 首版完整 Stage/Task Plan Review v0.1（原字节 SHA-256 `629dd55f3f891b8ae10faebd87f40a55035f91c311719467dc3dc20b4617f9db`），并要求 **M1 结束后暂停、汇报、简短复盘、获负责人明确确认才能开始 M2**。
- **当前状态**：v1.0 / **S1 CLOSED**（OBS-01 已批准延期）；**S2 初版计划 APPROVED、尚无 Coding/Test 执行成功回执**；当前下一项是向 Cursor 传达已获批实施指令，启动 S2-T00 A+B-lite 与 S2-T01 只读 V15 设计。**DB-01/DB-02/条件性 DB-03 的具体正式 DDL 仍待专项批准，不得先实施**。
- **文件性质**：动态事实记录，不新增授权；本版替代 v0.7，历史原件/SHA 保留。当前 GitHub / Mac 实时状态以 Cursor 最近回执为主，不能冒充本 Chat 直接核验。

## 1. 现行依据（新 Chat 固定读取顺序）

1. 正式治理：`bookkeeping-ai-collaboration-governance-v1.6.md`（Approved），对应 Project Instructions v1.6。**v1.5 只是历史版本**；本轮 Cursor 只读调查仍使用本机旧 v1.5/v0.5，下一次任务必须补齐 v1.6 和新计划可读性。
2. **本文件 v0.8**：唯一当前状态；批准索引 `bookkeeping-2026-10-23-approval-index-v0.5.md`。
3. **S2 初版完整计划**：`bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md`（Approved v0.1，原获批 Review SHA `629dd55f3f891b8ae10faebd87f40a55035f91c311719467dc3dc20b4617f9db`）；范围/Gate `bookkeeping-s2-two-milestones-scope-v0.1-approved.md`（Approved）。计划内部合法修订须版本、Diff、SHA、组长决策记录。
4. 产品与设计：`bookkeeping-v1.0-product-baseline-v1.0.md`（Approved）、`bookkeeping-v1.0-ui-freeze-v1.0.md`（UI Freeze）、已批准相关架构/领域决定。
5. 前阶段：`bookkeeping-v1.0-stage-s1-plan-v0.5-approved.md`、`bookkeeping-s1-stage-closeout-v0.1.md`、S1 独立门禁证据 `s1-t07r-independent-gates-evidence.tar.gz`（SHA `3fee9cdb5f91fab60f6eaab663ce5b0bf0ab4b0c37f31fe5750678b5100f79b7`）。
6. 当前 Cursor 调查结论（负责人在 Chat 中已转交）：S2 代码基线、DB-01/02 技术风险、A+B 脚本复用与门禁建议。调查报告为事实证据，不是已批准 Task Plan 本身。

**注意**：Git docs、Project 共享资料与负责人 Mac 不是自动同步。新 Chat 应主动搜索项目文件/查 SHA；不知道本机文件状态时不能假称 Cursor 已读到 v1.6。

## 2. 最近 Gate、业务范围与待完成事项

- S1：T07R Review PASS、T06 Testing PASS（后端 259 项，2 Skipped；前端 188 项、typecheck/lint PASS）、T07 Supervisor PASS；负责人完成 S1 人工验收并批准 G5 单一延期例外，S1 CLOSED。不能把排除项误写成已通过。
- S1 遗留 `S1-T08-OBS-01` / `UI-NAV-01`：主导航“账本/统计/我的”未按 UI Freeze 可达，最晚 v1.0 最终 UI Gate 修复；**不擅自纳入 S2**。原 T07 P2=5 / P3=3 与未测项仍在 Backlog。
- **S2-M1（已批准）**：账户归档、历史只读、恢复、状态写入限制 + 真实 A+B-lite 工具试点。T00～T04；M1 自动化 Testing PASS + 独立 Supervisor PASS；无强制微信 UAT。**自动 Gate 后必须 STOP，给负责人完整复盘材料，待负责人明确确认后继续 M2。**
- **S2-M2（已批准、等待 M1 确认）**：转账 CRUD、双时间和双账户流水/余额；归档账户永久删除预览、关联整笔转账删除、并发重验和事务原子性。T05～T11；全量独立自动 Gate + 负责人微信开发者工具功能与 A+B 流程双维 UAT。
- A+B-lite：一个最小 Task 启动入口 + 一个只读证据打包器；不得新建 Orchestrator 平台、自动外部消息通道或绕过人工/独立门禁。T00 若需要大改 SDK，应 STOP A+B 并转旧脚本继续业务，不让基础设施阻断交付。

## 3. 当前 Agent 指令队列与负责方

| 状态 | 工作项 | 下一责任人 | 完成条件 |
|---|---|---|---|
| 已完成调查 | S2 只读技术核查，基线 `663e76d...`、缺口、A+B 候选 | Cursor 报告已返回；ChatGPT 已 Review | 成果已经吸收到获批 S2 初版计划；不得重复调查 |
| **待启动（已授权）** | **S2-T00 A+B-lite 工具实现**（普通可恢复脚本，不碰业务 DB） | ChatGPT 发完整任务指令，负责人在 Cursor 低频接力，唯一 Coding Agent 执行 | 脚本及测试/失败回退证据返回；独立 Reviewer 与同一编码会话隔离 |
| **待提交审批设计** | **S2-T01 V15 accounts 状态 Schema 完整方案** | ChatGPT 组织 Cursor 只读编制 SQL 草案、锁/兼容/回退材料；负责人批准正式 DDL | 完整 DDL 与迁移说明提交负责人；**当前不批准执行 SQL** |
| 阻断至专项审批 | S2-T02 开发正式账户状态持久化、DB-01 迁移 | 等 DB-01 批准后唯一 Coding Agent 执行 | 先批准 SQL 和可恢复演练，再编码/数据库迁移 |
| **未来强制暂停** | **S2-T04 / M1 自动 Gate** | 独立 Testing + Supervisor → ChatGPT 组长 → 负责人管理确认 | 证据全部 PASS + 一页交付/流程复盘 + 负责人明确继续许可；**未确认不得启动 M2** |
| 等待 M1 确认 | S2-T05～T11 M2 工作及最终 UAT | ChatGPT / Cursor / 独立 Agents / 负责人 UAT | 完整 M2 PASS+人工 Gate，不能提前合并 main |
| 待补证据 | Draft PR #2 无 CI、历史 Git Data API 对象发布及凭据/远端完整性复核 | 后续安排有界独立只读核验 | 审核证据；不以已有 SHA 相同推断全部检查完成 |

## 4. Git 与安全边界

- 仓库 `L-Hurricane/bookkeeping`；Cursor 报告 `release/v1.0` 与 `docs/s1-approved-archive` 为同一 HEAD `663e76d4b1e1ac6a7979211299fd7225dfb3236d`；PR #2 `https://github.com/L-Hurricane/bookkeeping/pull/2` Draft，目标 `main` HEAD `a393734ad147a5dbe11a83cebfd3381f408db7b2`，PR 无 CI 检查；未合并。远端对象内容未由本 Chat 实时验证。
- 负责人日常工程 `/Users/limingyuan/Documents/code/personal/bookkeeping` 在旧 `release/v0.3`；POC-2 工作区有 10 个未提交修改；UAT 分支 `uat/v1.0-s1` 停在 `5ca8216...`。**不得将这三者当作 S2 的干净基线，不得覆盖/清理其脏文件**。
- S2 从核验过的 `release/v1.0` 建立授权隔离工作区及任务分支；一个 Coding Agent 串行，Testing/Supervisor 独立。普通本地 Git 可恢复操作由组长组织。
- **常设有界 Git 权限**：已批准 Stage 和必要 Gate 通过后，可对现有 `release/v1.0` 进行非强制 Push，更新原 Draft PR #2 并事后通知；但不得新建 PR/远端共享分支、强推、删除远端、改分支规则、跳过审核。过去 GitHub API 按原 SHA 重建对象系未获批准的例外路径，**不可默认为下一阶段通用方案**。远端异常时先只读诊断并 STOP。
- 仅负责人通过 GitHub 网页端执行 `main` 最终 Merge；完整 v1.0 前不考虑提前 Merge。不得执行生产/真实个人数据、外部越权写入与本地临时目录删除。
- **数据库**：正式 Schema/索引/约束和额外已有数据迁移须具体 SQL 专项事前审批；已核对的独立非真实开发库受恢复验证及目标 RTO ≤10 分钟约束；测试库白名单更窄，生产库逐项授权；未完成 DB-01 前不得执行 V15 等正式结构变化。

## 5. S2 M1 效率评估与下一次负责人参与

- 在 T00 及 M1 各任务记录：负责人实际脚本/消息启动数、上传/回传数、累计主动分钟、AI 轮次/自然耗时、失败重试原因、自动证据包 SHA 与人工审核次数；以 S1 观察过的同类操作为参考，S1 无完整计时，不捏造节省百分比。
- M1 结束提供一页报告：**交付/测试/遗留风险、A+B 是否节省人工、建议保留或回退工具、是否适合在 M2 使用**。
- 负责人下一次必须介入可能是 DB-01 正式 DDL 审批；M1 Gate PASS 后必须进行独立于功能 UAT 的简短管理确认；只在触及保留权限或危险异常时额外请示。

## 6. 文档与跨 Chat 接续

- 当前 Project 共享资料应将本 v0.8 替换 v0.7、审批索引 v0.5 替换 v0.4，新增正式 S2 首版计划 v0.1。现行治理 v1.6、Project Instructions v1.6、S2 M1/M2 范围、PRD/UI Freeze 和 S1 结项继续有效。
- 获批 Review 原件原字节/SHA 保留；正式版/批准索引分列，Project 文件与 Git `docs/` 归档需真实 Agent 完成并分别验证；本次形成文件不代表已 Push。
- 六项接任核对：①职责权限 ②Stage/Task/Gate/Backlog ③批准事实与执行终态 ④Git/PR/源码/证据时点 ⑤下一责任人/完成条件 ⑥越界禁止事项。资料齐全时 HANDOFF_READY；缺失不阻断可安全工作的事项时 HANDOFF_PARTIAL；必要关键依据缺失时 BLOCKED。

## Change Log

| 版本 | 日期 | 内容 | 状态 |
|---|---|---|---|
| v0.7 | 2026-10-23 | S2 双子里程碑/范围已获批准，S2 初版 Plan 待批 | 历史，原件保留 |
| **v0.8** | **2026-10-23** | 记录 S2 初版完整 Plan 通过、M1 自动 Gate 后必须暂停复盘、T00/T01 获授权待启动、数据库专项审批待办、当前职责和安全边界 | **唯一现行状态 / 不是新增授权** |
