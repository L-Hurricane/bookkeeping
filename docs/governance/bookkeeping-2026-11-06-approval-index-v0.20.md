# 04丨记账软件｜2026-11-06 审批索引 v0.20

- **状态**：现行索引入口的增量。只登记 v1.1 S1 结项核对与 D01 准备。不把 S1 标成 CLOSED，不合并 `main`，不授权真实数据导入。
- **相对 v0.19**：原件字节不改。v0.19 SHA-256 仍是 `3e5df933cbebb005fb030c902b990d0a0ac0404cad38a1bf06aaa53072512596`。

## v1.1 S1

| 事项 | 原件 | SHA-256 |
|---|---|---|
| S1 结项核对 | `docs/implementation/bookkeeping-v1.1-s1-stage-closeout-v0.1.md` | `51d59a84314150d70fac45dc71f63894c701240a7a216e8bddebccbb4cf0b290` |
| S1 遗留 | `docs/implementation/v1.1-s1-closeout-backlog-v0.1.md` | `9e1f38d078b3893463c0cab196fe2c8fc5fc83732f7324504ca5bcdcea1c7190` |
| D01 准备 | `docs/implementation/v1.1-s1-d01-execution-prep-v0.1.md` | `8af4b308b18420e7eab64f5933fffecddffc92084a87a0066670cd5316a15738` |
| S1 Task Plan Review v0.1 | 仓库外原件。本索引不改其字节 | `ddc6aaab074c345239b7967a9d0b2ff2d9b42ed9deff4b3223879998c3ba0603` |
| M03/M02 Schema 批准记录 | `docs/implementation/v1.1-s1-t03-t05-schema-approval/bookkeeping-v1.1-s1-t03-t05-schema-approval-record-v0.1.md` | `fc1ccee5f5f961dfb24c05c4c794ff687a740356528835f54ac1cd80c84ab0de` |

固定人工验收 SHA 是 `786c038d6b09eaa426820d3027f2cb2ca1e0254e`。负责人接受 M01、M03、M02 核心功能。结项核对的结论是 **S1 NOT CLOSED**，因为该 SHA 还没有独立 Testing 与 Supervisor PASS。`c5aeeae` 的独立结果由交付主 Chat 转述为 Testing FAIL、Supervisor HOLD，报告 SHA-256 `afe45aa94653ebebeffc82441f8a77857bd497044cfd739fc5b68b55a8e71b33`；该报告原件不在本仓库。
