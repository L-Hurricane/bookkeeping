# 04丨记账软件｜S6 Draft PR 中间同步时点 · 独立批准记录 v0.4

- 日期：2026-10-27
- **状态：APPROVED。**
- 审批人：项目负责人。
- **审批事实**：ChatGPT 组长在 S6-T07 Testing/Supervisor 双 PASS、P0/P1=0 后，向负责人单独请求：“是否批准上述 v0.4 中间 Push 时点调整？”明确限定 T08 之前向既有 `release/v1.0` / Draft PR #2 作非强制快进，不合并 main、不部署、不视为最终 UAT；负责人回复 **“同意”**。
- 审批前 Review 原件：`bookkeeping-v1.0-s6-interim-draft-pr-timing-review-v0.4.md`。
- Review SHA-256：`6552ff6a85834ec6e9f751fdd2f9620ebbed8acf385d576a2fc3a1f0f055aec5`（原字节保留，其正文 Pending 不改）。
- 被修订 v0.3 正式计划：`bookkeeping-v1.0-stage-s6-ui-final-plan-v0.3-approved.md`；SHA-256：`8619081f735ed6a73ab2552d5eca6cac8e0299ad6488f83a0a41ddafa68b20d7`（不修改原件）。
- 正式生效补充：`bookkeeping-v1.0-s6-interim-draft-pr-timing-v0.4-approved.md`；SHA-256：`20cf53e6ed041e58c206f955bf51c432c97c09f17131e3027194b14e57ca0044`。

## 批准范围和生效条件

仅允许在已批准 S6-T07 独立双 PASS、P0/P1=0、无新增安全阻断且远端可快进时，**将当前受审冻结 UI 基线及审查证据提前一次同步至现有 GitHub `release/v1.0` / Draft PR #2**。以本地已存在的合法恢复点、仓库读写权限和治理 v1.8 §4.8 的既有授权为边界。

本批准**不扩大**新 PR/分支、强推、main 合并、正式发布/生产部署、数据库真实操作、真实付费模型调用、冻结 UI 改动等权限。T08 最终人工 UAT 与 T09 结项的原必备 Gate 保持不变；后续代码改动需必要独立复验。真实 AI 成功生成及保存/读取仍为最终验收前的未解决项。

## 归档

将此记录、Review 原件、Approved 补充按现行治理存入业务 Git `docs/implementation/` 或合规的治理/实施归档目录，并在 `docs/CURRENT.md` 或审批索引登记 SHA、事实和适用范围。此文件生成并非已执行 Push 的证据；执行结果以后续 Cursor 回执为准。

## Change Log

- v0.4 / 2026-10-27：记录负责人对既有 Draft PR 中间同步时点例外的明确批准；不修改其他获批计划原件。
