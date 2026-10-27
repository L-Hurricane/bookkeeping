# 04丨记账软件｜唯一现行项目状态与跨 Chat 交接记录 v2.2

- 日期：2026-10-27
- **状态：PROJECT REPLACEMENT CANDIDATE — 未自动同步 Project/Git。** 本文依据 v2.0 及负责人 2026-10-27 转达的 Cursor S6 中间 Push 回执更新远端、PR 与下一步；Git 现场仍以 Cursor 实核 `docs/CURRENT.md`、分支和测试证据为准。
- 正式治理：`bookkeeping-ai-collaboration-governance-v1.8.md`，批准权限边界不变。


## 当前最新审批与下一责任（2026-10-27，优先于下述 v2.1 快照）

- **负责人明确批准**原《UI Freeze Diff Review v0.1》（SHA `48570a6ea649287bbc129c76fa04b27ed6f8bef3bd9a87d012f2099d7855b1dc`）和《S6 Scope/Gate Amendment Review v0.5》（SHA `34393969b323ad9c57b05b775cbe64bd4953cd670d5c54b9903c72d269be906d`），批准 **UI-A01～A09** 在既定 S6 T06 实现、T07 受影响独立复验，S6 T08/T09 不变；不扩大 Git、Schema、真实数据/付费模型权限。
- **实际特殊决定**：UI-B01 自定义账户分组延期；UI-B02 单账户逐笔交易后余额 (`UAT-03`) **既未批准新增 Coding，也未批准延期**，需要先完成最小源码/性能/工作量评估，再作交付时点决定；UI-B03 后续 Backlog。
- **正式衍生文件**：`bookkeeping-v1.0-ui-freeze-diff-v0.1-approved.md`、`bookkeeping-v1.0-s6-ui-scope-gate-amendment-v0.5-approved.md`、独立批准记录 `bookkeeping-s6-ui-scope-approval-record-20261027-v0.1.md`。生成于 ChatGPT 当前工作环境，**未证明已归档 Git 或 Project**。
- **下一执行方 Cursor**：按已批 T06 做一次 UAT-03 有界源码只读评估，继续获批 UI A01～A09 的必要 Coding、自测；仅在新 HEAD 上经过既定 T07 独立定向复验后才可进入原 T08，不再将旧 T07 PASS 冒充新产品 PASS。
- **冻结批准边界**：S6 中途 Push v0.4 的特准曾执行完毕，本次新的 UI 增量批准**不自动新增一次中途 Push**；main 仍仅由负责人网页合并、无部署授权。

## 1. Stage / Gate / 负责人决定

| 事项 | 当前已知事实及责任 |
|---|---|
| S1～S4 | 已 CLOSED |
| S5 | **CLOSED — HUMAN UAT DEFERRED TO v1.0 FINAL GATE**；已合法非强制同步到既有 `release/v1.0`，远端 SHA `676fca14ed834837a306df3f58f8938485d94abf`（S6 Push 前最后一次 Cursor 回执） |
| S6 正式计划 | `bookkeeping-v1.0-stage-s6-ui-final-plan-v0.3-approved.md` SHA `8619081f735ed6a73ab2552d5eca6cac8e0299ad6488f83a0a41ddafa68b20d7`，T00～T09 完整计划继续有效 |
| **S6 v0.4 中间 Push 时点修订** | **2026-10-27 负责人明确回复“同意”，Approved v0.4 已生效**：Review SHA `6552ff6a85834ec6e9f751fdd2f9620ebbed8acf385d576a2fc3a1f0f055aec5`；Approved 补充 SHA `20cf53e6ed041e58c206f955bf51c432c97c09f17131e3027194b14e57ca0044`；独立批准记录 SHA `6e1e6f0c220a8477bca5af00c94e10c4f91b06fab1657752e796bace35652a91`。仅改 T07 后/最终 T08 前，既有 PR #2 的有界非强制同步时点 |
| S6-T00～T06 | 冻结 UI 已实现；404/可空 snapshot 修复，AI 502 定位为供应商配置未加载；T05 负责人基本体验除 AI 外暂未发现明显问题；生成业务未证明真实可用。编码和回归已到受审起点 |
| **S6-T07** | **Testing PASS，Supervisor PASS；P0/P1/P2/P3=0/0/0/0**。小程序 238 PASS、typecheck/lint PASS；后端单元 88 PASS；`bookkeeping_migration_test` Stub/JDBC Gate 1 PASS。受审 HEAD `8d0ddb2604b708912347384908a601aa89ade65f`，Gate 文档提交 `7e93a403de6066a3be69ca77f5b282c00fa052a6`（以上据 Cursor 回执，未直接读取本地 Git） |
| **S6 v0.4 中间 PR 同步** | **已完成**（依据 Cursor 回执）：本地及 `origin/release/v1.0` = `299ea62f7010261c33017bf0cb5b0eafb6b45173`，既有 Draft PR #2 保持 Open/Draft，head 同步；PR 描述中的旧 SHA 和“S6 未批准”文本未同步，需后续合法文档维护。没有强推、新 PR、main Merge 或部署。CI checks=0，非 PASS。 |
| S6-T08 | **未开始、未 PASS**。负责人要求最终在 UI 验证完成后，用合法真实/合成数据及授权调用验证 AI 真实成功生成/持久化/历史读取，进行一次完整 v1.0 人工 UAT。需要实际 Gate 决定 |
| S6-T09 | 未开始、未 CLOSED。最终 Gate 通过后才能结项，main Merge 仅负责人网页操作，部署独立审批 |

