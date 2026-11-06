# 04丨记账软件｜v1.1 S1 结项记录 v0.2

- 日期：2026-11-06
- 结项状态：**S1 CLOSED**
- 业务 UAT 固定 SHA：`786c038d6b09eaa426820d3027f2cb2ca1e0254e`
- 本地标签 `v1.1-s1-t11-r2-uat-candidate` 指向该 SHA。
- 文档提交 `54b310b40b3b7105600f6bbc261b42f5bbf48fc0` 只收录了结项核对 v0.1。它不是业务候选，不参与 UAT。
- v0.1 原件保留，SHA-256 `51d59a84314150d70fac45dc71f63894c701240a7a216e8bddebccbb4cf0b290`。v0.1 把 R2 独立 Gate 写成缺失，该结论作废。本文件是现行结项记录。
- 这不是合并 `main`，不是 Push，不是部署，也不是个人账本已重导。

## 正式 Gate

| Gate | 证据 | 判定 |
|---|---|---|
| T10 Testing | `bookkeeping-v11-s1-evidence/s1-t11` 的上一层 `s1-t10/TESTING.md`。结论是「建议 S1-T10 Testing PASS」。候选 `3f5c065b9dc36e2130e8a6e1a60aa1c6c84df105`。SHA-256 `1879ea1b38faaf9489e5baed6e0e49d23b01edb66400c1d9f60b54c4800e180f` | PASS |
| T10 Supervisor | 本任务中交付主 Chat 确认与 Testing 构成双独立 Gate PASS。证据目录没有单独的 Supervisor 文件，本记录不另造 SHA | PASS，按交付主 Chat 确认 |
| `c5aeeae` 定向 Testing | `s1-t11/TESTING.md`。结论是「不建议定向 Testing PASS」。标签拖动 P1。SHA-256 `afe45aa94653ebebeffc82441f8a77857bd497044cfd739fc5b68b55a8e71b33` | 历史 FAIL。已被 R2 覆盖 |
| R2 Testing | `s1-t11-r2/TESTING.md`。结论是「建议定向 Testing PASS」。候选就是 `786c038`。SHA-256 `6b7d75a3ce2d6df4b6bd9f9f318a17f02a5881107929dbc5fa6bfdea2e07a457`。P0 = P1 = P2 = P3 = 0。真实拖动手势和点按把手没有执行 | PASS。手势仍不是实操 PASS |
| R2 Supervisor | 交付主 Chat 确认已复核上述 Testing 证据和代码并 PASS。证据目录没有单独文件 | PASS，按交付主 Chat 确认 |
| 负责人 2026-11-06 | 接受 M01 全历史流水、M03 转账名称、M02 账户分组核心功能，并接受两项低频交互延期 | 核心 UAT 接受。延期项不记实操 PASS |

v1.0 S6 CLOSED 与 M03/M02 Schema 批准仍按 v0.1 已核对的提交和 SHA 成立。本文件不重开它们。

## 两项延期不是 P1

分类和标签改上级的拖动体验不理想，真实手势没有被证明 PASS。点按把手仍然可用：移动不超过 16px 时，`onHandleEnd` 调用 `openParents`，弹出「选择新的上级」，`onChooseMove` 调用 `moveCategory` 或 `moveTag`。这条路径读的是把手上的 `data-id`，不经过拖动命中。创建表单里的上级选择器仍然只用于新建。

因此，改上级这项已冻结能力没有完全不可用。它是负责人已接受的非阻断体验问题，不降级、也不再打开 P1。账户拖动时其他卡片平滑让位同样是已接受的延期。分组排序本身已在核心验收里接受。

## 继续不记 PASS

- O01 同级自定义排序，仍是 P2。
- 保存后的全局定位，仍是 P2。
- 真机未测。人工深分页未测。
- R2 没有在开发者工具里点按把手。源码路径在，实操未测。
- T10 的账户组改名成功写入仍是 P3，不阻断。11 个环境门跳过仍是未测。

## 关闭

正式 Gate 已满足，P0/P1 不留在本 Stage。S1 CLOSED。未测和延期保持原词，不写成 PASS。
