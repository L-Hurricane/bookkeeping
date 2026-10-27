# 04丨记账软件｜S6 UI 收敛范围及 Gate 接续 · Approved v0.5

- 日期：2026-10-27
- 状态：**APPROVED（对现行 S6 计划的增量补充）**。
- 负责人批准依据：“两份文件同意。账户分组可以暂缓，逐笔余额评估一下工作量和技术实现难度，再考虑是否暂缓”。
- 原 Review：`bookkeeping-v1.0-s6-ui-scope-gate-amendment-review-v0.5.md`，SHA-256 `34393969b323ad9c57b05b775cbe64bd4953cd670d5c54b9903c72d269be906d`，原字节/`PENDING` 不改写。
- 配套 UI Approved：`bookkeeping-v1.0-ui-freeze-diff-v0.1-approved.md`；其绑定 UI Review SHA `48570a6ea649287bbc129c76fa04b27ed6f8bef3bd9a87d012f2099d7855b1dc`。
- 继承原 S6 Approved v0.3 SHA `8619081f735ed6a73ab2552d5eca6cac8e0299ad6488f83a0a41ddafa68b20d7` 和中间 Push Approved v0.4 SHA `20cf53e6ed041e58c206f955bf51c432c97c09f17131e3027194b14e57ca0044`；不扩大 Git 权限。

## 1. 已批准的实施与 Gate

- 继续在**既有 S6-T06** 的任务范围内串行实施 UI-A01～A09。Coding 前做最小差异核验，复用现行代码和接口，新增必要只读接口不能改账务事实；新增正式 Schema/数据库操作另行保留审批。
- **S6-T07** 不增加第二个独立 Gate；对新受审 HEAD 及受影响接口做必要的独立 Testing/Supervisor 复验，双 PASS、P0/P1=0。旧代码 HEAD 的 Gate PASS 仍独立保留。
- **S6-T08** 原一次性最终人工 UAT、**S6-T09** 结项和原中间 Push v0.4 的有限批准不变。真实 AI 外部模型、真机、未测项按既有规则另作必要验证。

## 2. B 组实施边界的特定澄清

原 Review 中 B01、B02、B03 默认延期是组长建议，而非无条件的审批事实。本轮负责人实际决定：**B01 延期；B02 仅允许定向只读技术评估，暂不决定是否延期；B03 延期**。对 B02 的调查不构成新增 Coding、Schema、Stage 或原人工里程碑交付范围变更授权。调查应与已授权 T06 差异核对合并，不开新的审核 Gate。

## 3. STOP

审批原件/SHA 不匹配、不可恢复 Git 起点、P0/P1、账务正确性/租户隔离受损、未经批准改变冻结业务、未经授权数据库/真实数据/外部模型/远端 Git 及破坏操作时停止相关动作。

## Change Log

- Approved v0.5（2026-10-27）：正式批准 S6 T06 UI-A01～09 增量及 T07 定向独立复验，保留 T08/T09；B02 改成来源明确的待评估事项，非提前决定延期。