## 2. Git / 远端 / 当前执行方

- 已知本地分支 `uat/v1.0-s2`；S6-T07 产品受审 HEAD `8d0ddb2604b708912347384908a601aa89ade65f`，其后 Gate 文档提交 `7e93a403de6066a3be69ca77f5b282c00fa052a6`，前次回执指出产品代码未变。当前实际工作区清洁度和最新 HEAD **待 Cursor 复核**。
- **最新 Cursor 同步回执**：通过本机代理 `127.0.0.1:7897`，合法 fetch + 祖先关系验证后，既有 `release/v1.0` 非强制快进 `676fca1` → **`299ea62f7010261c33017bf0cb5b0eafb6b45173`**，`ls-remote` 实核；`main` 仍是 `a393734ad147a5dbe11a83cebfd3381f408db7b2`；Draft PR #2 保持 Open/Draft，head 同 SHA；GitHub checks=0，非 CI PASS。来源为负责人转述的 Cursor 实测回执，ChatGPT 未直接操作 GitHub。
- **下阶段负责人 / ChatGPT 组长**：基于冻结 UI 与 `UX-ST-01` 先开展完整产品的 UI/UX 讨论，重点统计页面的信息架构、筛选与下钻；当前没有批准 B 类冻结 UI 修改，也未开始新增 Coding。
- **下阶段 Cursor（仅待合法调度时）**：现有 PR 正文落后于实际 head 和审批状态，后续在既有 Draft PR #2 的权限及合法工作节点修正文档描述；无需为了非阻断文案单独组织一次人工交接。不得 Merge main、新 PR、部署。
- **组长后续**：S6 中间 Push 已完成，组织与负责人讨论统计 `UX-ST-01` 和 MoneyWiz 借鉴；如需改冻结 UI，先形成具体差异审批，之后回归/独立复验，最终执行 T08 人工 UAT，不进入 S6 CLOSED 直至正式 T09。

## 3. 未关闭风险与 Backlog

- `S6-AI-GEN-01`（最终 UAT 前必须验证）：真实供应商配置加载、允许范围内的真实月/年生成、成功快照持久化及读取；既有 AI 502 是配置未注入进程，Stub/失败路径 PASS 不等于真实功能 PASS。不得导入真实财务数据或实际付费调用，除非获得必要的独立授权。
- `UX-ST-01`：统计使用效率与 MoneyWiz 对比，**PR 中间同步后**讨论是否需要 UI 升级；不是已批准的 B 类变更。
- `UAT-03`：逐笔交易后余额候选，未获批，不自动进入 S6 编码。
- 历史 `S5-UAT-DEFER-01` 仍在最终人工 Gate 补验；微信真机、HTTP MockMvc、完整 `./mvnw test`、真实模型及 GitHub CI 尚无 PASS 证据。
- **数据库状态分歧**：本地 `bookkeeping` 启动时报告已有 Flyway V18，但历史批准只涉及 `bookkeeping_migration_test`；未知原因/授权来源尚未闭环。不得自行回滚、重迁或触碰真实数据；若找到真实越权/数据安全证据，按治理停止相关操作并报告。
- 本地工作区 WS/清理候选仍 Deferred，禁止越权删除或重置。

## 4. 必须遵守的 STOP

批准原件 SHA 不符；下一次 Git 操作无法确认本地恢复点/远端祖先关系；Push 非快进或鉴权失败；新产品改动未纳入 T07 审核；出现 P0/P1、真实数据泄露/越权数据库操作；需要新共享 PR、强推或合并 main；拟执行未经授权的付费真实模型调用、冻结 UI 产品变更、生产部署。

## Change Log

- v1.9（历史）：S6 Approved v0.3，T00～T05 待开始，现已被实际执行回执替代。
- **v2.0 / 2026-10-27**：登记 S6 T00～T07 完成和双 PASS、AI 真实生成延期；负责人批准 S6 v0.4 中间 Draft PR 安全同步时点；记录 Push 待执行、T08 未开始、UI 后续审批边界。

- **v2.1 / 2026-10-27**：根据 Cursor 实际回执登记 S6 v0.4 中间非强制 Push 和既有 Draft PR #2 head 同步成功，`main` 不变，CI checks=0；PR 正文过期为非阻断文案项；转入 `UX-ST-01` 与全局 UI/UX 评审，T08/T09 未开始、真实 AI 验证未完成，治理权限不变。

- **v2.2 / 2026-10-27**：登记 UI-A01～A09 / S6 v0.5 获批及 B01 延期、B02 待调查、B03 延期，后续授权边界不变。
