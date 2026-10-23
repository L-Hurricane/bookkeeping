# 04丨记账软件｜2026-10-23 审批记录与文件索引 v0.5（现行）

- **状态**：Approved / 记录已经发生的负责人批准事实；不是新的开发、Git 或生产授权。
- **本版本相对于 v0.4**：继承此前全部审批和 SHA 链；新增负责人批准 S2 首版完整 Stage/Task Plan Review v0.1、明确 M1 后暂停与复盘，并登记正式计划及唯一现行状态 v0.8。v0.1～v0.4 均作为历史原件保留。
- **负责人原话**：第一轮“文档都可以审阅通过。你可以标注为已通过”；第二轮“前边两个文档已经审批通过，没有问题。生成对应文档，我放到项目的公用空间中”；第三轮对权限 Review v1.6 回复“同意，你修改文档（做一下放入目的地说明），我来放入项目空间中”，并单独明确 S2 M1/M2 的试点要求；第四轮对 S2 范围 Review v0.1 回复“审阅稿我确认，没有问题，生成正式文档，并说明需要放在哪里。目前Cursor还在执行中”。
- **来源边界**：已获批准的 Review 原件及其 SHA 保留不变，正式衍生文件另行计算 SHA；GitHub 状态来自 Cursor 回报而非本 Chat 独立现场调查。

## 1. 已获负责人批准的审阅原件及批准时 SHA

| 原件（保留原字节） | SHA-256 | 批准事项 |
|---|---|---|
| `bookkeeping-governance-cross-chat-handoff-review-v1.4-r3.md` | `e6212df6f906bffdcdc18cbec54a93284d44527bc04bcb8819bdd5854f272b2b` | v1.4 治理获批原件 |
| `bookkeeping-s1-stage-taskplan-closeout-review-v0.5.md` | `6865b6177567cc679d5235dbe012c9701383b96e33d23f7cbc410de269fb513f` | S1 v0.5 Stage Gate 例外获批原件 |
| `bookkeeping-current-state-handoff-2026-10-23-review-v0.3.md` | `be39329a5d2b1147de02df48101aaca4115d0026de1b9cbb07a56485a60c282b` | S1 原初结项动态状态的获批 Review 原件 |
| `bookkeeping-repository-workspace-cleanup-review-v0.1.md` | `ec91ac38d30375e8c9f50a58fe12d3333cf9cca3f30e9ba5f185cbd9725b2486` | 获批目录收敛方案原件 |
| `bookkeeping-cross-chat-lead-handoff-protocol-review-v0.1.md` | `5466857cb12dd4dd54ba21f086b9f9b4b8af6e178b811cf691f34e07e5796671` | 新增获批组长岗位接续规范原件 |
| `bookkeeping-current-state-handoff-2026-10-23-v0.4.md` | `acb3e9a987186d59754a7119f5f61939b5ad2a44ef4f89c1b49cf161eda9a4d1` | 负责人批准的当时动态状态（现已过时） |
| `bookkeeping-governance-decision-delegation-review-v1.6.md` | `735f7c95c9926be98858cca86cdd3afceeab237393cd2fb4b6584cd15c4eb8a3` | **负责人明确 Approved**；原 Review/Pending 字节保留，独立登记批准 |
| `bookkeeping-s2-two-milestones-scope-review-v0.1.md` | `f04b49effd3216b911e9e3ad877901f9bc30169b4993e2db8b6c83638f3a4bc1` | **2026-10-23 负责人明确 Approved：S2 M1/M2 功能范围与 Gate；不含首版完整 Task Plan 或编码授权** |
| `bookkeeping-v1.0-stage-s2-plan-taskplan-review-v0.1.md` | `629dd55f3f891b8ae10faebd87f40a55035f91c311719467dc3dc20b4617f9db` | **2026-10-23 负责人明确批准 S2 首版完整 Stage/Task Plan**；明确 M1 完成后必须暂停并进行简短复盘；正式数据库 DDL 仍须专项审批 |

原 v1.4 获批规范、S1 Gate 例外及本地整理方案的详细批准范围继续以历史 `bookkeeping-2026-10-23-approval-index-v0.1.md` 为据。本 v0.5 只是维护**索引入口**，不覆盖任一批准原件。

