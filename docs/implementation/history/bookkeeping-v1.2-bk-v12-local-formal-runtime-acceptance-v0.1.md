# 04丨记账软件｜BK-V12 本地正式运行验收归档 v0.1

- 日期：2026-11-16
- 业务固定 SHA（RC-02）：`824a3ce3ccc3bbb1223ded64c5cdfb04eb2f3b03`
- 状态：本地正式环境**运行验收归档**（Mac 电脑端可用；iPhone 经代理只读查看真实账本成功）。**不是**全部手机写入功能测试 PASS，**不是** v1.2 全量 CLOSED 替代，**不是** Push / PR / 合并 `main` 授权。

## 1. 端口与进程分工（本机）

| 端口 | 角色 | 验收边界 |
|---|---|---|
| `127.0.0.1:18090` | 个人正式后端（`bk start personal`，RC-02 jar，库 `bk_personal_local`，Flyway 关闭） | Mac 电脑端正常使用已由负责人确认 |
| `192.168.1.3:18092` | 已批准 LAN 代理 → `18090`；白名单 `192.168.1.0/24`；约 30 分钟超时；仅 `/api/v1/` | iPhone 成功查看真实账本；**不**宣称全部写入场景 PASS |
| `18082` | UAT Spring，保持不变 | 代理不得占用；本验收未停 UAT |
| `18080` / `18081` | Cursor / 临时测试优先端口 | 按需；非正式账本 |

## 2. 制品与部署目录

| 项 | 路径 / 值 |
|---|---|
| 业务树 | `~/.local/share/bookkeeping-v21-upgrade-rc02-wt` @ `824a3ce…` |
| 托管 jar | `~/.local/share/bookkeeping-localtrial/release/`，`SOURCE.txt` → `824a3ce…` |
| 电脑端正式小程序 deploy | `~/Documents/code/personal/bookkeeping-deploy/20261113-824a3ce3`（API `127.0.0.1:18090`） |
| iPhone 正式 deploy 副本 | `~/Documents/code/personal/bookkeeping-deploy/20261113-824a3ce3-iphone-18092`（API `http://192.168.1.3:18092`） |

## 3. 验收表述边界

- **已确认**：Mac 电脑端可正常使用；iPhone 通过 `18092 → 18090` 成功查看真实账本。
- **不得扩大**：全部手机写入、深分页人工、公网部署、Agent 代写账、Push/PR/合并 `main`。
- **Gate**：v1.2 S1 CLOSED 与 RC-02 Testing/Supervisor/Owner UAT 仍以结项与批准原件为准；本文件不替代正式 Gate。

## 4. 恢复点（非密钥）

- 切换前旧 jar 备份目录名形如 `release-backup-pre-rc02-*`（勿把 `f23edeec` 旧制品直接接回已 V21 的个人库）。
- 旧版源码树：`…/bookkeeping-v21-upgrade-old-f23edeec-wt`。
- UAT `18082` 独立保留。

## Change Log

- v0.1 / 2026-11-16：归档本地正式运行与端口分工；明确手机查看成功 ≠ 全量真机写入 PASS。
