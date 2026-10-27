# 04丨记账软件｜唯一现行项目状态与跨 Chat 交接记录 v2.9（待业务 Git/Project 同步）

- **编制日期**：2026-10-27，依据当前负责人最新产品审批与 Cursor 回执；替代此前**本 Chat 生成**的 v2.8 候选（未证明此前版本已在 Git/Project 归档）。
- **当前一句话**：**S6-T08 已经进入负责人实际家庭账本试用；第一轮 UI-FB-001～009 据 Cursor 回执完成前端修改和 245 项小程序自测，但缺真实新代码 SHA/独立审核；负责人第二轮 FB-010～014 原型已明确批准实施，下一责任人为唯一 Coding Agent。T08 尚未 PASS，T09 未开始，S6 未 CLOSED。**
- **证据性质**：代码、MySQL、服务、Gate 状态来自负责人粘贴的 Cursor 回执；本 Chat 未直接读取本机 Git/MySQL/微信开发者工具。本文件尚未写入 `docs/CURRENT.md`，也未上传 Project。正式现场以 Cursor 核验为准。

## 1. 治理与权限

现行治理 `bookkeeping-ai-collaboration-governance-v1.8.md`；S6 初版完整 Stage/Task Plan `bookkeeping-v1.0-stage-s6-ui-final-plan-v0.3-approved.md`，批准 SHA `8619081f735ed6a73ab2552d5eca6cac8e0299ad6488f83a0a41ddafa68b20d7`；正式 PRD 和 UI Freeze 继续有效；继承首轮已批 UI Diff v0.1 和 S6 Scope/Gate Amendment v0.5。

项目负责人：冻结范围、人工 Gate 与保留高风险审批、亲自网页 Merge main；ChatGPT：完整组长职责、普通范围内任务调整和独立 Gate 组织；**一个 Coding Agent 串行**；独立 Testing 与 Supervisor 不能由该 Coding 会话冒充。不重新申请已批准的普通实现权限、不新增 Stage/重复 Gate。

## 2. 已批准 / 执行 / 验证状态

| 对象 | 目前可确认的最新事实 | 边界 |
|---|---|---|
| S1–S5 | CLOSED | 不重开 |
| S6-T07 | 最早家庭产品基线 `58557e9ed0dcb68a20b5b83d7b361f15e104c5df` 独立双 PASS，P0/P1=0；dev/test 接口源变更 `9501b77b7a66de669a75f26d91ab18d542c79d56` 定向双 PASS | **不覆盖后续第一轮/第二轮改版的新产品 HEAD** |
| 第一轮 UI-FB-001～009 | 负责人已批准；Cursor 最近回执称只改小程序前端，九项大体实现，mini 245 PASS、typecheck/lint PASS；**未提供新的完整 commit SHA、独立测试/审核结果及真实截图**；此前账户流水仍有“退出登录”漏项 | 下一次执行先核对实际代码和遗漏，不能据回执猜最新 HEAD |
| 第二轮 UI-FB-010～014 | 负责人审阅七屏可点原型后明确“**可以，准备实施吧**”；Review 原件 SHA `e190506e9345fd1e9218db9a6854dff388013cedfa419c7e88469148faa611a4`；已生成**独立批准记录** `bookkeeping-s6-ux-round2-approval-record-20261027-v0.1.md` | 可在现行 S6 T06 内实现；**尚未收到本轮 Cursor 编码回执** |
| S6-T08 | **负责人已经实际使用模拟家庭账户**，第一轮九项、第二轮五项 UI/UX 反馈；最终 UAT 尚未明确 PASS/FAIL | 用户不是全职测试员；新前端需定向独立验证 |
| S6-T09 | 未开始；既有 Draft PR #2 / `release/v1.0` 受原限定授权 | 不提前 Push/Merge/部署或声称 CLOSED |

## 3. 第二轮具体交付范围（已批准）

- **FB-010 净值趋势**：柱状图改为按北京时间业务日终净值的折线，交易日最后生效后数值；无交易日沿用上日；真实 API、两侧转账时间、固定余额调整、筛选范围正确。
- **FB-011 单账户流水**：统一账户资产卡片/收支摘要/月度/流水/搜索/FAB 样式；去掉说明及前轮遗留退出登录；点击流水直接编辑，归档账户只读，UAT-03 逐笔余额继续延期。
- **FB-012 控件与管理页**：时间/账户筛选、分类和标签操作统一风格，去掉教学提示，保留真实删除风险提示。
- **FB-013 分类/标签树移动**：触屏拖动调整层级 + 点击手柄选择合法上级，解决一级合法移回二级；不改 PRD CT-01～10 规则与已有交易关联，不添加未经批准的 Schema。
- **FB-014 记一笔及账户弹层**：金额主视觉与分组卡片、账户选择紧凑底部弹层，收入/支出共表单，转账独立，字段与业务逻辑保持。
- **前轮 FB-008 完成项**：移除账户流水底部“退出登录”，统一仅在“我的”。