## 2. 正式衍生文件与现行 SHA

| 文件 | SHA-256 | 现行角色 / 生命周期 |
|---|---|---|
| `bookkeeping-ai-collaboration-governance-v1.4.md` | `0bc1fad859dca1129e60362e2ef9ba14e869630c73edb779bf9e298463b193b4` | 历史正式治理，被 v1.5 接替 |
| `bookkeeping-project-instructions-v1.4.md` | `b5a58612fe484d62016f640197fa406bb77ee882efc8a419dd6d2ff989cad6a4` | 历史正式设置，被 v1.5 接替 |
| `bookkeeping-v1.0-stage-s1-plan-v0.5-approved.md` | `339d885f5913770fa1f10847316ad25579f585e1e2f517688a61c0af351accf8` | 继续有效的 S1 结项计划 |
| `bookkeeping-s1-stage-closeout-v0.1.md` | `2becd637de75e27a3b1580f69587f715fa4e2fd90f11c352131ec1fc896bee7e` | 继续有效的 S1 结项记录 |
| `bookkeeping-repository-workspace-cleanup-v0.1-approved.md` | `3f5863abc4452bf7953601b8c9c9e960d9a6d852ebb853f2575c2e2987ced9e5` | 清理策略已批准，执行清理未授权 |
| `bookkeeping-current-state-handoff-2026-10-23-v0.3.md` | `c435867517495e0b2bd7174f7901598f34d606bcc2a8b37503027dc2eaaf45ff` | 历史状态，后有 v0.4、v0.5 |
| `bookkeeping-ai-collaboration-governance-v1.5.md` | `8e0912a5e3e8377d0f264f6e0ff2e98a3516a88a16c5e32460ec183b311b120f` | 历史治理，已由 v1.6 接替 |
| `bookkeeping-project-instructions-v1.5.md` | `9d517933a3fe22fa874085d32b7e209cae67111002405fc5518d7e6c2799c40e` | 历史项目指令，待负责人用 v1.6 替换设置 |
| `bookkeeping-cross-chat-lead-handoff-protocol-v0.1-approved.md` | `f324a5cb09fce6659385852d6dff0e98b020d916c8080dddb531c547dba7dbb1` | 组长接任操作规范，已吸收入治理 v1.5 |
| `bookkeeping-current-state-handoff-2026-10-23-v0.5.md` | `d2d0569a8b42647ba8865bbc5430db9db0d62661cf6614baede7d266b14bd71f` | 历史动态状态（PR #2 回执），由 v0.6 接替 |
| `bookkeeping-ai-collaboration-governance-v1.6.md` | `364a2dc1c22ea9d3dce7ed209d77fdc36ed90fe759417acdedca10d19c9e59d1` | **当前正式治理**，吸收已批准自主决策与安全边界 |
| `bookkeeping-project-instructions-v1.6.md` | `450bc6feda551c3f8af80f495ac810418a5089d9ce291cfd4c5aaa151a6fb697` | **当前项目指令正文**，待负责人替换设置 |
| `bookkeeping-current-state-handoff-2026-10-23-v0.6.md` | `62f3556af19c46f00209aa73f99ca6bce46ca9ef42cf0e5b3da06738ffdfb7d1` | 历史动态状态，已被 v0.7 替代 |
| `bookkeeping-s2-two-milestones-scope-v0.1-approved.md` | `b2c4d2abf452d87ab34b1930fe4679d2b8036c8ce8849b9cd4b0b1b0cfc664a7` | **现行已批准 S2 M1/M2 范围与 Gate；不是完整执行计划** |
| `bookkeeping-current-state-handoff-2026-10-23-v0.7.md` | `8b9d0bb36f10a4ccd72a493634539f9df2e54e64b13f8d2f3e4d37c4432d4c50` | 历史滚动状态；已由 S2 批准后的 v0.8 替代 |
| `bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md` | `69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5` | **当前获批 S2 首版完整执行计划正式版**；原 Review SHA 单独保留；必须在 M1 暂停 |
| `bookkeeping-current-state-handoff-2026-10-23-v0.8.md` | `65c839c278979f68fa82ea3a6fbe6c04dee0bc8625a4edf0011f5cca7aebbac4` | **唯一现行滚动状态**：S2 Plan 已批、T00/T01 待启动、M1 必须暂停 |

