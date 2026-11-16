# 04丨记账软件｜v1.1 S1 结项核对记录 v0.1

- 日期：2026-11-06
- 结项状态：**S1 NOT CLOSED**
- 固定人工验收 SHA：`786c038d6b09eaa426820d3027f2cb2ca1e0254e`
- 本地标签 `v1.1-s1-t11-r2-uat-candidate` 指向该 SHA。更早标签 `v1.1-s1-t11-uat-candidate` 仍指向 `c5aeeae101cae9b3f3b792e219422e531b28e8dd`，`v1.1-s1-t09-uat-candidate` 仍指向 `3f5c065b9dc36e2130e8a6e1a60aa1c6c84df105`。
- 这不是合并 `main`，不是 Push，不是生产部署，也不是个人账本已重导。
- 本记录不修改冻结 PRD、UI Freeze、已执行 Flyway 或业务源码。

## 负责人 2026-11-06 决定

交付主 Chat 转述负责人决定：接受 M01 全历史流水、M03 转账名称、M02 账户分组的本轮核心功能验收。

明确不是实操 PASS、继续延期或未测：

- 分类／标签拖动改上级的实际交互体验不理想，接受延期，不能记为实操 PASS。
- 账户拖动时其他卡片平滑让位的动画延期。
- O01 同级自定义排序继续原有 P2 延期。
- 历史流水深分页保存后的全局定位等原有 P2、真机、人工深分页，继续未测。

## 关闭条件核对

获批计划 T12 的进入条件是 T11 PASS，且所有前置 Gate 满足。T10 写明：候选 SHA 变更后要重做适用核验，不能用研发自测代替。

| 条件 | 证据 | 判定 |
|---|---|---|
| v1.0 S6 CLOSED | `docs/implementation/bookkeeping-s6-stage-closeout-v0.1.md`。结项提交 `39655070a99cb802433989a6d310ad268a1dcd2e` 是 `origin/main` `c6b2d0c7eae57d642a78f3cc3bcf4a4799542610` 的祖先 | 达标 |
| M03/M02 正式 Schema 已批准，V1–V18 未改 | 批准记录 SHA-256 `fc1ccee5f5f961dfb24c05c4c794ff687a740356528835f54ac1cd80c84ab0de`。V19 SHA-256 `962e9d9960b71576ae73c4dfbf984f84e730033dfaa143cd0ec7282d48d30218`。V20 SHA-256 `d045172081fd87dd854c5461d784b778b9bfa64f46be19753277deb8a8064691` | 达标。不授权把这两支迁移应用到个人库 |
| T09 研发自测 | `3f5c065` 上授权库 `bookkeeping_test`：后端 338 通过、0 失败、9 跳过；当时小程序 261 通过。其后到 `786c038` 只有小程序修复。`786c038` 提交前小程序测试 266 通过、0 失败，typecheck 与 lint 通过 | 研发自测达标。不是独立 Gate |
| T10 独立双 PASS 覆盖最终 SHA | 交付主 Chat 曾确认 `3f5c065` 上 Testing 与 Supervisor 均 PASS。其后候选变为 `c5aeeae`。交付主 Chat 转述该 SHA 的独立结果是 Testing FAIL、Supervisor HOLD，报告 SHA-256 `afe45aa94653ebebeffc82441f8a77857bd497044cfd739fc5b68b55a8e71b33`。仓库内没有这份报告原件。`786c038` 没有新的独立 PASS | **缺口。** 不满足「SHA 变更后重做适用核验」 |
| T11 负责人 UAT PASS | 核心三项已接受。分类／标签拖动体验、让位动画、O01、真机和人工深分页没有被接受为 PASS | 核心三项达标。整个 T11 不是无例外 PASS |
| 未测没有写成 PASS | 本文件与 `docs/implementation/v1.1-s1-closeout-backlog-v0.1.md` | 达标 |
| P0/P1 不留在 CLOSED 中 | 标签行缺少 `data-id` 的 P1 已由 `786c038` 修复，但独立 Gate 尚未在该 SHA 上复验。拖动体验延期不是把该 P1 重新打开 | 功能修复已提交。独立复验未完成，因此不能标 CLOSED |

## 缺口

S1 不能标 CLOSED。缺的是对 `786c038d6b09eaa426820d3027f2cb2ca1e0254e` 的独立 Testing 与 Supervisor 适用复验，至少覆盖标签拖动命中和已改的四项界面。负责人已接受的三项核心功能不需要重做成未验收。

复验 PASS 且没有新的 P0/P1 之后，才能把本记录的状态改为 CLOSED。在此之前不 Push、不 Merge、不部署、不重导个人账本。

## Git

写入时分支 `v1.1/s1-m01`，产品候选 HEAD `786c038d6b09eaa426820d3027f2cb2ca1e0254e`。没有上游推送。`origin/main` 仍按 S1 开工时核对的 `c6b2d0c7eae57d642a78f3cc3bcf4a4799542610` 理解；本结项核对没有再次访问远端。
