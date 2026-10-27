# 04丨记账软件｜唯一现行项目状态与跨 Chat 交接记录 v2.6（待 Git 核验同步）

- 日期：2026-10-27
- 状态：**S6-T07 独立双 PASS（依据负责人转述的 Cursor / Testing / Supervisor 现场回执）；S6-T08 技术准备完成、最终人工 Gate 尚未由负责人 PASS/FAIL；S6-T09 未开始；S6 未 CLOSED**。
- 与 v2.5 的变化：补入最新 `9501b77` 技术准备及定向补测事实；记录 S6-T08 两项保留授权正式获批。旧 v2.5 的“分类、方案 B、全量分页、微信模拟器未测”信息已被新的回执更新，不可作为当前阻断项。
- 证据边界：本 Chat 已核验所附 Review SHA，但**未直接访问本地 Git、独立 Gate 全量原件、MySQL、微信或外部模型**。Git/数据库/测试运行情况来自负责人贴回的现场回执；Cursor 应核实本地事实并将唯一正式入口 `docs/CURRENT.md` 更新/归档。本文件是待同步的现行候选，不声称已写入业务 Git 或 Project。

## 1. 治理、批准与阶段

- 治理：`bookkeeping-ai-collaboration-governance-v1.8.md`；初版完整 S6 Approved v0.3（SHA-256 `8619081f735ed6a73ab2552d5eca6cac8e0299ad6488f83a0a41ddafa68b20d7`）；UI Freeze Diff Approved v0.1；UI Scope/Gate Amendment Approved v0.5；`bk_s6_family_e2e_20261027` 独立数据库批准仍有效。
- **新获批准**：`bookkeeping-s6-t08-remaining-authorizations-review-v0.1.md`，原件 SHA-256 `b15181c76be0b26b3a912cb7b8476bbfc887508488694f5e9a6a13aba9f8c0f7`，负责人在两项联合审批请求后回复“同意”；正式独立记录 `bookkeeping-s6-t08-remaining-authorizations-approval-record-20261027-v0.1.md`。
- 新权限：合成数据隔离环境内的 `qwen3.7-flash` 最多三次真实调用，预计费用合计不超过 ¥0.20；可在限界内只读既有 DashScope 秘钥并临时注入隔离进程；同一可信 LAN 临时让负责人本人手机连接 `18081`（若合法且安全可行）；不能公网开放、第三方隧道、修改旧库/旧端口、生产部署。第三次 `s6_family_owner` 调用只由负责人亲自点击触发。
- 不得提前批准/代签 T08 PASS、合并 main。T09 按原 S6 Gate 和 v1.8 §4.8 处理，既有 release/v1.0 非强制 Push 不因本次扩权。

## 2. 最新 Git 与质量证据（均来自回执）

| 项目 | 最新事实与限界 |
|---|---|
| S1～S5 | 已 CLOSED（既有证据） |
| UAT 工作分支/工作区 | `uat/v1.0-s2`，回执称工作区干净；HEAD `9501b77b7a66de669a75f26d91ab18d542c79d56` |
| 已完成全量家庭数据 Gate 产品基线 | `58557e9ed0dcb68a20b5b83d7b361f15e104c5df`（含金额颜色修复）；`6b35529` 为仅 Gate/Backlog 文档 |
| T08 环境地址产品改动 | `9501b77` 对小程序 dev/test 接口地址由本机 `8080` 改成隔离 `18081`，据回执新变动经 `S6 T08 directed retest` 和 `S6 T08 directed supervisor` 双 PASS |
| T07 | `S6 family independent testing` PASS + `S6 family independent supervisor` PASS；P0/P1/P2=0，P3 原为 `S6-DATASET-P3-01` |
| T08 | 技术准备完成，负责人尚未亲自体验；无 T08 人工 Gate PASS/FAIL |
| Git / release | 本次无 Push、Merge 或部署；尚未开始 T09 |

## 3. 隔离数据与新增定向验证