## 3. Git、PR 与后续授权边界

- **S1 CLOSED**（仅 `S1-T08-OBS-01` 依据 v0.5 已获批准延期，最晚 v1.0 完整 UI/产品人工验收前修复）；不代表所有历史 P2/P3 或排除测试已解决。
- 负责人已另行授权本轮 Push 和创建 Draft PR；Cursor 报告 GitHub **PR #2** `https://github.com/L-Hurricane/bookkeeping/pull/2` 已创建，`release/v1.0` → `main`，HEAD `663e76d4b1e1ac6a7979211299fd7225dfb3236d`。对 `main` 无合并/直接改动，CI 无检查结果，PR 当前 Draft/BLOCKED。API 发布路径的独立核验及安全审计仍在待办，详见现行状态 v0.8。
- 同一 Draft PR 计划用于 S2 及后续 Stage 的受控累计交付；**S2 M1/M2 范围与首版完整 Stage/Task Plan 已获批准，T00/T01 可启动；正式 DDL、真实数据迁移仍须专项审批。M1 自动 Gate 后必须停下向负责人报告和复盘，取得确认后才能开始 M2。** 治理 v1.6 仅对已批准 Stage 和必要 Gate 的 `release/v1.0` 有界非强制 Push 授权。
- 完整目标版本 v1.0 结束前不提前合并；**main 最终合并仅由负责人在 GitHub 网页执行；部署与本地文件删除均未获授权**。长期 PR 不等于替代独立 Stage Gate。

## 4. Project、Git 与变更时点

1. 在 Project 设置中将完整 Project Instructions v1.6 **正文**替换 v1.5；仅上传文件不能替代项目指令更新。
2. Project「可用来源」以治理 v1.6、唯一现行状态 v0.8、正式 PRD/UI、S1 Plan/Closeout、此**统一审批索引 v0.5** 为主要接续资料；可以按需保留接任规范已批准版 v0.1。旧治理/旧状态/旧索引退出现行角色，但历史原件在可恢复的归档中保留。S2 范围和首版完整计划均已正式批准，Project 应新增 `bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md` 并继续保留 `bookkeeping-s2-two-milestones-scope-v0.1-approved.md`；Review 原件保留历史，不再充当现行执行版。
3. 经授权的 Cursor 文档维护任务在业务 Git 的 `docs/governance/` 归档正式文件及必要原件；Git 归档与 Project 上传**没有自动同步**，不得将本次文件生成误记为已 Push 到 PR #2。
4. 当前 Chat 继续推进，不为文档整理强迫切换。重大批准、PR/Gate、版本状态变化时刷新唯一动态入口，避免新 Chat 读到过时授权。

## Change Log

| 版本 | 日期 | 变更 | 状态 |
|---|---|---|---|
| v0.1 | 2026-10-23 | 四份原始 Review 统一批准，S1/治理和整理方案正式化 | 既有批准历史 |
| v0.2 | 2026-10-23 | 新增组长职责接任获批原件与正式治理/指令 v1.5；状态 v0.4 已批准但被含 PR #2 的 v0.5 取代 | 历史索引 |
| v0.3 | 2026-10-23 | 新增组长自治 Review v1.6 的负责人批准、正式治理/指令 v1.6 SHA、状态 v0.6、S2 两里程碑当时待确认责任与放置指引 | 历史审批索引 |
| v0.4 | 2026-10-23 | 新增 S2 双子里程碑 Review v0.1 的明确批准及原 SHA、正式范围文件 SHA、状态 v0.7 和 Cursor 调查在途的事实 | 历史索引 |
| **v0.5** | **2026-10-23** | 新增 S2 初版完整 Plan Review v0.1 明确批准、M1 强制暂停点、正式计划/状态 v0.8 的 SHA 索引 | **Approved / 现行审批索引** |
