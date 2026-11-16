# 04丨记账软件｜v1.2 S1 初版完整 Stage/Task Plan Review v0.1

- 日期：2026-11-09
- 状态：**REVIEW / 待项目组长审核后提交负责人批准。编码未授权。C11 Schema NOT APPROVED。**
- 文档基线：`v1.1/s1-m01` `50178488877ef5c7601f2ad1f8b8f27b97df2dde`。本轮不创建 `v1.2` 分支，不提交，不 Push。
- 产品/UI 候选：`docs/implementation/history/bookkeeping-v1.2-s1-product-ui-diff-review-v0.1.md`。
- Schema 候选：`docs/implementation/history/bookkeeping-v1.2-c11-counterparty-schema-review-v0.1.md`。
- 复用原件：已提交 `docs/implementation/bookkeeping-v1.1-closeout-stage-task-plan-v0.2.md`，SHA-256 `c7a42ff930a8de083abad36afdbff3c565d14bccb16814f8138c6c855e881b04`。原字节不改。本文件不是把该 v0.2 改名后的已批准计划。
- v1.1 S1：**CLOSED**。业务 UAT SHA `786c038d6b09eaa426820d3027f2cb2ca1e0254e`。结项 `docs/implementation/bookkeeping-v1.1-s1-stage-closeout-v0.2.md`。本计划不重新打开 S1，不改结项 v0.1/v0.2 和遗留 v0.1/v0.2，不改 V1–V20。
- 治理：本计划按仓库外已核对的治理 v1.10 §4.12 编写。v1.10 正文 SHA-256 `481a755a00989ee18f9cf140623a86f079d4152cd8714e628ed3d9734a383347`，现只在本机 Downloads，尚未入库。Git 内治理原件仍是 v1.6。v1.11 继任正文未提供给本工作区，本计划不把它标为生效。§4.12 不新增 Git、Schema 或个人数据权限。

## 1. Core

一个本地交付分支，批准后才从 `50178488877ef5c7601f2ad1f8b8f27b97df2dde` 新建。建议名 `v1.2/s1-m01`。本轮不创建。

Core 是 C01–C08 与 C11。C11 是必交付，不降为 Optional。它的编码还要先有第 4 节 Schema 批准。Schema 未批时，切片 1–3 仍可在本计划获批后单独进行；不得把缺少 C11 的 SHA 写成 S1 PASS。

不设 Core＋Optional 双分支。C09/C10、同级持久排序和投资记账不在本 Stage，也不登记成可稍后合入的 Optional。历史回填不是本 Stage 的 Optional，见第 6 节。

## 2. 唯一 Coding Agent

获批后只有一个 Coding Agent 串行实现下表切片。本规划会话不编码。

编码会话不兼任本 Stage 的独立 Testing 或 Supervisor。形成本 Review 的会话不担任要求独立于这份调查的最终 Reviewer。

## 3. 任务切片

| 顺序 | 任务 | 内容 | 数据影响 | 定向自测 |
|---|---|---|---|---|
| 0 | 本 Review 与两份配套 Review | 方案 | 无 | 已写入。不是 DEV PASS |
| 1 | C05 + C06 | 单账户流水按余额影响显示符号和类型文字。保存返回保留原筛选，按记录 id 定位 | 仅前端展示与导航。不改库存金额 | 收入、支出、负收入、负支出、两侧转账、余额调整、信用账户、零金额。编辑请求仍提交原金额。月汇总不变。范围内翻页直到命中；范围外不改筛选 |
| 2 | C02 | 五个时间入口共用 `business-time.ts` 和同一面板，并拒绝不合法输入 | 无 | 规范日期才能绑到原生选择器。改日期保留秒。用时间选择器才把秒归零 |
| 3 | C03 + C04 + C08 | 排序独立展开、边缘自动滚动、拖动平滑让位、松手保存。转账选择器按现有分组和组内顺序 | 复用已有分组 API。无新表 | 折叠与排序展开分离。失败恢复服务器顺序。归档不可选，两侧账户互斥 |
| 4 | C11 | 支出的可选交易对方 | 须先有 Schema Review 的专项批准 | 未批准前不写 Flyway、不改表、不改 API。批准后的自测见 Schema Review 第 5 节 |
| — | C01 | 不编码 | 无 | 固定候选 SHA 上复验空名称标签。不另开修复 |
| — | C07 | 不改手柄结构 | 无 | 点按改上级的现有测试保持通过 |

