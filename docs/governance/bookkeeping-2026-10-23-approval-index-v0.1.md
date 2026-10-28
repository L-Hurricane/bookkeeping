# 04丨记账软件｜2026-10-23 统一审阅批准记录与文件索引 v0.1

- **批准日期**：2026-10-23
- **批准主体**：项目负责人
- **批准原话**：“文档都可以审阅通过。你可以标注为已通过，提供新文档，并且说明应该放到哪个地方。”
- **同时明确的实施边界**：“目前不打算立即更换Chat……只会在不得不更换时候进行更换，但是我需要你按照这个标准来做文档”；“其他我本地不需要临时文档，需要在功能完成后，比如合并代码之后，统一清理”。
- **性质**：本记录将已审阅的四份**原件**与衍生正式整理稿关联；不修改历史原件、不复用变更后的旧 SHA。

## 1. 统一通过的四份审阅稿与正式文件

| 事项 | 获批审阅原件 / SHA-256 | 依原稿整理的正式文件 / 独立 SHA-256 | 审批状态 |
|---|---|---|---|
| 跨 Chat 治理修订 v1.4-r3 | `bookkeeping-governance-cross-chat-handoff-review-v1.4-r3.md` / `e6212df6f906bffdcdc18cbec54a93284d44527bc04bcb8819bdd5854f272b2b` | `bookkeeping-ai-collaboration-governance-v1.4.md` / `0bc1fad859dca1129e60362e2ef9ba14e869630c73edb779bf9e298463b193b4` | Approved / 2026-10-23 |
| S1 Stage Plan v0.5 与 OBS-01 例外 | `bookkeeping-s1-stage-taskplan-closeout-review-v0.5.md` / `6865b6177567cc679d5235dbe012c9701383b96e33d23f7cbc410de269fb513f` | `bookkeeping-v1.0-stage-s1-plan-v0.5-approved.md` / `339d885f5913770fa1f10847316ad25579f585e1e2f517688a61c0af351accf8` | Approved / 2026-10-23 |
| 当前状态交接 v0.3 | `bookkeeping-current-state-handoff-2026-10-23-review-v0.3.md` / `be39329a5d2b1147de02df48101aaca4115d0026de1b9cbb07a56485a60c282b` | `bookkeeping-current-state-handoff-2026-10-23-v0.3.md` / `c435867517495e0b2bd7174f7901598f34d606bcc2a8b37503027dc2eaaf45ff` | Approved / 2026-10-23 |
| 本地工作区整理方案 v0.1 | `bookkeeping-repository-workspace-cleanup-review-v0.1.md` / `ec91ac38d30375e8c9f50a58fe12d3333cf9cca3f30e9ba5f185cbd9725b2486` | `bookkeeping-repository-workspace-cleanup-v0.1-approved.md` / `3f5863abc4452bf7953601b8c9c9e960d9a6d852ebb853f2575c2e2987ced9e5` | Approved / 2026-10-23 |

其中治理全文 `bookkeeping-ai-collaboration-governance-v1.4.md`、项目指令 `bookkeeping-project-instructions-v1.4.md`、阶段结项记录 `bookkeeping-s1-stage-closeout-v0.1.md`均为本次批准内容的正式落地成果；以下补充其各自 SHA：

- `bookkeeping-ai-collaboration-governance-v1.4.md` — `0bc1fad859dca1129e60362e2ef9ba14e869630c73edb779bf9e298463b193b4`
- `bookkeeping-project-instructions-v1.4.md` — `b5a58612fe484d62016f640197fa406bb77ee882efc8a419dd6d2ff989cad6a4`
- `bookkeeping-s1-stage-closeout-v0.1.md` — `2becd637de75e27a3b1580f69587f715fa4e2fd90f11c352131ec1fc896bee7e`
- `bookkeeping-current-state-handoff-2026-10-23-v0.3.md` — `c435867517495e0b2bd7174f7901598f34d606bcc2a8b37503027dc2eaaf45ff`

## 2. 审批范围与遗留授权

1. **治理 v1.4 Approved**，由 v1.3 接替；项目设置实际更新和共享文件上传尚未核实。按功能增量整理结项资料，但**不强制换 Chat**。
2. **S1 Plan v0.5 Approved**，S1 以“CLOSED（OBS-01 一次性延期）”登记；原 v0.4 全部其他技术/安全约束不变，不能把延期例外扩展到任何其他问题。OBS-01 最晚 v1.0 正式 UI/产品验收前修复并回归；后续 Stage Task 仍待新审批。
3. **状态文件 v0.3 Approved**，是当前事实入口，允许日后事件驱动更新，绝不是新的代码执行授权；代码 `5ca8216` 仅截图缩写，完整 SHA 和最新 Git status 待现场核实。
4. **本地目录收敛方案 v0.1 Approved**，**清查/清理推迟到完成本功能合并和必要归档后**；方案审批不授权直接删除、移动、prune、强行清理或修改历史。
5. **远端 push、创建 PR、main merge、生产部署仍未在本次统一审阅中获授权**；如有需要，按既有安全流程另行授权。资料在 Git `docs/` 入库需获授权工程或文档维护任务处理。
6. 本次不创造本地临时档案，也不要求用户为交接立即迁移到新 Chat。

## 3. 存放与生效

- **ChatGPT Project 共享资料**：治理 v1.4、现行状态 v0.3、S1 v0.5 和此批准索引为优先可访问副本，S1 结项和目录收敛方案按需同存；已通过的 PRD/UI/架构继续保留。旧版作为历史，不与现行混列。**当前尚未验证已上传。**
- **Project 设置 → 项目指令**：由负责人用 `bookkeeping-project-instructions-v1.4.md` **正文整体替换**旧版；设置尚未实际更新。不要仅上传此文件来替代项目指令输入框操作。
- **业务 Git `docs/`**：待获得相应文档归档授权及本功能代码集成后，将正式资料与批准原件/索引按业务需要分别归档到 `docs/governance/`、`docs/implementation/`；核验原件 SHA 与批准记录一致，不直接往主分支写入。**尚未执行。**

## Change Log

| 版本 | 日期 | 变化 | 状态 |
|---|---|---|---|
| v0.1 | 2026-10-23 | 登记四份统一 Review 原件哈希及正式文件哈希，明确 S1 结项、治理升级、当前状态与合并后清理边界 | Approved / 独立审批索引 |