- MySQL 8.4.3，仅专用合成库 `bk_s6_family_e2e_20261027` 可写，Flyway V1–V18；两个用户共 12590 笔普通交易，试用用户 `s6_family_owner` 约 6295 笔；数据版 `s6-family-e2e-v0.1`、seed `20261027`，自动化 `s6_family_auto`。日常 `8080` 与五个旧库据回执未变；服务 `127.0.0.1:18081`。
- 补测：2025-11-01～2026-10-27 两用户分类树与独立事件预期一致，未分类 25512.52/277 笔；2026-09 餐饮下钻 134 笔；自动用户全量分页 126 页/6300 行/2145ms 且 key 不重复；重复请求/同键并发幂等、不同键并发独立成功；三级分类正负和净额 21.00 场景；方案 B 先 409 归档后 204/对方账户余额恢复/跨租户 404。以上为现场回执，未在本 Chat 直接重跑。
- `S6-DATASET-P3-01`：据回执自动用户 `生活→超市/人情` 已在合成数据层修正并验证父金额 103353.55、1103 笔；试用用户未重建，临时数据已移除；须在本地 Backlog 标为“已定向补测处理”，不要无据声称业务源码存在 P3 漏洞。
- 模拟器实测：微信开发者工具登录 owner；6 正常账户、首页净值 -189387.22、当月收入 34000.00、支出 48067.18，全部交易第一页 50 行；这**不是手机真机**。真机及真实外部 Qwen 成功仍需按新增授权执行。
- 备份：`/tmp/bk_s6_family_e2e_20261027-before-t08.sql`、SHA-256 `269beb2a8d440302fd6c241e7554ab923e981b9e841e809808e056f55586e648`；原备份 `/tmp/bk_s6_family_e2e_20261027-pre.sql` SHA-256 `66a2c3f7dba80bb792dc92f6d5002817c83671db60885e2b9a0c0e1cebd61c8e`。不得误写为全库完整灾难恢复演练，也不得自动覆盖 owner 试用数据。
- 小程序 245 测试 PASS、typecheck/lint PASS；后端指名单元 90 PASS（来自此前回执），未将 Stub 误报真实供应商成功。性能样本：本月统计 846.4/557.0ms、全账交易首页 67.8ms、名称搜索 13.6ms、近一年 188.9ms；非 p95/SLA。

## 4. 待做、负责人最小介入与 Stop

1. **Cursor（下一执行方）**：依据 `bookkeeping-s6-t08-authorized-model-lan-cursor-handoff-v0.1.md` 对授权原件/SHA、Git/Gate、隔离数据库做最小预检；在授权内完成自动用户的月/年真实 Qwen 生成及回读（不重试）；有界尝试可信局域网真机连接。若无法安全配置 LAN，标明 NOT TESTED，不申请公网暴露。
2. **Testing / Supervisor**：复核真实模型返回、快照/配额及任何必要的新产品代码受影响范围；实测与未测要分层留证。若无新代码不增全量 Gate。
3. **负责人（到真实人工 Gate 时）**：像普通家庭账本用户一样在模拟家庭账本里操作、评价产品是否正确/顺手；`s6_family_owner` 唯一可选第三次真实月度报告须负责人主动点击；最终亲自明确 T08 PASS/FAIL。不要让负责人承担全量专业测试。
4. **T09**：仅在 T08 人工 PASS 等原定条件成立后执行结项、正式状态/Backlog/SHA 索引及既有 release/PR 工作。`main` 仍仅由负责人通过 GitHub 网页自行合并；生产部署需另外批准。

**STOP**：越权敏感读取、模型或费用无法确认、实际外发内容超过合成汇总白名单、网络出口可能公网暴露、访问旧库/服务、账务或租户隔离 P0/P1、无法保留恢复点；不得以本次“同意”推定其他对象的权限。

## Change Log

- v2.6（2026-10-27）：纳入 T08 定向补测、微信模拟器真实加载家庭账本、未测真实 Qwen/真机，以及本次两项保留审批通过的事实；保留 Stage 未 CLOSED、人工 T08 尚未 PASS 的事实。
