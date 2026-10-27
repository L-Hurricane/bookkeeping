# Cursor 接力｜S6-T00～T05 冻结 UI 实施工作包 v0.1

**仅适用于已经批准的 S6 范围，禁止将指令理解为新的跨 Stage 授权。**

## 当前依据

- 治理：`bookkeeping-ai-collaboration-governance-v1.8.md`（以 Git/Project 有效原件为准）。
- Review 原件：`bookkeeping-v1.0-stage-s6-ui-final-plan-taskplan-review-v0.3.md`，SHA-256 `2ca8c1a2c44a55b85f9e4d09900e2bc39bb85a5cde99973a339b7bd47adb116a`。
- 正式计划：`bookkeeping-v1.0-stage-s6-ui-final-plan-v0.3-approved.md`，SHA-256 `8619081f735ed6a73ab2552d5eca6cac8e0299ad6488f83a0a41ddafa68b20d7`。
- 独立批准记录：`bookkeeping-s6-stage-plan-approval-record-v0.3.md`，SHA-256 `172cbc2d5548db848e70029eb819f2c4794275ff99c5ce7cb81e496d14d9c98f`。
- 状态替换候选：`bookkeeping-current-state-handoff-2026-10-27-v1.9.md`，SHA-256 `35145f3ed1b804506001f78f18b387e45ddbd846c309bd867115c848d5ec5edf`。
- S5 最后已确认本地/远端 `release/v1.0`：`676fca14ed834837a306df3f58f8938485d94abf`，Draft PR #2 仍 Open/Draft、checks=0、main 未合并，须在 T00 复核实际状态。

## 本次连续合法工作

1. **T00**：核对全部原件 SHA、Git 工作树和恢复点、最新 `docs/CURRENT.md`；复用此前 S6 只读调查；核实 `S3-UI-ENTRY-01` 原始 Backlog，不做重复全面调查。将 Review/Approved/批准记录保存原字节并归档，更新批准索引和状态入口，不能假称 Project 自动进入 Git。
2. **T01**：按冻结 `bookkeeping-v1.0-ui-freeze-v1.0.md` 形成最小页面/组件/行为差异映射；冻结规则清楚的直接实施。发现真实冲突时只停止受影响变更，汇报具体冻结差异，不阻断其他已批准工作。
3. **T02**：实现账本/统计/我的底部导航和账本级分类/标签管理入口，确保既有账户上下文、归档访问和 AI 跳转可达。
4. **T03**：按冻结 UI 完成账本净值／收入／支出分区、分类/标签等权入口、筛选/下钻/显式多选，以及正红负绿、直接编辑、转账/余额调整等必要交互差异；保持原账务与 AI 语义不变。
5. **T04**：修复原批准范围的 `S3-UI-ENTRY-01`、`S4-UI-COPY-01`、`S5T06-D1` 和影响完整基本路径的其他定向缺陷；准备合法、可恢复的非真实数据体验预览。`UAT-03` 不自动立项。
6. **T05**：Coding 自测和受影响回归，形成可运行冻结 UI 基线、可供负责人一次体验的最短清单，准确标明未测与环境条件。仅有源码查阅不能代替数据库/真机验证；若未验证则保留未测。

优先完成**冻结 UI + 核心业务稳定**，不得提前用 MoneyWiz 风格重做统计，或取消旧单账户 AI 入口。T05 只用于负责人功能观察，不是新增正式独立 Gate 或最终 UAT。

## 固定边界和 STOP

- 单 Coding Agent 串行；T00～T05 范围内普通技术决策、必要修复、依赖内次序调整由组长授权自主执行，记录变化和证据。
- 不修改已冻结 PRD/UI 或独立人工 Gate；B 类冻结 UI 改动、`UAT-03`、新业务需求必须有负责人另行审批才可实现。
- 不操作未经授权的 `bookkeeping`、`bookkeeping_test`、真实/生产/共享数据库，不触发 Spring/Flyway 的默认意外迁移；不使用未经批准的真实付费模型、敏感数据外传。测试优先 Stub/合成数据。
- 不强推、新开共享 PR、合并 main、部署生产、破坏性清理。S6 远端 Push 仍须核对 v1.8 §4.8 和实际 Stage/Gate 条件；历史成功代理 `127.0.0.1:7897` 不是额外 Git 授权。
- 如批准 SHA 不符、不可恢复风险、跨用户/金额错误 P0/P1、独立 Gate 越界或审批范围变化，STOP 受影响工作并出具最小阻断报告。
- **完成 T05 后停止**，一次交付 HEAD/提交 SHA、自动化实际运行结果、未测范围、体验入口/清单、P0/P1 和 T06 后续准备条件；不自行启动 T07 独立 Gate、T08 人工最终 Gate 或 T09 结项。
