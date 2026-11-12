# 04丨记账软件｜2026-11-12 审批索引 v0.24

- **状态**：现行索引入口的增量。只登记 `BK-V12-LOCAL-ENABLE-20261112` 个人本地正式账本启用（附条件）及本轮静态部署准备归档。
- **相对 v0.23**：原件字节不改。v0.23 SHA-256 仍是 `2c36381c94ce12eb1470ff3eee2380f3537040667c3e47523cec9d990abb9a9a`。v0.23「不授权个人正式库」是当时的句子，不回写。
- **现行正式治理**：仍以仓库外 Project / 现行治理为准；本索引不改治理版本。

## BK-V12-LOCAL-ENABLE-20261112

负责人于 2026-11-12 在交付主 Chat 明确批准**单次、附条件**的个人本地正式账本启用操作。本索引只登记批准事实与边界；**不**把 Cursor 代操作、自动衔接真实库、Push/PR/合并 `main` 写成已授权。

| 项 | 固定值 |
|---|---|
| 批准 ID | `BK-V12-LOCAL-ENABLE-20261112` |
| 业务 RC-02 | `824a3ce3ccc3bbb1223ded64c5cdfb04eb2f3b03` |
| V21 安全脚本 | `50fe905289a0716589ea2c4356af4964468749a2` |
| 操作包 v0.2 | `docs/implementation/history/bookkeeping-v1.2-personal-bookkeeping-v21-upgrade-op-package-v0.2.md`（SHA-256 `92c8e91960d8472384975a244a2925887645f75c523f5a150e3453823f4fc503`） |
| 安全脚本 Supervisor | PASS WITH CONDITIONS（交付主 Chat 已接受） |

### 允许范围（本批准）

- 负责人本人、本机 Bootstrap、唯一目标库名 **`bookkeeping`**。
- 使用独立 RC-02 只读树 + 固定安全脚本，按脚本门禁执行预检、备份、升级、失败时整库联合恢复（恢复另需二次确认词与武装开关）。
- 本轮 Coding Agent 仅做**无敏感数据的静态部署准备与 docs 归档**；真实预检/备份/升级由负责人本人在 Mac 上执行。

### 禁止事项（本批准仍排除）

- Cursor / Agent 连接、查询、备份、升级或恢复真实 `bookkeeping`；读取私有 env、密码、个人备份、MoneyWiz。
- 历史交易对方回填；改金额/余额公式；真实交易 UPDATE/DELETE。
- 修改或重置合成 UAT（含 18082 / UAT 小程序副本）。
- Push、新远端分支、PR、合并 `main`、公网部署。
- 用 `bookkeeping-v11-s1` 当前 HEAD 冒充业务 RC-02；把演练/自测旧 SHA 默认当个人旧版。

## 本轮静态准备归档

执行记录：`docs/implementation/history/bookkeeping-v1.2-bk-v12-local-enable-20261112-static-prep-v0.1.md`（SHA-256 见该文件写入后的 CURRENT 表；以文件原字节为准）。

本轮**未**启动带真实库连接的 Spring，**未**执行 `preflight`/`backup`/`upgrade-run` 的 live 路径，**未**宣称个人库已升级到 V21。

## 未授权（继续）

历史回填、Agent 代操作真实库、Push/PR/合并 `main`、公网部署。真实库步骤仍仅负责人本人在专项边界内执行。
