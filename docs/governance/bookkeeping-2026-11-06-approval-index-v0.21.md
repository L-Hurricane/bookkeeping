# 04丨记账软件｜2026-11-06 审批索引 v0.21

- **状态**：现行索引入口的增量。纠正 v0.20 的结项结论，并登记 D01 范围 Review。不合并 `main`，不授权真实数据导入。
- **相对 v0.20**：原件字节不改。v0.20 SHA-256 仍是 `be1a2564ec04b8e9ff36d4f88fdf8a019ffeda68ace3b5c55d8311d650d02be3`。它把 S1 写成 NOT CLOSED，该结论作废。

## v1.1 S1 CLOSED

| 事项 | 原件 | SHA-256 |
|---|---|---|
| S1 结项 v0.2 | `docs/implementation/bookkeeping-v1.1-s1-stage-closeout-v0.2.md` | `22b3c3bff0391e113e1a9c920dddb1cc831e5708064be3b3361561ff7d7a403c` |
| S1 遗留 v0.2 | `docs/implementation/v1.1-s1-closeout-backlog-v0.2.md` | `01f687c30a3561c27a2831d48e33fe70ae151693dac4d549fcbbc1d985459290` |
| 作废的结项核对 v0.1 | `docs/implementation/bookkeeping-v1.1-s1-stage-closeout-v0.1.md` | `51d59a84314150d70fac45dc71f63894c701240a7a216e8bddebccbb4cf0b290` |
| R2 Testing | 仓库外 `bookkeeping-v11-s1-evidence/s1-t11-r2/TESTING.md` | `6b7d75a3ce2d6df4b6bd9f9f318a17f02a5881107929dbc5fa6bfdea2e07a457` |
| T10 Testing | 仓库外 `bookkeeping-v11-s1-evidence/s1-t10/TESTING.md` | `1879ea1b38faaf9489e5baed6e0e49d23b01edb66400c1d9f60b54c4800e180f` |
| `c5aeeae` Testing | 仓库外 `bookkeeping-v11-s1-evidence/s1-t11/TESTING.md` | `afe45aa94653ebebeffc82441f8a77857bd497044cfd739fc5b68b55a8e71b33` |

业务 UAT SHA 是 `786c038d6b09eaa426820d3027f2cb2ca1e0254e`。文档提交 `54b310b40b3b7105600f6bbc261b42f5bbf48fc0` 不是业务候选。T10 与 R2 的 Supervisor PASS 由交付主 Chat 确认，证据目录没有单独文件。

## D01

| 事项 | 原件 | SHA-256 |
|---|---|---|
| D01 范围 Review v0.1 | `docs/implementation/history/bookkeeping-v1.1-d01-full-reimport-scope-review-v0.1.md` | `c6f794727ba9ce3698f4b4ea3a1c565a7386086bc14e65137379738ebf01f4b6` |

状态是 REVIEW / NOT APPROVED。四份 LT-T05 原件仍在仓库外 ZIP `d9ab6fa83ca641d6aecbacbdc009a11368dcabed7a70dabf9db08a684393f008`，字节不改。
