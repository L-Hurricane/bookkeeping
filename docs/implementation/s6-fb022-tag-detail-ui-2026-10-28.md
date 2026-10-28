# S6 UI-FB-022 标签详情样式 2026-10-28

这是 C 类视觉收敛记录。T08 仍是 IN PROGRESS，不是 PASS。T09 NOT STARTED。S6 NOT CLOSED。

- 产品代码 `2aeeca0341ba1dacbe950193c605c916ebc60141`。只改标签详情页的布局、短说明和分类行缩进。没有改 `GET /api/v1/statistics/tag-analysis`、旧 `tagIds` 并集、金额或 Schema。
- 含下级 / 仅本标签仍分别对应 TREE / DIRECT。已有同时包含条件时，点「仅本标签」停在 TREE，并提示「同时包含只按标签及下级计算」。
- 小程序测试在这次代码上 253 项通过，typecheck 与 lint 通过。没有重跑后端全量测试。
- 合成用户截图：`/tmp/s6-fb022-shots/01-tag-detail.png`、`02-category-depth.png`、`03-and-sheet.png`、`04-and-empty.png`。修改前画面是交接包 `input-tag-detail-current.png`。没有写试用用户，没有调用模型。手机和局域网 NOT TESTED。
- 受影响范围确认 PASS。Supervisor ACCEPT FOR CONTINUED OWNER TRIAL。P0/P1/P2 = 0。P3：净额为零时同时保留「不计算覆盖率」和覆盖率分母说明。
