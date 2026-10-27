# 04丨记账软件｜S6 既有 Draft PR 中间同步时点定向修订 · Approved v0.4

- 日期：2026-10-27
- **状态：APPROVED，立即生效。** 负责人在收到 v0.4 Review 与组长“是否批准上述 v0.4 中间 Push 时点调整”的单一明确审批问题后回复“同意”。
- **审批原件（保持原字节）**：`bookkeeping-v1.0-s6-interim-draft-pr-timing-review-v0.4.md`；SHA-256 `6552ff6a85834ec6e9f751fdd2f9620ebbed8acf385d576a2fc3a1f0f055aec5`。
- **被修订的正式完整 Stage/Task Plan（保持原字节）**：`bookkeeping-v1.0-stage-s6-ui-final-plan-v0.3-approved.md`；SHA-256 `8619081f735ed6a73ab2552d5eca6cac8e0299ad6488f83a0a41ddafa68b20d7`。
- **治理依据**：`bookkeeping-ai-collaboration-governance-v1.8.md`，既有 `release/v1.0` 受限非强制 Push、单 Coding Agent 串行、独立 Testing/Supervisor、负责人保留人工里程碑/冻结产品与 UI 的审批权限；本修订**不扩大 Git 权限类别**。
- **文件效力**：本文件是 S6 完整 Approved v0.3 的**单一时间点补充修订**，不替换、不改写 v0.3 任务正文。v0.3 的 T00～T09、T08 一次完整人工 UAT 和 T09 正式结项继续有效；仅在满足下列条件时允许提前一次向现有 Draft PR 同步受审冻结 UI 基线。

## 1. 前后差异与批准结论

| 范围 | v0.3 已批准 | v0.4 增量批准 |
|---|---|---|
| 中间远端 Push | T09 在 T07 和 T08 PASS 后结项同步 | **S6-T07 Testing 与 Supervisor 双 PASS，P0/P1=0 后，即可在 T08 前通过既有授权对 `release/v1.0` 进行一次非强制快进 Push，更新已有 Draft PR #2** |
| T07 独立质量 Gate | 必须独立通过 | 不变；若后续 UI/业务代码变化，受影响内容必须补独立复验，旧 Gate 不能替新代码背书 |
| T08 人工 UAT | 最终统一人工 Gate | 不变，**尚未执行，不能记 PASS**；真实 AI 生成成功、持久保存和历史读取仍须最终验收前验证 |
| UI 评估 | 实际试用后再讨论，B 类冻结差异须审批 | 中间 PR 同步后讨论 `UX-ST-01`；本批准**不授权任何 B 类 UI 调整或新的业务功能** |
| T09 结项及 Git | 双 Gate 完成后结项与正式同步 | 不变；结项时仍需核对并在必要时合法补充同步，禁止跳过正式人工 Gate |

## 2. 本次中间同步的既有 Gate 事实（据 Cursor 回执，待执行时实核）

- 产品最后代码提交 `5ba4aabfc8820fdb851691f11a417bab0075c842`（S6 冻结 UI + AI 404 / 空快照定向修复）。
- T07 受审 HEAD `8d0ddb2604b708912347384908a601aa89ade65f`；Gate 文档提交 `7e93a403de6066a3be69ca77f5b282c00fa052a6`，据回执只增加审核记录，未修改产品代码。
- S6-T07：Testing **PASS**、Supervisor **PASS**、P0/P1/P2/P3=**0/0/0/0**。小程序 238 项、后端单元 88 项、隔离 MySQL Stub Gate 1 项通过；typecheck、lint 通过。**这些不是 CI PASS，也没有真实供应商调用或微信真机验证。**
- 后续独立审核如发现新 P0/P1、安全越权或来源不明变更，STOP 同步并报告，不以此批准忽略 Gate。
- `S6-AI-GEN-01`：未加载外部供应商配置导致生成 502，真实 AI 调用/成功保存/重新读取尚未验证，保留为 v1.0 **最终 UAT 前必须关闭的验收阻断**；本次 PR 中可显式记为 deferred，不能标成通过。
- 本地 `bookkeeping` 已有 V18 的来源/权限仍有历史记录不一致，允许在既有合法范围内查证记录，但**不批准任何数据库读写、额外迁移或回滚**。无真实越权证据时不因此另加质量 Gate。

## 3. 执行要求与 STOP

1. **先归档本 Review 原件、Approved v0.4、独立批准记录**；只读核对现有 `docs/CURRENT.md`、Git HEAD、提交拓扑、工作区，以及远端 `release/v1.0` 的实际 HEAD。不得以本审批文件被生成代替 Git 已归档。
2. 优先采用历史已验证的 Mac 本地 HTTP 代理 `http://127.0.0.1:7897`，按 `bookkeeping-github-local-proxy-git-runbook-v0.1.md` 做有界连通预检、`fetch`、`merge-base --is-ancestor` 检查后，**仅当真实远端是拟推送提交祖先时非强制快进**到现有 `release/v1.0`。禁用强推/历史重置/创建新 PR。
3. 同步后用 `ls-remote` 核实远端新 SHA，并检查已有 [Draft PR #2](https://github.com/L-Hurricane/bookkeeping/pull/2) 仍为 Open / Draft；checks=0 不能说 CI PASS。
4. 此操作**不合并 `main`、不部署、不将 S6 标 CLOSED、不宣称产品已发布、不进行真实供应商付费调用或真实数据导入**。后续 UI 代码变更需在冻结差异审批与必要回归/独立复验后才能用于最终 T08。
5. 对网络/鉴权失败只进行有限诊断，不无界重试；远端非快进、恢复点不明、出现 P0/P1/真实越权风险、批准原件 SHA 不匹配时 STOP 报告。

## 4. 记录与验证

本修订产生后需保存原 Review SHA、批准事实及 Approved 文件 SHA 到独立批准索引。当前结果仅为**批准可执行**；在收到 Cursor 完成回执之前不得声称本次 Push 已执行成功。

## Change Log

| 版本 | 日期 | 变更 | 状态 |
|---|---|---|---|
| Approved v0.3 | 2026-10-27 | 完整 S6 T00～T09，冻结 UI 优先、T07 独立 Gate、T08 人工 Gate、T09 Git | 继续有效 |
| Review v0.4 | 2026-10-27 | 提议 T07 PASS 后先快进同步既有 Draft PR，T08/T09 不变 | 原件保留，SHA 已绑定 |
| **Approved v0.4** | **2026-10-27** | 负责人回复“同意”，批准**本 Stage 此特定的中间 Push 时点例外** | **APPROVED** |
