# 04丨记账软件｜唯一现行项目状态与跨 Chat 交接记录 v3.1（同步候选）

- 记录日期：**2026-10-28**；继承 `bookkeeping-current-state-handoff-2026-10-28-v3.0.md`，本次依据负责人新增 T08 实际试用反馈及明确同意的四项 UI 差异更新。
- **性质**：在本 Chat 生成的**唯一现行状态候选**，尚未写入业务 Git `docs/CURRENT.md`、尚未同步 Project 共享资料、尚未发到 Cursor；不得假称其他表面已更新。
- **一句话现状**：**S6-T08 负责人试用仍在进行、尚未 PASS；负责人已批准 UI-FB-016～019 一次性整改；Cursor 连续工作包已准备、尚未开始执行。S6-T09 NOT STARTED、S6 NOT CLOSED。**

## 一、治理与有效审批

1. 正式治理：`bookkeeping-ai-collaboration-governance-v1.8.md`；S6 初版完整 Stage/Task Plan `bookkeeping-v1.0-stage-s6-ui-final-plan-v0.3-approved.md` SHA-256 `8619081f735ed6a73ab2552d5eca6cac8e0299ad6488f83a0a41ddafa68b20d7`；后续 S6 v0.4 中间 Push 时点修订与 v0.5 UI Scope/Gate 增量仍有效。既往第一、二轮 UI 设计批准不失效。
2. **本轮新审批**：负责人明确接受 FB-016 的账户 FAB 方案、FB-017 的标签改为弹层显式多选、FB-018 的「流水→详情→编辑」原型及 FB-019 的账户筛选三模式/自选分组，并表示“同意你的这个建议。其他暂时没发现问题，对这一轮排查做一下调整修复。”留档 `bookkeeping-s6-t08-fb016-019-ui-diff-and-approval-v0.1.md`，**SHA-256 `db34c71e39d5959c422972624097c6ed1c87bd3ed5a478f02900671cf916c3e5`**。
3. FB-018 此次确立对原 UI Freeze §2.2 的**明确、有限例外**：普通收支流水点击从直接编辑改为先看详情；取消和保存编辑均返回详情。其他未被明确替代的冻结业务事实/权限不变。已存在的 Design Review v0.1 原件 SHA-256 `829d06b34bad2bf3567983f7753039a22bd2df1c285f88e37779df665034a3c9` 保持原字节。
4. 该批准不新增 Stage、人工 Gate、正式 Schema、数据库执行、模型额度、Git 推送/合并/部署权限；按现有 S6 T06 反馈实现与已批准独立质量边界执行。组长可自主安排普通范围内实现与定向测试；仅一个 Coding Agent 串行，独立 Testing/Supervisor 与 Coding 实质隔离。

## 二、已完成进度（以负责人最后一次 Cursor/Testing/Supervisor 回执为据）

- **S1～S5 CLOSED**；S6-T07 早期 `58557e9ed0dcb68a20b5b83d7b361f15e104c5df` 家庭数据基线曾 Testing/Supervisor 双 PASS，后续 UI 提交经必要定向复验；旧 HEAD 的 PASS 不覆盖未测新产品代码。
- **最近报告的代码/分支**：`uat/v1.0-s2`、产品 HEAD `379ca7c578d52d8ab9503d5efb052572dc082d50`，据回执工作区干净；最近小程序 **249 PASS**、typecheck/lint PASS，独立 Testing 定向 PASS，Supervisor **`ACCEPT FOR CONTINUED OWNER TRIAL`**；不是 T08 PASS。
- **最近落地能力**：转账账户底部弹层、跨年账户交易按实际业务时间分组、编辑页与分类弹层调整、长区间日终净值：`ROLLING_YEAR + ACTIVE` 据回执返回 366 个连续北京业务日点。没有证据表明本轮 FB-016～019 已编码。
- **UAT 合成环境**：已批准专用库 `bk_s6_family_e2e_20261027`、Flyway V1～V18；历史回执隔离后端 `127.0.0.1:18081` PID `55562`、日常 `8080` PID `775`，均是**当时快照**，执行前必须重新检查；不得写五个旧库。
- **试用身份**：`s6_family_owner`，约 **6,295** 笔家庭交易；自动化独立 `s6_family_auto`；密钥仅位于负责人本地私有配置。不得查看/传播凭据，更不得重置 owner 的人工试用记录。
- **真实 AI**：独立合成自动用户已成功一次月报、一次年报，快照回读 PASS；最后一次外部调用授权**仅保留给负责人在试用用户上主动触发**，Agent 不得代用。真机仍 NOT TESTED，不得以模拟器代替。
- **Git/发布**：无证据表明最新 UI 改动已 Push、合并或部署；`release/v1.0` 的有限非强制 Push 权限不自动授权本轮任意远端操作；main 仍仅负责人通过 GitHub 网页 Merge。

## 三、本轮待执行四项（负责人已确认，Coding 未开始）

