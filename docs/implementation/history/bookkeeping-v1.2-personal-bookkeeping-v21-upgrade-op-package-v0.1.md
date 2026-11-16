# 04丨记账软件｜个人正式库 `bookkeeping` V21 升级操作包 v0.1

- 日期：2026-11-11
- 状态：**REVIEW / NOT APPROVED / NOT EXECUTED。** 供组长审查后再申请负责人专项批准。本文件不授权执行。
- Cursor **不得**试连个人库、不得读取个人备份、凭据或 MoneyWiz 源数据。正式库实际 Flyway 版本、记录数与余额只由负责人在获批后私有核查。
- 上位依据：
  - C11 Schema Review v0.2 §6–7，SHA-256 `40de6084f14f7538f8d21a7cea7662367f7472cdcb62cc6b7ea30db418eb3c45`
  - 业务交付 RC-02 `824a3ce3ccc3bbb1223ded64c5cdfb04eb2f3b03`（v1.2 S1 CLOSED）
  - 合成恢复 PASS 仅证明 `bookkeeping_v21_restore_drill` 路径；**不**继承为个人库执行权
- 固定目标库名：**`bookkeeping`**
- 排除：`bk_personal_local`、`bookkeeping_uat`、`bookkeeping_test`、`bookkeeping_migration_test`、`bookkeeping_v21_restore_drill`、MoneyWiz 源文件
- **本次升级范围：** 仅增加 V21 可空列 `counterparty_name`。**不做** MoneyWiz 历史回填，**不** UPDATE/DELETE 任何真实交易行，**不**改金额与余额公式。

## 1. 可复用资产与缺口

| 资产 | 路径 | 本机核验 | 个人库可用性 |
|---|---|---|---|
| 环境说明 | `docs/development/local-environment.md` | SHA-256 `26021deceb370dcd1ef3f8da9d150d36bc9978dc5b8f67859ca47f7e91602c42` | 路径约定可复用 |
| Schema §7 文本程序 | 同上 Schema Review | `40de6084…eb3c45` | 顺序权威；须负责人执行 |
| `mysqldump` / `mysql` | `$HOME/.local/share/bookkeeping-bootstrap/mysql-8.4.3-macos14-arm64/bin/` | 8.4.3 存在 | 可复用；库名现场确认 |
| JDK 25 | `$HOME/.local/share/bookkeeping-bootstrap/zulu25.36.205-ca-jdk25.0.4.1-macosx_aarch64/Contents/Home` | 存在 | 可复用 |
| V21 文件 @ RC-02 | `backend/src/main/resources/db/migration/V21__transaction_counterparty_name.sql` | SHA-256 `525bf36b67633b1b4b5d9b77694195db4502dcab22ef02274c9c6eea8d45d5f9` | 仅随 RC-02 后端迁移 |
| 合成演练回执 | `~/.config/bookkeeping/v12-s1-v21-restore-drill/` | 参考 | **禁止**对个人库重放 |
| UAT launch | `~/.config/bookkeeping/v12-s1-rc02-uat-launch.sh` | 指向 `bookkeeping_uat` | **禁止** |
| `acceptance-data.sh` | `backend/scripts/acceptance-data.sh` | 灌数 | **禁止** |

**缺口：** 仓库无「硬编码仅 `bookkeeping` + 预检门禁 + 备份 SHA + 恢复确认」的一键脚本。下文 §5 只提最小脚本方案，**本任务不编码、不运行**。

## 2. 固定制品版本与获取路径

| 角色 | Git SHA | 获取路径（负责人本机） |
|---|---|---|
| **新**后端 + **新**小程序 | `824a3ce3ccc3bbb1223ded64c5cdfb04eb2f3b03` | 工作树 `/Users/limingyuan/Documents/code/personal/bookkeeping-v11-s1`，分支 `v1.2/s1-m01`，`git rev-parse HEAD` 必须等于该 SHA；或 `git worktree add <dir> 824a3ce3ccc3bbb1223ded64c5cdfb04eb2f3b03` |
| **旧**后端 + **旧**小程序（联合恢复参考） | `7281d1f156924e92eebc11f1843059ba41eb5f26` | 已有只读树 `$HOME/.local/share/bookkeeping-v21-drill-v20-wt`（无 V21 文件）；或 `git worktree add <dir> 7281d1f156924e92eebc11f1843059ba41eb5f26`。**执行前必须核验个人当前实际运行制品**；不得假设生产已在跑该 SHA |
| V21 字节 | 文件 SHA-256 `525bf36b…d5f9` | 在新树内 `shasum -a 256 backend/src/main/resources/db/migration/V21__transaction_counterparty_name.sql` |

