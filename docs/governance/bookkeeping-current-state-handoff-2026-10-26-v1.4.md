# 04丨记账软件｜唯一现行项目状态与跨 Chat 交接记录 v1.4 · Project 替换候选

- 日期：2026-10-26；状态：**Project 替换候选 / NOT YET SYNCED**。负责人已说明 v1.3 和 S5 Review 已放入 Project；本 v1.4 在 S5 正式批准后替代 v1.3。**只有实际替换后**才能称其为 Project 唯一现行入口；不得保留两份均称「当前」。
- 证据来源：负责人在本 Chat 连续转达的 Cursor S4-T06/T07/T08 回执及明确人工 UAT 结论，以及已批准治理/PRD/UI/计划；**本会话未直接连接 Mac Cursor、当前 GitHub、最新 `docs/CURRENT.md` 或读取 S4 CLOSEOUT 原字节**，以下代码/Git 结论保留“据回执”性质。
- 现行治理：正式 v1.8（负责人转达 Git 原件 SHA-256 `a8fb22ac207e06be3341826be5690d91b7c7f6d74e81937b26e5a2cb0bd987b0`；Project 所附 v1.8 与此 SHA 可能不同，按获批独立记录与正式原件链核对，不自行变更授权）；FOCUS-01～08，有界非强制 Push 仅对既有 `release/v1.0`，main 网页 Merge 由负责人执行。

## 1. Stage / Gate 当前终态

| 项目 | 状态 | 证据 / 界限 |
|---|---|---|
| S1 | CLOSED | 历史正式 S1 CLOSEOUT |
| S2 | CLOSED | 历史正式 S2 CLOSEOUT、负责人 UAT |
| S3 | CLOSED | `docs/implementation/bookkeeping-s3-stage-closeout-v0.1.md`，据 Cursor SHA `a994388ea67e8d04b21bdfa6d5b2228790a50ec4937ed670de27f831ce793203` |
| 统一 DB 设计 | Schema v0.3 APPROVED | Review SHA `7844121057e2d14504c9ca21baf82d8b83fff8a9638b2bb7cd56e8c5525b47ae`；V18 隔离库演练 PASS，正式迁移文件 SHA `9ddb179ef52bcc8518e6b3b620b259c812205262fd264988e0e15f95f4151005`；没有授权在日常/共享/生产库执行 |
| S4 / ST-01～10 | **CLOSED** | S4-T06 独立 Testing 复验 PASS、Supervisor PASS；T07 负责人五项实际 UAT PASS；入口与中文标题 P1 均已闭环；T08 文档／Backlog／Git 收敛，P0/P1=0 |
| S5 / AI-01～07 | **APPROVED；T00 待启动，Coding 尚无执行回执** | 负责人 2026-10-26 同轮批准完整 Stage/Task Plan T00～T08 以及账户永久删除后 LEDGER 历史月/年报告不可读的方案 B。Review SHA `7b8663797e0bd5d68c73a4fd04eff97208fd8cb724cb5fa1c7a734dd3c389cba`；Approved SHA `1958595f6ff480273fb460d3e70245167f8aff6adf7d19cd7332ef1708ecf964`；独立批准记录 SHA `af7dda910da54b97fecf9331dac8960f8ded52f2ef0d200099f66af4b12db6ba`。不代表 T00 已执行，T06/T07 Gate 保留 |

## 2. S4 最新交付与 Git（据 Cursor 2026-10-26 回执）

- 完整统计：月/季/年/自定义日期、正常/全部/自选账户、历史净值/转账双时间、分类三级汇总和未分类、标签子树去重/明细、统计入口与明细标题解码、金额正红负绿。
- T06：后端定向单测 12 PASS；前端小程序 228 PASS，typecheck/lint PASS；后续标题修复的定向证据：前端 230 PASS、独立 Testing 与 Supervisor PASS（不将两次数字机械合并）。
- 最终本地 HEAD 与远端 `origin/release/v1.0`：`f6908c8e3c91f9a1ef8396f4416e77ed5555ec77`；现有 Draft PR #2 为 Open / Draft (`https://github.com/L-Hurricane/bookkeeping/pull/2`)，head 同步；`origin/main` 为 `a393734ad147a5dbe11a83cebfd3381f408db7b2`。**无 CI PASS 证据**；未 Merge main，未生产部署。
- 本次 CLOSEOUT：`docs/implementation/bookkeeping-s4-stage-closeout-v0.1.md` SHA `6c0beab70aaed1c114619b99d78538e98c6d9b5bcb75eb02ee04f9c518ddc3a7`；Backlog `docs/implementation/v1.0-s4-closeout-backlog-v0.1.md` SHA `0bc77fb244e1a3c9a03d0026a05f67e38cf9b2a249ff07d5b76a7c52fd6aaeb9`；审批索引 `docs/governance/bookkeeping-2026-10-26-approval-index-v0.8.md` SHA `a06b91c45b2de870d6f9eb95a2197faaf5220d55285546a8bb4abdefefe9040a`。
- 保留未测：真实 MySQL 下历史净值/转账双时间/调整/信用/归档/跨用户隔离、微信真机与会启动 Spring 的全量测试；**不能伪称这些已验证**。V18 没有进入 `bookkeeping`、`bookkeeping_test`。