| ID | 明确目标 | 核心测试 |
|---|---|---|
| FB-016 | 单账户卡移除普通账户/统计/余额调整冗余文本；首页/全部交易 FAB 3 项，正常单账户 FAB 4 项含“调整余额”；默认当前账户；归档只读 | 首页三项/正常单账户四项/归档无 FAB、调整余额正确独立记账 |
| FB-017 | 新增/编辑标签均通过统一弹层显式多选、树形显示、新建/确认/取消；表单只显示已选摘要 | 父子标签独立多选、编辑回显、取消不丢数据、新建标签独立保存 |
| FB-018 | 普通交易**列表→只读详情→编辑**，编辑取消/保存回详情；删除保留确认；统一详情与编辑视觉 | 各列表入口、导航返回、编辑保存、删除及归档历史只读；核对正式 PRD TX-04 允许换账户与旧实现锁定的潜在冲突 |
| FB-019 | 统计筛选三模式互斥：未归档默认/全部含归档/自选；自选可选未归档及已归档，两组排序，独立可搜索多选面板、合计摘要、清空状态明确 | 统计五类与下钻筛选继承、自选组合、空选、隐式过滤不丢账户 |

详细冻结差异、STOP/验收矩阵及权限见本次正式批准记录和 `bookkeeping-s6-t08-fb016-019-cursor-handoff-v0.1.md`。**B01 自定义账户分组、B02/UAT-03 逐笔余额、B03 高级报表继续延期；自选筛选面板仅为以后分组预留结构，不实施分组。**

## 四、未完事项／Backlog 与 Gates

- **S6-T08**：IN PROGRESS，负责人明确说这轮没再发现其他问题，并批准一轮定向 UI 修复；这不代表正式 UAT PASS。完成代码、必要独立 Testing/Supervisor 后，再让负责人自由试用并明确 PASS/FAIL。
- **S6-T09**：NOT STARTED；T08 正式 PASS 之后才能收敛 Stage/Backlog/证据并按既有有限 Git 权限处理，不能自动 merge main/部署。
- **待核实非阻断 P3**：统计旧 `moreOpen` 无模板绑定、标签拖空无法保持所选行。没有独立阻断证据，不为此另造 Gate；若在本次页面范围内顺手定位合法修复，可最小处理并记证据。
- **技术事实核对**：近期 Cursor 回执说普通交易已保存后锁定资金账户，但正式 PRD TX-04 与 UI Freeze §2.2 写明允许修改所属账户。这是潜在**实现/冻结契约差异**，不得因 UI-FB-018 而静默扩大或缩小账号编辑权限；本次 Cursor 做只读核对，若属现有已批业务能力遗漏，依法定 Gate 做范围内最小修复，否则停止受影响行为并报告。
- **真机**：NOT TESTED；原 T08 的真机核对要求保留并在最终人工结论里明确接受或处理未测，未经新授权不得开公网/隧道。

## 五、动态交接：负责人／ChatGPT／Agent

- 负责人：**已批准本轮四项 UI 差异**；不要求继续亲自做专业测试；等新代码可用后做产品体验与最终 T08 决策。
- ChatGPT 项目组长：完成本轮审批、接力及唯一状态候选；审 Cursor 回执后分级裁定、组织必要定向独立复验和 Gate；**尚未直接操作 Cursor/Mac/Git，也未声明任务自动启动**。
- Cursor Coding Agent：**待接收** `bookkeeping-s6-t08-feedback-round3-approved-cursor-handoff-20261028.zip`；收到后先核验 SHA、现行 `docs/CURRENT.md`、HEAD、工作区/恢复点，再一次性完成四项可批准范围内的实现与自测。
- Testing Agent / Supervisor：**尚未执行本轮新代码**；Coding 最终 SHA 确定后实质隔离地按相关业务路径做必要定向 Testing/Supervisor，不能沿用上一 HEAD PASS。
- 当前完成判定：最终产品 SHA + 自动与真实模拟器/合法隔离 HTTP 证据 + 独立定向 Testing/Supervisor 结论 + 最新剩余未测，**仅达到继续 T08 试用就绪**，不自动意味着 T08 PASS。

## 六、接任六项核对与禁令

1. **角色/权限**：治理 v1.8；负责人独立审批冻结产品/UI、DB、人工 Gate、main 网页 Merge；组长决策普通任务；Coding 单 Agent 串行、独立 Testing/Supervisor。
2. **Stage/Task/Gate/Backlog**：S1～S5 CLOSED；S6-T08 IN PROGRESS；FB-016～019 负责人已批、Agent 尚未执行；B01/B02/B03 延期；P3 待核。
3. **批准与实际执行**：本轮批准≠已编码≠独立通过≠人工验收；旧阶段 Gate 与最新代码的定向验证分别记载。
4. **Git/代码/证据**：据最近回执 `uat/v1.0-s2`、`379ca7c578d52d8ab9503d5efb052572dc082d50`，`18081`/MySQL/用户需现场核验；本 Chat 产物尚未同步 `docs/CURRENT.md`、Project。
5. **下一执行者与完成标准**：用户一次性交给 Cursor 本包；Coding 实施→独立定向 Testing/Supervisor→组长 Review→负责人继续 T08 体验/最终决定。
6. **禁止越界**：不改正式 Schema/Flyway、旧五库、用户真实/试用数据、未授权外部模型/公网、未批准 Git 操作；不跳人工 Gate、不新增无证据质量门禁。

**HANDOFF_READY（资料准备就绪，尚未实际转交/执行）。**

## Change Log

- **v3.1 / 2026-10-28**：继承 v3.0 的最近真实代码/Gate/隔离测试证据；新增负责人对 FB-016～019 的审批事实、明确 FB-018 改变冻结导航、FB-019 自选归档/未归档混选，以及本轮 Coding/Testing/Supervisor 待执行状态；未宣称 Git/Project 自动同步。