C05 与 C11 不放进同一次提交。每片一个可追溯的本地小提交。提交前跑该片点名的定向测试。阶段收口再跑受影响区域的完整测试。未执行的测试不写成 PASS。

## 4. 独立 Testing、Supervisor 与集中 UAT

切片自测不是 Gate。全部已授权 Core 切片进入同一本地 HEAD 后，才冻结一个候选 SHA。

其后只对该 SHA：

1. Coding Agent 做一次集成回归，范围是第 3 节点名的定向项，加上受影响模块的既有测试。
2. 独立 Testing 与独立 Supervisor 各读该 SHA、差异和证据。两个角色都不是编码会话。
3. 负责人做一次集中 UAT，覆盖 C02 时间、C03/C08 拖拽、C04 转账选账户、C05 符号与类型、C06 返回定位、C11 支出对方的创建/编辑/清空/详情，以及 C01、C07 的复验。收入、转账、余额调整不出现交易对方输入。

每项标注 PASS、FAIL、未测或延期。真机、人工深分页若本轮仍未做，保持未测，不写成 PASS。Gate 之后再有提交，就生成新 SHA 并重做适用测试。旧 PASS 不覆盖新 SHA。

候选 SHA 现在不指定。本文件不打标签。

## 5. Git 交付条件

最终交付同时满足下面几条，才可以另案讨论远端同步：

- 本计划与 C11 Schema 均已有独立批准记录。C11 若当时仍未批准，不得交付成完整 S1。
- 工作树干净，HEAD 等于第 4 节冻结的候选 SHA。
- 独立 Testing 与 Supervisor 对该 SHA 均已给出结论，未解决 P0/P1 为 0。
- 负责人集中 UAT 已有明确接受或退回。
- 证据目录记录该 SHA、测试命令和未测项。

即便上述条件成立，本计划也不授权 Push、新建远端分支、新建或更新 PR、合并 `main`、部署。治理 v1.6 §4.8 对既有 `release/v1.0` 的非强制 Push 不延展到 v1.2。远端动作需要以后的专项批准。

## 6. C11 与历史回填分开批准

| 事项 | 本计划中的位置 | 现在的状态 |
|---|---|---|
| C02–C06、C08 编码 | Core 切片 1–3 | 待本计划批准 |
| C01 复验、C07 保持 | Core，但不编码 | 随候选 SHA 复验 |
| V21 与 C11 API | Core 切片 4 | 另见 Schema Review，NOT APPROVED |
| 历史回填 | 待授权专项，不进入切片 | 不清库、不重导；不读取 Excel；不连接个人库 |
| 个人库备份、升级、整库回滚 | Schema Review 第 6–7 节 | 未授权。失败时不用 `DROP COLUMN` 充当回滚 |

回填规则沿用收尾计划 v0.2 第 4.1 节：账户＋描述＋业务时间优先；歧义时用分类、金额和类型；只写一对一的支出。先有匹配报告，再由负责人本机执行。执行前还要已批准的 V21 和已完成的个人库备份。这些都不因本计划获批而生效。

## 7. STOP

- 把本文件或 v1.1 收尾 v0.2 当作已批准的 v1.2 计划。
- 重新打开 v1.1 S1，或修改其结项原件。
- 在本计划批准前编码，或在 Schema 批准前实现 C11。
- 把历史回填、个人库迁移或生产 UPDATE 绑进本 Task。
- 在候选 SHA 冻结前做独立 Gate 或负责人 UAT。
- 缺少 C11 时把 Stage 标成 PASS 或 CLOSED。
- Push、新建远端分支或 PR、合并 `main`、部署、`Flyway.clean()`、关闭 `foreign_key_checks`。

## Change Log

- v0.1（2026-11-09）：由已提交收尾 Task Plan v0.2 最小差异改写为 v1.2 S1 初版完整计划候选。补上 Core、单 Coding Agent、定向自测、独立 Testing/Supervisor、集中 UAT、固定候选 SHA 和 Git 交付条件。历史回填保持为未授权专项。