绑定设计文档原件 `bookkeeping-s6-ux-round2-design-review-v0.1.md`、可点击原型 `bookkeeping-s6-ux-round2-interactive-preview-v0.1.html`；对应批准记录和 Cursor 接力指令由本 Chat 新生成。Review 仍保留历史 `REVIEW/PENDING` 原字节，不因后续批准而改写原件。

## 4. 试用环境、安全与已发生的 AI 调用

- 本机 MySQL 8.4.3 专用合成库 `bk_s6_family_e2e_20261027`，Flyway V1–V18；旧五库必须保持只读；dev/test 后端独立端口 `127.0.0.1:18081`，日常 `8080` 不改。最近回执显示已可用，实际执行再现场验证。
- `s6_family_owner`：约 6295 笔家庭普通交易，**用户已开始自行试用，禁止任何重置/覆盖**；自动回归使用 `s6_family_auto`。密码仅在 Mac 本地受限环境，不出现在聊天/文档/日志。
- 真实 DashScope `qwen3.7-flash` 经批准的两次 auto 月/年报已成功生成并持久化回读；3 次上限仅余 **1 次**，只能负责人亲自点击触发，Agent 不得代点或重试。
- 微信开发者工具**模拟器**可用；手机真机因本地 HTTP/合法域名问题仍 `NOT TESTED`，未经额外授权不开放公网/隧道/部署。
- UI 改版不动核心账务、Schema、账户自定义分组 B01、逐笔余额 B02、高级报表等延期范围；分类/标签聚合中性色是已批准特定例外，其余有符号金额保持正红负绿。

## 5. 下一责任人/交付条件

**下一方：Cursor 唯一 Coding Agent**，读取现行 `docs/CURRENT.md`、本状态、S6 正式计划、批准记录、第二轮 Review/预览；先只读确认真实分支/HEAD/现有首轮改版；在已批第二轮范围串行实施并自测；将真实截图、受影响路径、未测范围和最终 commit SHA 提供给独立 Testing 与 Supervisor。对新受审 HEAD 独立**定向**双 PASS 后，交负责人继续 S6-T08 真实产品试用与最终 Gate 决定。**无事实依据不得宣称已被 Cursor 执行、通过 Gate、T08 PASS 或 S6 CLOSED。**

**STOP**：正式 Schema、未批核心业务语义、真实库/用户数据、用户试用账户重置、越权敏感读取、真实模型额外调用、公网服务、强推/新 PR、Agent Merge main/部署。保护本地 Git 工作区和可恢复起点。

## 6. 六项接任核对

1. **角色与权限**：治理 v1.8、组长负责动态调度、一个 Coding Agent、独立 Testing/Supervisor。
2. **Stage/Task/Gate/Backlog**：S6、T08 在途、第二轮 UI 已获批待实施、T09 未开始；B01/B02 延期。
3. **批准与执行终态**：Review v0.1 原字节 `PENDING` 但后续负责人批准已由独立记录确认；**新代码尚无回执**。
4. **Git/源码/证据时间**：本地 `uat/v1.0-s2` 据旧回执；家庭原受审 `58557e9`、端口变更 `9501b77`；**首轮最新版 SHA 未取得、当前工作区未复核**。
5. **下一执行方与完成判定**：Cursor 只读核对→T06 定向 UI 实施→Coding 自测→对最终 HEAD 必要 Testing/Supervisor→负责人继续 T08 体验；如需权限外变更仅报告最小阻断。
6. **越界禁止**：绝不重置 owner、迁移旧库、外发额外 AI 请求、未经审核 Push/Merge/部署、改变 T08 人工 Gate 或新增 Stage。

**交接就绪性**：`HANDOFF_PARTIAL`（正式文件和审批足以授权下一轮任务，缺 Cursor 实时 Git HEAD、首轮改版独立验证及执行受理回执；这些由 Cursor 自主核实，不要求负责人重述）。

## Change Log

- **v2.9 / 2026-10-27**：继承 v2.8 的正式治理、试用数据/AI/旧 Gate 事实；追加 Cursor 已报告第一轮前端 Coding 与自测但缺最新 SHA/独立 Gate；追加第二轮 FB-010～014 原型已获负责人“可以，准备实施吧”明确批准、独立批准记录、下一执行责任及 FB-008 漏项；T08 继续在途，T09 未开始。文件当前仅在本 Chat 创建，未冒称已写入 Git/Project。