小程序：从对应 Git 树的 `frontend/miniprogram` 打开；API 指向**个人**后端端口。**禁止**使用 `~/.local/share/bookkeeping-uat-rc02-miniprogram` 连接个人库。

## 3. 负责人本机精确命令清单

凭证只来自负责人私有 `defaults-file` 或环境，**不写入本文件、不交给 Cursor**。下列 `PRIVATE_MYSQL_CNF` / `PRIVATE_BACKUP_DIR` 为占位。

### 3.0 制品预检（无数据库）

```sh
NEW_WT="/Users/limingyuan/Documents/code/personal/bookkeeping-v11-s1"
OLD_WT="$HOME/.local/share/bookkeeping-v21-drill-v20-wt"   # 或自建 worktree
test "$(git -C "$NEW_WT" rev-parse HEAD)" = "824a3ce3ccc3bbb1223ded64c5cdfb04eb2f3b03"
test "$(shasum -a 256 "$NEW_WT/backend/src/main/resources/db/migration/V21__transaction_counterparty_name.sql" | awk '{print $1}')" \
  = "525bf36b67633b1b4b5d9b77694195db4502dcab22ef02274c9c6eea8d45d5f9"
test "$(git -C "$OLD_WT" rev-parse HEAD)" = "7281d1f156924e92eebc11f1843059ba41eb5f26"
test ! -f "$OLD_WT/backend/src/main/resources/db/migration/V21__transaction_counterparty_name.sql"
```

任一失败 → STOP。

### 3.1 库身份与 Flyway 预检（负责人私有；Cursor 不执行）

```sh
MYSQL="$HOME/.local/share/bookkeeping-bootstrap/mysql-8.4.3-macos14-arm64/bin/mysql"
# PRIVATE_MYSQL_CNF=负责人私有配置，本文件不写路径内容
"$MYSQL" --defaults-file="$PRIVATE_MYSQL_CNF" --protocol=TCP -h127.0.0.1 --batch -e "
SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='bookkeeping';
SELECT MAX(CAST(version AS UNSIGNED)) AS max_v
  FROM bookkeeping.flyway_schema_history WHERE success=1;
SELECT COUNT(*) AS counterparty_col FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA='bookkeeping' AND TABLE_NAME='transactions'
   AND COLUMN_NAME='counterparty_name';
"
```

**硬性 STOP：** `max_v` ≠ 20；`counterparty_col` ≠ 0；库不存在或连错实例。版本未知时**不得假设已是 V20**。

私有笔记（不进 Git）：账户数、交易数、抽查账户余额、抽查交易 id、Flyway max。

### 3.2 停写

停止所有连接 `bookkeeping` 的后端/导入。确认端口不再接受写请求。保留合成 UAT `18082` 不强制停止，但**不得**把个人升级指到 UAT。

### 3.3 全量逻辑备份 + SHA

```sh
MYSQLDUMP="$HOME/.local/share/bookkeeping-bootstrap/mysql-8.4.3-macos14-arm64/bin/mysqldump"
BACKUP="$PRIVATE_BACKUP_DIR/bookkeeping-pre-v21-$(date +%Y%m%dT%H%M%S).sql"
"$MYSQLDUMP" --defaults-file="$PRIVATE_MYSQL_CNF" --protocol=TCP -h127.0.0.1 \
  --single-transaction --routines --events --triggers \
  --databases bookkeeping > "$BACKUP"
shasum -a 256 "$BACKUP" | tee "$BACKUP.sha256"
wc -c "$BACKUP"
# 目视确认 dump 头含 Database: bookkeeping，且无 bookkeeping_uat / bookkeeping_test
```

备份失败、SHA 未落盘、库名未复核 → STOP，不升级。

### 3.4 V21 升级（仅 RC-02 新后端）

```sh
export JAVA_HOME="$HOME/.local/share/bookkeeping-bootstrap/zulu25.36.205-ca-jdk25.0.4.1-macosx_aarch64/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
# 负责人私有：DB_URL 主机库名必须是 bookkeeping；不要 source UAT env
cd "$NEW_WT/backend"
./mvnw -DskipTests spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dspring.profiles.active=local" \
  -Dspring-boot.run.arguments="--server.port=<负责人自选非18082端口>"
```

日志核验：`Migrating schema \`bookkeeping\` to version "21"` 成功一次。

