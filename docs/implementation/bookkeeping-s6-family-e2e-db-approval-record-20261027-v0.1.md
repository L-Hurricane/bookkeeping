# 04丨记账软件｜S6-T07 专用合成家庭账本隔离库批准记录 v0.1

- 日期：2026-10-27
- 状态：**APPROVED（严格限定具体数据库对象和所述操作）**
- 审批主体：项目负责人。
- 审批直接事实：组长明确提请负责人决定“是否批准上述专用隔离库及限定操作？”，并给出 `bk_s6_family_e2e_20261027`、现有五库不可写、非生产身份核验、现行 Flyway、合成数据、备份恢复、禁止提权和其他外部动作等范围。负责人答复：**“批准”**。
- **批准绑定的原 Review**：`bookkeeping-s6-isolated-family-e2e-db-authorization-review-v0.1.md`；原件 SHA-256：`d3959800aa6eff3dd370fbd306c54233bd4dbcf607197b8078b3a6603cc1f968`。Review 原字节和其正文 PENDING 留存不改；本记录是独立的批准事实凭据，不以修改 Review 获得“批准版”。
- 继承：治理 `bookkeeping-ai-collaboration-governance-v1.8.md`，原 S6 Stage/Task Plan Approved v0.3（原 Review SHA `2ca8c1a2c44a55b85f9e4d09900e2bc39bb85a5cde99973a339b7bd47adb116a`，Approved v0.3 SHA `8619081f735ed6a73ab2552d5eca6cac8e0299ad6488f83a0a41ddafa68b20d7`），UI Scope/Gate Approved v0.5 和既有 T07/T08 门禁；不形成新 Stage/Gate。

## 1. 批准对象与边界

**唯一允许新建及写入的数据库名：** `bk_s6_family_e2e_20261027`，仅当执行人实地确认目标是既有本机**非生产 MySQL**，有合法既有权限，库名此前不存在，且应用和 Flyway 配置确实显式指向这个库时才可创建。

在该专用库内允许：初始化经核验未修改的现行 Flyway V1～V18；准备固定 seed/version 的纯虚构家庭账本、隔离自动化用户及负责人试用用户；可恢复的必要合成数据操作；针对该库运行真实 HTTP/MySQL、独立账务对账、性能采样和可用的微信 UI 自动化；受控基线备份和**经过验证**的恢复演练（目标 RTO≤10 分钟，仅是目标）。所有实际动作须有命令/配置脱敏、结果和恢复证据。

**明确排除现有五库**：`bookkeeping`、`bookkeeping_test`、`bookkeeping_migration_test`、`bk_s1t03_6928fd20_test`、`bk_s1t03_6928fd20_migration`，均不可针对本次测试写入、迁移、清空或覆盖。不能默认连接旧 `127.0.0.1:8080` 应用就声称已经指向新库；启动 Spring 之前必须验证其 DB 配置，旧服务与新测试服务隔离，确保无隐式 Flyway 误迁移。

不授权新增正式 Schema/DDL 或修改 Flyway 历史；不授权真实/生产数据、真实付费 AI、扩权、敏感凭据传播、其他数据库、Git Push/新 PR/main 合并/部署或破坏性清理。若同名库已存在、MySQL 实例身份不明、缺合法 CREATE 权限、连接不能可靠隔离、Flyway 不匹配、恢复点不可信或必须提权，则仅该危险步骤 **STOP** 并提出最小缺口，不自行绕行。

模拟家庭用户一旦交付负责人试用，其数据不得被 Agent 未经其同意重置、覆盖或删除。回归测试使用独立合成身份；本授权也不包含未来删除数据库。

## 2. 批准后的执行与结项责任

1. **Cursor / 唯一 Coding Agent**：环境只读预检→确认授权对象→建库及 Flyway 受控初始化→独立身份的合成数据准备→备份/恢复验证→必要的当前范围内 `UI-P3-COLOR-01` 修复；记录最终 HEAD、种子、数据版本、分型笔数与真实环境信息（脱敏）。不得让 Coding 自行签署独立 Testing/Supervisor。
2. **独立 Testing Agent**：以原始事件独立计算预期值，实际执行真 MySQL + HTTP/E01～E14，以及可用的 E15 自动化；记录未测、性能和真实成功/失败；不可将本地 Qwen Stub 视为真实供应商验证。
3. **独立 Supervisor Agent**：复核环境权限与恢复、租户隔离、资金/统计口径、覆盖与缺陷分级，仅对最终产品 HEAD 和新测试证据出具结论。
4. **组长**：审核回执，维持 T07 **BLOCKED** 直至独立 Testing/Supervisor 双 PASS、P0/P1=0 且必要的端到端证据完备；再组织已批准的 T08 人工 Gate 交接。T08 未自动开始，真实 Qwen 的外部调用仍须另行具体授权。

## 3. 本次批准与未执行事实

负责人批准的是**可以由 Cursor 在受限环境实施的动作**，不是“已经创建数据库、已经生成 6,000 笔交易、已通过恢复或 E2E”。截至本记录生成，执行回执未收到：目标库状态 **NOT EXECUTED / 未核验**，受审旧 HEAD 仍为 `78de4503425a533abbedfa4cc99c6e584093116d`，T07 **BLOCKED**，T08 **未开始**。

本记录将与上述 Review 分别留存。任何实际同步到业务 Git 或 ChatGPT Project，均须以后续真实操作与回执为据。

## Change Log

- v0.1 / 2026-10-27：记录负责人明确“批准”，绑定 Review 原 SHA，新增指定隔离库的有界授权；不增正式 Schema、质量 Gate、Git、生产或外部 AI 权限。
