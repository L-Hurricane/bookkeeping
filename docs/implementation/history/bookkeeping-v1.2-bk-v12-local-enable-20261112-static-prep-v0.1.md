# 04丨记账软件｜BK-V12-LOCAL-ENABLE-20261112 静态部署准备 v0.1

- 日期：2026-11-12
- 批准 ID：`BK-V12-LOCAL-ENABLE-20261112`（单次、附条件；登记见审批索引 v0.24）
- 状态：**静态准备完成 / 真实库步骤未执行**。本文件不授权 Agent 连接 `bookkeeping`。
- 业务 RC-02：`824a3ce3ccc3bbb1223ded64c5cdfb04eb2f3b03`
- 安全脚本：`50fe905289a0716589ea2c4356af4964468749a2`
- 查阅工作树（含脚本与文档）：`bookkeeping-v11-s1` 分支 `v1.2/s1-m01`，准备时 HEAD 为 `50fe905…`（**不是**业务 RC-02）

## 1. 独立目录

| 角色 | 路径 | HEAD / 说明 |
|---|---|---|
| NEW_WT（业务） | `$HOME/.local/share/bookkeeping-v21-upgrade-rc02-wt` | detached `824a3ce…`；新建；含前后端；**无** `tools/v21-personal-upgrade` |
| 安全脚本树 | `$HOME/.local/share/bookkeeping-v21-upgrade-scripts-50fe905-wt` | detached `50fe905…`；与 RC-02 分离 |
| 正式小程序（静态副本） | `$HOME/.local/share/bookkeeping-personal-rc02-miniprogram` | 自 RC-02 `frontend/miniprogram` 复制；`apiBaseUrl=http://127.0.0.1:18090`；**非** UAT 副本 |
| UAT（未改） | `$HOME/.local/share/bookkeeping-uat-rc02-miniprogram` | 仍指向 `18082`；本轮未重置 |

V21 文件在 NEW_WT 上复算 SHA-256：`525bf36b67633b1b4b5d9b77694195db4502dcab22ef02274c9c6eea8d45d5f9`。

## 2. 静态检查（未启应用、未连库）

NEW_WT 存在：`backend/pom.xml`、`backend/mvnw`、`application.yml`（`url: ${DB_URL}`）、21 个 `V*.sql`（含 V21）、`frontend/miniprogram` 工程文件。  
**未**运行 `spring-boot:run` / Flyway / live `preflight`/`backup`/`upgrade-run`。

注意：RC-02 的 `application-local.yml` 含 `DB_URL` 默认回退串。负责人 live 时必须在私有 env 提供**显式非空** `DB_URL`，不得依赖该回退；安全脚本亦按 `${DB_URL}` 与 MySQL 探针交叉校验。

建议正式后端端口：**18090**（∉ `{18081,18082,18083}`；与正式小程序副本一致）。UAT 继续独占 18082。

## 3. 允许 / 禁止（摘录）

允许：负责人本人按 `50fe905` 脚本对唯一库 `bookkeeping` 做预检→备份→升级；失败时按脚本武装开关做整库空白+导入联合恢复。  
禁止：Agent 代连库；历史回填；动 UAT；Push/PR/`main`；用当前文档树 HEAD 冒充 RC-02。

## 4. 负责人下一步亲自核验（不代填）

1. 私有旧后端 SHA/启动命令/端口；旧小程序路径与 api；**不得**默认演练 `7281d1f`。  
2. 私有 env（600）：显式 `DB_URL=…/bookkeeping`；`SERVER_PORT=18090`；`BK_V21_BACKUP_PASSPHRASE`。  
3. `BK_V21_MYSQL_DEFAULTS_FILE` 与 `export BK_V21_EXPECT_SERVER_UUID`（与 `@@server_uuid` / JDBC 端口一致；UUID **不能**写入脚本白名单 env 文件）。  
4. 库内 Flyway 成功最大版本是否为 20、是否尚无 `counterparty_name`。  
5. 指纹底账：账户数、交易数、抽查余额与交易 id。  
6. 停写后按脚本顺序：`preflight` → `backup`（Manifest/SHA/加密）→ `upgrade-run` dry → 武装后执行；失败则 `restore` 规则。  

真实预检与备份**仅**负责人本人在 Mac 上执行。本记录 STOP 于此。