## 3. Backlog 与新发现

- 原正式 Backlog：`S4-UI-COPY-01` P3（「净资产」应为「账本净值」）；`UI-NAV-01/S1-T08-OBS-01`（三主导航）；`S3-UI-ENTRY-01`；`UAT-03`；质量与工作区清理事项继续 Deferred，均不得因 S4 CLOSED 被遗漏。
- **新增 `UX-ST-01` P2（待 Git Backlog 同步）**：负责人 2026-10-26 确认统计现有功能可用，但使用体验欠佳，暂不判断是 UI 还是使用流程；在 v1.0 全部业务功能完成后，**与整体 UI 一起参考 MoneyWiz 做对比与统一产品体验优化**。不阻断 S5，不自行更改冻结 UI。本条在当前 Chat 已记录，尚无证据证明已写入 S4 CLOSEOUT Backlog，不可冒充同步。
- **AI 业务规则已批 B**：永久删除任一账户后，该用户既存全账本 LEDGER 月／年报告不再对用户可读，用户无永久删除账户恢复功能；删除影响预览应提示报告影响，已耗模型额度不返还。现有备份/未来合规分析方向未因此获得新的数据库或历史数据留存授权。

## 4. 当前责任、授权与 STOP

- 组长当前负责：落实已批准的 S5 计划与方案 B，核对批准文件可达性、安排 T00～T05 连续任务包并在既有 Gate 停止；控制最少介入、最少审核轮次。
- 下一执行方：**单一 Coding Agent 执行 S5-T00**；先核验批准原件及 SHA、Git/安全数据库配置、V18 迁移风险、模型 Stub，之后按批准依赖连续 T01～T05；完成后停止于 T06，由独立 Testing/Supervisor 门禁接续，不得跳过 T07 人工验收。当前尚无 T00 执行回执。
- 审批保留：新 Stage 完整计划、冻结业务行为改变、正式 Schema／实际额外迁移、真实业务或生产数据、生产部署、强推／新 PR／main Merge 等。
- STOP：获批 S5 原件不符；Spring/Flyway 自动把 V18 迁到未经授权的 `bookkeeping` 或 `bookkeeping_test`；跨租户/报表泄露、错误计费、P0/P1 未闭环、覆盖未提交或不可恢复工作树、未通过独立／人工 Gate 越界推进。

## 5. Project 与 Git 交接

- Git 的 `docs/CURRENT.md` 据 Cursor S4 T08 已被更新，但尚未包含本轮 S5 批准；请在下一次合法 T00 文档归档更新为 S5 APPROVED/T00 IN PROGRESS（按实际执行）并核验。负责人已表示 Project 有 v1.3，本 v1.4 为替代候选；不能声称 ChatGPT 自动同步。
- S5 Review 已获负责人批准并生成 Approved v0.1 和独立批准记录，待 Cursor 核验 SHA 后保留原字节入 Git 与审批索引；S4 的正式结项/Backlog/索引已据报告存在于 Git，不再次制造多套并行文件。

## Change Log

| 版本 | 日期 | 变化 | 状态 |
|---|---|---|---|
| v1.2 | 2026-10-25 | 截止 S3 在途的 Project 历史入口 | 已过时 |
| v1.3 | 2026-10-26 | 接入 S4 CLOSED、V18 隔离 PASS、Git/PR 回执、未测范围、UX-ST-01 和 S5 待审批责任 | 历史快照；负责人表示已放入 Project |
| v1.4 | 2026-10-26 | 记录负责人批准 S5 初版计划与方案 B、正式文件 SHA，下一责任调整为 T00；保留未执行／未同步边界 | Project 替换候选，未自动同步 |
