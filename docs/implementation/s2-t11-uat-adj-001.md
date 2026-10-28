S2-T11-UAT-ADJ-001
日期: 2026-10-25
状态: 组长批准的 S2 内部定点修复。未改获批计划原件。未 Push，未合并，未宣布 S2 CLOSED。

授权依据:
- 获批计划 `docs/implementation/bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md` SHA-256 `69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5`。里程碑内普通任务调整只记录版本、Diff、SHA 与原因，不替换原件。
- 治理 v1.7 SHA-256 `4fcf410092429fa97aa21baaddc91b027fa0d006cee08af59e46b49bac4c56c1`。
- 负责人完成第一轮体验后，组长批准 UAT-01、UAT-02 修复，以及 UAT-04 最多三轮有界排查。UAT-03、UAT-05 不编码。

相对获批计划的 Diff:
- 不新开 Stage，不改 Schema，不改归档、转账和余额的业务规则。
- UAT-01：归档成功后用 `reLaunch` 回到正常账户列表，清掉原来的账户页面栈；列表刷新后再提示「账户已归档」。失败、取消或返回结果不是已归档时不跳转，也不提示成功。恢复账户仍留在设置页。
- UAT-02：新建转账的转出账户取当前仍可用的账户。转入账户不自动填写。2026-10-25 负责人确认这包括只有一个可选转入账户的情况，见 `docs/changes/v1.0-s2-uat-02-transfer-account-selection.md`。编辑已有转账的回填没有改。
- UAT-04：小程序主页改为账本页。已有列表再次显示时不再先把整页换成「正在读取账户」。
- UAT-03 交易后余额、UAT-05 统计/AI 导航维持原 Backlog，本记录不立项。

## UAT-04 排查

第一轮：登录成功直接 `reLaunch` 到账本，所以首次进入只有一次列表请求。微信主页按钮打开 `app.json` 的第一页。当时第一页是 `pages/bootstrap/index`，只显示「正在进入个人记账」，再 `reLaunch` 到账本。保存后换账户等路径会把页面栈收成一页，业务页左上角就会出现这个主页按钮。

第二轮：账本 `onShow` 每次都把 `phase` 设为 `loading`。模板在 loading 时卸掉已有账户卡片，所以从「‹ 账本」或系统返回回到账本时，会先空白再等接口。账户流水页自己的 `onShow` 也会重载流水，但它不挡住这次返回调用。

第三轮没有继续扩大。上面两处用局部修改处理。没有加缓存框架，没有改后端。

残余：第一次打开账本仍会显示「正在读取账户」，直到列表返回。已经打开的账户流水页，返回时仍可能重载该流水；这不是主页按钮路径，本轮没有改。

## 文件 SHA-256

| 文件 | SHA-256 |
|---|---|
| `frontend/miniprogram/miniprogram/account/navigation.ts` | `c7c1e6cfc51702224372623609e8e5a74043fbf4c725f0de28a8ffaf2bf23c2b` |
| `frontend/miniprogram/miniprogram/transfer/defaults.ts` | `ebb160e237bf29f490f852c898422197b75d98c86dc18793799d1b12816df94f` |
| `frontend/miniprogram/miniprogram/pages/transfer-create/index.ts` | `5f87dfadada70633155f653723e7fbe2f1207c25bc0b0bec0619df773cce2d81` |
| `frontend/miniprogram/miniprogram/pages/account-settings/index.ts` | `1342043ca1901f7c2d36478d3a122679324e51a04147f16b3286b92b2402289f` |
| `frontend/miniprogram/miniprogram/pages/account-list/index.ts` | `dd6c3f300622d0c6f2e6443b3eec0da4e4f803fc6825fd67cf0ed96dce3d13b1` |
| `frontend/miniprogram/miniprogram/pages/account-list/index.wxml` | `877593388a460a2cded36016308e4c97e7ba30882ba3e617dc2def6d3f12a2ba` |
| `frontend/miniprogram/miniprogram/app.json` | `7142378623bc0355e2564727a23e16e2821df0ffc21995397f3a12798aa04ec2` |
| `frontend/miniprogram/tests/uat-s2-t11.test.ts` | `b25c3c1665ae5089f6ec7a042886d1100317185e53439d3bf8e216cb7ca02375` |

计划原件 SHA 未变。`transfer-detail` 不在本次差异里。

## 验证与复核

`frontend/miniprogram` 执行 `npm test`、`npm run typecheck`、`npm run lint`，退出码 0。测试 216 项，失败 0。其中 UAT-01、UAT-02、UAT-04 的新用例覆盖归档回跳参数、成功提示条件、列表刷新方式、转出/转入默认值和主页页序。

同一次差异复核：归档失败分支不跳转、不提示成功；恢复逻辑仍留在设置页；转入默认不从多个账户里挑一个；编辑转账页未改；没有 Schema、数据库或凭据输出。这不是独立第二人 Gate，也不沿用 T10 PASS 代替这次前端差异。没有在微信开发者工具里走真实点击。

## 影响

开发库数据和已迁移 Schema 未动。工作区里 2026-10-24 的三份未提交文档仍保留。本轮代码和记录都未提交。
