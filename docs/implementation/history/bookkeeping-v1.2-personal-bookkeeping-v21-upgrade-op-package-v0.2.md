# 04丨记账软件｜个人正式库 `bookkeeping` V21 升级操作包 Review v0.2

- 日期：2026-11-11
- 状态：**REVIEW / REVISE 后待组长再审 / NOT APPROVED / NOT EXECUTED。** 不授权真实库操作，不授权 Cursor 连接 `bookkeeping`。
- v0.1 原件保留：`docs/implementation/history/bookkeeping-v1.2-personal-bookkeeping-v21-upgrade-op-package-v0.1.md`，SHA-256 `3156a1b3bcfbfc0a09f17b9a6c16260db9856408c0a58ec3aaad64edce4b5f18`。原字节不改。
- 本文件是对 v0.1 的**最小差异修订**，不重写整套方案。未点名改写的 v0.1 条款（含仅 V21 可空列、无回填、无真实交易改写）继续有效。
- 上位依据同 v0.1：Schema Review v0.2 §6–7（SHA-256 `40de6084f14f7538f8d21a7cea7662367f7472cdcb62cc6b7ea30db418eb3c45`）；业务交付 RC-02 `824a3ce3ccc3bbb1223ded64c5cdfb04eb2f3b03`；合成演练 PASS 不继承执行权。
- 固定目标库名：**`bookkeeping`**。排除集同 v0.1。
- Git 现状（修订时核对）：工作树 `v1.2/s1-m01` 的 `HEAD` = 文档归档 `186af142f53dedf1739e9104a9b526d583e205f0`；其父提交 = 业务 RC-02 `824a3ce3ccc3bbb1223ded64c5cdfb04eb2f3b03`。二者不得混用为「唯一 HEAD 校验」。

## 1. 相对 v0.1 必须改正的六点

### 1.1 制品校验：独立只读固定树，不切换现有工作树

**废止** v0.1 §2 / §3.0 中「`bookkeeping-v11-s1` 的 `git rev-parse HEAD` 必须等于 RC-02」——该树 HEAD 已是文档 Commit，断言必然失败或诱使 `reset`/`checkout`。

**改为：**

| 角色 | 固定方式 | 禁止 |
|---|---|---|
| **新**后端 / 小程序 | 另建**只读** worktree 或 archive，钉死 `824a3ce3ccc3bbb1223ded64c5cdfb04eb2f3b03`。例：`git worktree add --detach "$HOME/.local/share/bookkeeping-v21-upgrade-rc02-wt" 824a3ce3ccc3bbb1223ded64c5cdfb04eb2f3b03`。在该目录内校验 `rev-parse HEAD` = RC-02，并复算 V21 文件 SHA-256 `525bf36b67633b1b4b5d9b77694195db4502dcab22ef02274c9c6eea8d45d5f9` | 为升级去 `checkout`/`reset` 现有 `bookkeeping-v11-s1`；把文档 Commit `186af14…` 当成业务候选 |
| **旧**后端 / 小程序 | **仅**使用负责人升级前私有笔记中的实际运行制品（见 §1.5）。需要源码树时，按该私有 SHA 另建只读 worktree | 把合成演练树或 `7281d1f…` **默认**当作个人旧版 |

现有 `bookkeeping-v11-s1`（含文档 Commit）保持不动，只作文档与 Git 历史查阅。

### 1.2 升级连接：实例、库名、端口强制校验

启动 RC-02 后端前，必须使用**专用私有环境文件**（例：`bookkeeping-personal-v21-upgrade.env`，权限 600，不进 Git、不交给 Cursor）。**禁止**直接 `source` UAT launch、演练 env，或未审查的 `bootstrap.env` 整文件（其中可能混有测试变量）。

强制断言（失败即 STOP）：

1. `DB_URL` 可解析，主机为 `127.0.0.1`（或负责人书面固定的本机 Bootstrap 地址）。
2. JDBC 路径库名**精确**等于 `bookkeeping`（`…/bookkeeping?` 或 `/bookkeeping` 结尾于 query 前）。
3. `DB_URL` / `DB_USERNAME` **字符串级禁止**子串：`bookkeeping_uat`、`bookkeeping_test`、`bookkeeping_migration_test`、`bookkeeping_v21_restore_drill`、`bk_personal_local`。
4. 启动命令显式 `--server.port=<专用端口>`；端口 ∉ `{18081,18082,18083}`（合成/UAT/演练曾用），并与小程序配置一致。
5. 进程环境中若存在 `TEST_DB_URL` / `MIGRATION_TEST_DB_URL`，升级会话必须 `unset`，避免误测连库。
6. 启动后日志必须出现实际 JDBC 指向 `bookkeeping`；若出现上述禁止库名 → 立即停进程，STOP。