升级后只读（私有）：

```sql
SELECT MAX(CAST(version AS UNSIGNED)) FROM bookkeeping.flyway_schema_history WHERE success=1;  -- 期望 21
SELECT IS_NULLABLE, COLUMN_TYPE FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA='bookkeeping' AND TABLE_NAME='transactions' AND COLUMN_NAME='counterparty_name';
-- 再核：账户数、交易数、抽查余额与 3.1 私有笔记一致；抽样旧行 counterparty_name IS NULL
```

**禁止：** 任何历史对方 UPDATE；MoneyWiz 导入；改交易金额。

### 3.5 正常启动与冒烟

健康检查 UP；小程序（RC-02 树）连个人后端：登录、账户余额、一笔旧支出详情。不宣布公网发布。

### 3.6 失败时联合恢复（库 + 旧应用）

1. 停新后端。
2. **禁止** `DROP COLUMN`、`Flyway.clean()`、关 `foreign_key_checks`、改写 V21。
3. 仅当目标名已口头/书面确认为 **`bookkeeping`**，且备份 SHA 与文件复算一致时，由负责人执行整库恢复（空白后导入 3.3 备份，或等价整库导入）。命令示例（**高危；未专项批准前不得执行**）：

```sh
# 再次确认：当前会话只针对 bookkeeping
"$MYSQL" --defaults-file="$PRIVATE_MYSQL_CNF" --protocol=TCP -h127.0.0.1 --batch -e \
  "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='bookkeeping';"
# 恢复手段由负责人按私有规程选择；导入后复算 flyway max、无 counterparty_name、余额/笔数
"$MYSQL" --defaults-file="$PRIVATE_MYSQL_CNF" --protocol=TCP -h127.0.0.1 < "$BACKUP"
```

4. 启动**旧**树 `7281d1f…`（或执行前记录的实际旧 SHA）后端 + 对应小程序。
5. 确认 Flyway 回到升级前、结构与指纹一致。只恢复库或只退代码 = **未完成**。

## 4. 校验步骤摘要

| 阶段 | 通过条件 |
|---|---|
| 制品 | 新=RC-02；V21 文件 SHA 匹配；旧树无 V21 |
| 预检 | 库名 `bookkeeping`；max_v=20；无 counterparty 列 |
| 备份 | 文件非空；SHA 落盘；dump 仅该库 |
| 升级 | Flyway=21；列可空；旧行 NULL；计数/余额未变；无回填 |
| 恢复 | 旧应用可启；Flyway/结构/指纹回备份点 |

## 5. 最小脚本方案（需另批；本任务不编码）

若组长要求一键门禁，建议仅放负责人私有目录（不进应用仓库）：

1. `preflight-bookkeeping-v21.sh`：断言库名、`max_v==20`、无列；打印制品 SHA。
2. `backup-bookkeeping.sh`：固定 `mysqldump` 参数；写 `.sha256`。
3. `restore-bookkeeping.sh`：强制 `--database=bookkeeping` 参数；默认 dry-run；需二次确认字符串。

**所需授权：** 单独批准创建上述私有脚本 + 仍要本操作包的执行批准。未批前不写、不跑。

## 6. 本次拟申请的唯一执行范围（尚未批准）

负责人本人、本机、唯一库 **`bookkeeping`**：停写 → 全量备份+SHA → RC-02 一次 V21 迁移 → 只读核对 → 必要时 §3.6 联合恢复。

不含：Cursor 代操作；MoneyWiz/历史回填；真实交易改写；Push/PR/合并/`main`；公网部署；合成库操作。

## 7. 硬性 STOP

- 本包仍为 REVIEW，或无负责人专项批准精确修订版。
- Flyway 最高版未知或 ≠20；已有 V21/列。
- 制品 SHA 或备份 SHA 无法核验。
- 目标不是精确 `bookkeeping`，或工具默认 UAT/test/`bk_personal_local`。
- 需要 Cursor 试连/读备份/凭据/MoneyWiz。
- `Flyway.clean()`、禁用外键、改 V21 热修、夹带回填。
- 恢复风险超出负责人当场接受范围。

## 8. 审批就绪判断（本文档层）

- **已具备**提交组长审查 → 再请负责人专项批准的**文本操作包**条件（命令、制品、STOP、无回填边界已写明）。
- **未具备**「无人工判断的一键脚本」条件；若审批人要求脚本门禁，先批 §5 再编码。
- 合成演练 PASS **不能**代替本包批准。
