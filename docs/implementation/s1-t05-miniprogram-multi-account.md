# S1-T05｜小程序多账户列表与交易账户选择

- 日期：2026-10-22
- Task：S1-T05
- 分支：`task/s1-t05`
- 前置：S1-T04 PASS（恢复点 `s1-t04-pass`）
- 依据：已批准 S1 Stage/Task Plan v0.2、v1.0 UI Freeze、T02 架构、T03/T04 后端能力

## 1. 范围

本 Task 做小程序前端，并按批准的 v0.2 Plan 差异补一条微信 HTTP 方法兼容入口：

1. 账本账户总览列表、创建普通/信用账户、账户设置更名。
2. 普通/信用类型展示；普通负余额提示且不阻断记账；信用负值按欠款语义不警告。
3. 新建/编辑交易可选择资金账户；仅一个适用账户时默认选中。
4. 保存后回到交易**当前所属**账户流水；跨账户修改后进入目标账户，而非原账户。
5. 金额展示统一正红、负绿、零值中性。
6. 保留既有日期时间输入、搜索、分类、标签、余额调整、统计入口。
7. **v0.2 兼容**：`AccountController` 追加 `PUT /api/v1/accounts/{accountId}/name`，复用既有 `accounts.rename()`；保留原 `PATCH`；小程序更名走 PUT；移除微信传输层对 PATCH 的非类型安全断言。

不做：T06/T07/T08；S2 转账、账户归档/永久删除；统计与 AI 月度分析重构；数据库迁移 V1–V13；服务层业务变更；PRD/UI/Task Plan 变更。

## 2. 导航与页面

| 页面 | 作用 |
|---|---|
| `account-list` | 登录后主页：多账户总览、创建入口；点进单账户流水 |
| `account-home?accountId=` | 单账户流水；账户设置；记一笔 |
| `account-create` | 创建 NORMAL / CREDIT + 起始金额 |
| `account-settings?accountId=` | 账户设置：更名 |
| `account-init` | 无账户时的首次 initialize（兼容旧路径） |

认证成功落地改为 `/pages/account-list/index`。

## 3. API 使用

- `GET /api/v1/accounts`：列表（含 `kind`）
- `POST /api/v1/accounts`：创建
- `PUT /api/v1/accounts/{id}/name`：更名（小程序使用；微信 typings 原生支持 PUT）
- `PATCH /api/v1/accounts/{id}`：更名（保留兼容，行为不变）
- 新建交易仍 `POST /accounts/{accountId}/transactions`
- 编辑 `PUT` 路径用原账户；请求体带目标 `accountId`

## 4. 保存回跳

- 同账户：沿用 opener `eventChannel` + `navigateBack` + post-save locate。
- 跨账户：`stashLedgerHandoff` + `reLaunch` 到目标 `account-home`，避免返回栈停在原账户。

## 5. 业务时间

对齐 T04 / BAL-05 / TX-06：前端不再拦截早于账户创建时间的业务时间（交易与余额调整表单一致）。

## 6. 验证

在 `frontend/miniprogram`：

- `npm run typecheck`
- `npm run lint`
- `npm test`

后端：`AccountController` PUT/PATCH 兼容相关账户 API 测试（正反例、鉴权、跨用户隔离、非法名称、同用户名称冲突）。

未做：微信开发者工具/真机交互验收（留给 T08）。

## 7. 已知跨任务风险（未改代码）

与 T03/T04 相同：AI 月度分析指纹不含 `accountId`，多账户下可能误复用 Snapshot。超出 S1-T05，仅记录。