### 1.3 备份：失败即停、完整性、独立加密、恢复可行性

在 v0.1 §3.3 之上增加（仍由负责人私有执行）：

1. **失败即停：** `set -euo pipefail`；`mysqldump` 非 0 退出则删除不完整文件并 STOP；禁止忽略 stderr。
2. **完整性：** 字节数 > 0；`shasum -a 256` 写入旁路文件并回读复算一致；dump 头含 `Database: bookkeeping`；dump 正文含 `flyway_schema_history` 与 `CREATE TABLE`；**不得**含禁止库名。
3. **独立加密副本：** 在明文备份 SHA 核验通过后，于**另一私有路径**生成加密副本（例：`gzip -c` 再 `openssl enc`，口令只在负责人本机密钥链/口头，不写仓库、不写 Chat）。明文与密文缺一 → STOP。
4. **恢复可行性（升级前，不碰现存业务库内容以外的冒险操作）：** 负责人私有确认：能解密、能复算 SHA、备份可被 `mysql` 客户端解析。**禁止**为「试恢复」在未专项批准时覆盖或导入仍含数据的 `bookkeeping`。合成演练已证明流程形状，不能代替个人备份可恢复性确认。

### 1.4 故障恢复：整库回到 V20 备份点

联合恢复目标是：**完整**恢复升级前备份中的 Schema + 数据 + `flyway_schema_history`（V20 终点），并启动 §1.5 核验过的旧应用。

**禁止：**

- 未经专项批准，对**已是 V21** 的现存 `bookkeeping` 直接 `mysql < dump` 覆盖导入。
- `DROP COLUMN`、`Flyway.clean()`、关 `foreign_key_checks`、改写 V21、只退代码不退库、只退库不退代码。

**允许的恢复形状（仍须本包执行批准 + 恢复当下二次确认）：**

1. 停新后端；复算备份 SHA/加密副本。
2. 书面确认目标名 = `bookkeeping`。
3. 将 `bookkeeping` **恢复为空白**（仅该库）后再导入**完整**预升级备份；或经负责人确认的等价整库替换，效果必须是 Flyway 最高版回到备份时、无 `counterparty_name`（若备份为 V20）、行指纹一致。
4. 启动私有核验过的旧后端 + 旧小程序；核对健康与指纹。

### 1.5 个人实际旧制品：私有核验，不用演练 SHA 冒充

**废止**将 `7281d1f…` 或 `$HOME/.local/share/bookkeeping-v21-drill-v20-wt` 默认写成「个人旧后端」。

升级前负责人私有记录（不进 Git、不交 Cursor）：

| 项 | 记录内容 |
|---|---|
| 旧后端 | 实际运行的 Git SHA，或可复算的 jar/class 树摘要；启动命令与端口 |
| 旧小程序 | 工程路径与内容摘要（或对应 Git SHA）；`api` 基址 |
| 一致性 | 旧后端迁移目录**最高** Flyway 文件版本 ≤ 库内 `max_v`；若库为 V20，旧树不得含会自动应用的 V21 |

仅当私有记录 SHA **恰好**等于某候选（含 `7281d1f…`）时，才可使用对应该 SHA 的只读 worktree。否则按私有 SHA 另建树或使用已归档制品。合成演练用过的 SHA 只作「曾验证可启动的 V20 形状参考」。

### 1.6 业务边界（不变）

仅 V21 新增可空 `counterparty_name`。无 MoneyWiz 历史回填，无真实交易 UPDATE/DELETE，不改金额与余额公式。

## 2. 继承的执行顺序（经 §1 修正后）

与 v0.1 相同骨架：制品预检（§1.1/1.5）→ 库身份/Flyway 预检（max_v=20、无列；Cursor 不执行）→ 停写 → 备份+加密（§1.3）→ 专用 env 启动 RC-02（§1.2）→ 只读核对 → 失败则 §1.4 联合恢复。

## 3. 最小固定执行脚本设计（只设计，不编码）

