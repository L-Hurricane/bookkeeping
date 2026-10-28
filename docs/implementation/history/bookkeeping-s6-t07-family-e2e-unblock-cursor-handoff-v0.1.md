# Cursor 接力｜S6-T07 合成家庭 E2E 解阻与原 Gate 复验 v0.1

- 状态：**可按已批准范围执行**，但数据库/代码现场与授权先决条件必须实测；本文不是测试 PASS。
- 负责人批准记录：`bookkeeping-s6-family-e2e-db-approval-record-20261027-v0.1.md`，绑定原 Review `bookkeeping-s6-isolated-family-e2e-db-authorization-review-v0.1.md` SHA `d3959800aa6eff3dd370fbd306c54233bd4dbcf607197b8078b3a6603cc1f968`。
- 现行 Stage/Gate：原 S6 T07 独立 Testing/Supervisor **BLOCKED**；T08/T09 不得擅自开始。受审 HEAD `78de4503425a533abbedfa4cc99c6e584093116d`，工作区 `uat/v1.0-s2`（来自 Cursor 回执，执行前必须重新确认）。
- 依据：治理 v1.8、`bookkeeping-v1.0-stage-s6-ui-final-plan-v0.3-approved.md`、`bookkeeping-v1.0-s6-ui-scope-gate-amendment-v0.5-approved.md`、`bookkeeping-v1.0-s6-e2e-family-uat-test-design-v0.1.md`。原件有冲突时以正式治理及后续批准记录为准。

## 一次连续执行任务（不要拆成逐命令求批）

**A. 安全预检：**检查 `docs/CURRENT.md`、当前 HEAD/工作区、仅经授权的本机非生产 MySQL 实例身份与权限、库列表。确认 `bk_s6_family_e2e_20261027` 不存在。以下五库严格只读且不得让任何测试/迁移误连接：`bookkeeping`、`bookkeeping_test`、`bookkeeping_migration_test`、`bk_s1t03_6928fd20_test`、`bk_s1t03_6928fd20_migration`。检查全局/环境变量/配置覆盖路径，防止旧 `127.0.0.1:8080` 进程或 Flyway 将应用连接到旧库；验证端口及实际数据源指向，若不能隔离则 STOP 有风险部分。无权 CREATE 时不要 sudo 或要求 DBA 凭据自行升级。

**B. 合法目标库：**仅针对 `bk_s6_family_e2e_20261027` 执行新库创建，核验 Flyway 历史 V1～V18 原字节，受控初始化；无需也不得新增正式 Schema/DDL。建立独立的可丢弃自动化测试身份与可保留的负责人试用身份；避免自动化跑数据重置影响试用。先建立可信备份/恢复机制（RTO≤10 分钟是目标），恢复实验只能在该库经识别的可丢弃基线上执行，保留前置恢复点，不允许 `Flyway.clean`、DROP 非授权库、不可恢复覆盖。

**C. 数据与测试：**使用固定 seed/version，约 12 个月、每完整月约 450～550 笔消费、总约 6,000 笔的纯虚构工薪家庭账本；工资、兼职、转账双业务时间、余额调整、负余额、三级分类、父子多选标签及去重、无标签/未分类、归档、边界日期。测试环境中用已许可的真实业务写入路径/受控初始化，但独立 oracle 必须基于合成事件计算预期值，不能通过待测后端统计 API 反算作为 expected。测试 E01～E14，记录 HTTP/JDBC/MySQL 层实际覆盖，性能数字和失败；检查 E15 微信自动化可行范围，不能执行则 `NOT AUTOMATED`。Qwen 只能使用 Stub；真实供应商 `S6-AI-GEN-01` **未获本次授权**。

**D. 范围内修复：**既有 `UI-P3-COLOR-01` 在同一次受审 HEAD 窗口进行最小正红负绿样式修复并验证；如出现已批准业务范围内的 P0/P1，单 Coding Agent 串行最小修复，独立定向复验；不要重启一个不必要的新 Gate。UI-B01 账户分组、UAT-03 逐笔余额继续延期，不实施。

**E. 独立 Gate 与停止：**Coding Agent 提交最终可恢复 HEAD + 自测记录后，必须由与 Coding 实质隔离的 Testing Agent 先验证，Supervisor 再独立审核同一最终 HEAD；提交各自 PASS/FAIL/BLOCKED、缺陷等级、覆盖/未测、账务 oracle、恢复凭证。原 T07 双 PASS、P0/P1=0 且真实关键端到端证据齐备之前一律 BLOCKED；不得开始 T08、T09。不存在自动启动下个 Agent 的通道时，明确报告下一责任人与需触发的合法步骤，不可假称已自动完成。

## 最小完整回执

- 实际 Git 分支/HEAD/工作区状态；是否新增提交、实际测试时间。
- MySQL 实例非生产证据（脱敏），新库名称、创建/迁移结果与是否触碰其他库（否的核实依据），隔离配置与恢复演练证据。
- 合成数据生成器版本/seed、真实各类行数、可供负责人试用的独立账号准备状态（不在报告泄露登录凭据）。
- 真实 HTTP + MySQL、独立 oracle 月汇总/边界与标签去重、微信自动化真实执行范围、性能实际值；Stub/真实 AI 分开记。
- Coding 自测、独立 Testing、Supervisor 的结论和证据路径，P0～P3 清单及已知未测。
- T07 是否双 PASS；T08 是否**具备准入**（不是自动开始）、用户数据是否已受防重置保护。

## STOP

非生产/库名/账号权限无法确认；同名库已存在；新数据源不能与旧 8080 隔离；Flyway V1～V18 不一致；备份恢复证据缺失；必须额外提权、正式 Schema、真实数据或付费 AI；侵入五个旧库；P0/P1 等安全或资金重大风险；违反单 Coding Agent、独立审核或越过 Gate。一律停相关风险动作，其余只读和合法部分可继续并报告最小阻断。

## Change Log

- v0.1 / 2026-10-27：基于负责人明确批准的唯一隔离库对象，将原 T07 BLOCKED 解阻路径合并为一个有界执行包；不新增 Gate/Task 范围或外部权限。