放置：**仅**负责人私有目录（建议 `~/.config/bookkeeping/private-v21-upgrade/`，600/700）。**不**进应用仓库。未获「脚本编码授权」前不得创建文件。

| 脚本 | 输入（无秘密进 argv） | 强制断言 | 人工确认 | 失败停止 | 凭据 |
|---|---|---|---|---|---|
| `preflight.sh` | `NEW_WT`、`OLD_WT`、私有 env 路径、期望新 SHA=RC-02、期望旧 SHA=私有笔记 | 新/旧 `HEAD`；V21 文件 SHA；旧树无 V21；`DB_URL` 库名=`bookkeeping`；禁止库名子串；端口合法；`max_v==20`；无列 | 打印摘要后要求输入 `PREFLIGHT-OK` | 任一非 0 / 不匹配 → exit 1 | 只读 `--defaults-file` 或 env；不 echo 密码；不写日志明文口令 |
| `backup.sh` | 同 env；`BACKUP_DIR`；加密输出目录 | dump exit 0；size>0；SHA 复算；头/库名；含 flyway；加密副本生成 | 输入 `BACKUP-OK` 才打印「允许进入升级」 | dump/SHA/加密失败 → 删残片并 exit 1 | 同上；密文口令来自 TTY/`openssl` 提示，不进 argv |
| `upgrade-run.sh` | `NEW_WT`；env；端口 | 再次跑 preflight 关键断言；未 `source` UAT；unset 测试 URL | 输入 `UPGRADE-BOOKKEEPING`（整词） | 断言失败或日志出现禁止库名 → 杀进程 exit 1 | 环境变量来自文件，chmod 600 |
| `restore.sh` | 备份路径；`.sha256`；旧 WT；env | SHA 复算；目标名参数必须字面 `bookkeeping`；默认 `--dry-run` | 干跑通过后，二次输入 `RESTORE-BOOKKEEPING-FULL` 才允许空白+导入 | 默认不写库；确认词错误 → exit 1；禁止对 V21 库直接覆盖导入 | 同备份 |

公共机制：`set -euo pipefail`；所有确认词走 stdin；退出码非 0 视为 STOP；脚本自身不把 `DB_PASSWORD` 打到 stdout/stderr；不把备份内容上传或写入仓库。

**脚本编码授权（尚未授予）：** 批准在负责人私有目录创建上表四个脚本（或等价合并），仍**不**等于批准对 `bookkeeping` 执行升级/恢复。

## 4. 真实库专项审批范围（建议文案；尚未批准）

在 v0.2（或其后精确 SHA）获组长接受且负责人专项批准后，唯一范围仍是：

负责人本人、本机 Bootstrap、唯一库 **`bookkeeping`**：停写 → 完整逻辑备份（含 SHA + 独立加密副本）→ 用**独立 RC-02 只读树**启动一次 V21 迁移 → 只读核对 → 失败时按 §1.4 **整库**回到预升级备份并启动**私有核验过的**旧应用。

仍排除：Cursor 代操作；试连/读凭据/备份内容进 Chat；MoneyWiz/回填；真实交易改写；Push/PR/合并/`main`；公网部署；合成库；未经批准覆盖现存 V21 库。

## 5. 硬性 STOP（在 v0.1 §7 上增补）

- 试图用 `bookkeeping-v11-s1` 当前 HEAD（文档 Commit）冒充 RC-02，或为升级 reset 该树。
- 无私有旧制品笔记，或把演练 SHA 直接当个人旧版。
- `DB_URL` 未通过 §1.2 断言，或误连合成库。
- 备份未加密副本、SHA 不一致、或升级前未做恢复可行性确认。
- 对已是 V21 的 `bookkeeping` 未经空白步骤直接导入覆盖。
- 本包仍为 REVIEW / 无专项批准 / 无脚本编码批准却编写或运行脚本。

## Change Log

- v0.1（2026-11-11）：初版操作包。SHA-256 `3156a1b3bcfbfc0a09f17b9a6c16260db9856408c0a58ec3aaad64edce4b5f18`。
- v0.2（2026-11-11）：组长 REVISE REQUIRED 后最小修订。改正 HEAD/RC-02 冲突（独立只读制品）；连接三要素校验；备份失败即停/完整性/加密/可恢复性；整库 V20 恢复禁止覆盖式导入；个人旧制品私有核验；展开脚本设计（仍不编码）。业务边界不变。
