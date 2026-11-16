# 04丨记账软件｜v1.2 S1 批准索引与 CURRENT 更新方案 v0.2

- 日期：2026-11-09
- 状态：**方案 / 未执行。本文件不是审批索引 v0.23，也不修改 `docs/CURRENT.md`。**
- v0.1 原件保留：`docs/implementation/history/bookkeeping-v1.2-s1-index-and-current-update-proposal-v0.1.md`，SHA-256 `e24675b9450778635c403d910a599bfeec933710a72ba93f838ea4f14a2f9eb1`。原字节不改。
- 现行索引入口仍是 `docs/governance/bookkeeping-2026-11-09-approval-index-v0.22.md`，SHA-256 `fb415b3e04e844ac91cd6b91ce3c2ba388f32f1c1eb56db0abdc592edd8e2f0b`。原字节不改。
- 现行正式治理是 Project v1.10，SHA-256 `481a755a00989ee18f9cf140623a86f079d4152cd8714e628ed3d9734a383347`。Git 目前只归档到 v1.6，SHA-256 `364a2dc1c22ea9d3dce7ed209d77fdc36ed90fe759417acdedca10d19c9e59d1`。这是文件归档缺口，不表示 v1.6 重新生效。v1.11 未核准正式生效前不得当作现行治理。

交给负责人批准的是下面三份 v0.2。四份 v0.1 原字节都还在，不作为本次批准文本。

| 候选 | 路径 | SHA-256 | 状态 |
|---|---|---|---|
| 产品/UI 差异 Review v0.2 | `docs/implementation/history/bookkeeping-v1.2-s1-product-ui-diff-review-v0.2.md` | `ace80cebae68e3ee01682aa278de4b0008da17792cae24fab53455d139efd494` | REVIEW |
| Stage/Task Plan Review v0.2 | `docs/implementation/history/bookkeeping-v1.2-s1-stage-task-plan-review-v0.2.md` | `30db9c7f018297d978e91d05a5911a705c08c6474029598e0bbcf5aae80f02a7` | REVIEW |
| C11 Schema Review v0.2 | `docs/implementation/history/bookkeeping-v1.2-c11-counterparty-schema-review-v0.2.md` | `40de6084f14f7538f8d21a7cea7662367f7472cdcb62cc6b7ea30db418eb3c45` | REVIEW / NOT APPROVED |

SHA 在写入后按文件字节计算。Review 正文保持 REVIEW。批准后另写批准记录，不回写这三份正文。

## 1. 批准之后才新增的索引

建议新文件 `docs/governance/bookkeeping-2026-11-09-approval-index-v0.23.md`。现在不创建。它只登记负责人对上面三份 v0.2 的决定，并写明：

- v0.22 原字节不改。v1.1 S1 仍是 CLOSED。四份 v0.1 原字节不改。
- 现行正式治理是 Project v1.10，SHA-256 `481a755a00989ee18f9cf140623a86f079d4152cd8714e628ed3d9734a383347`。Git 只到 v1.6 是归档缺口，不是 v1.6 重新生效。v1.11 未核准前不是现行治理。
- 产品/UI 与 Stage Plan 的批准不包含个人正式库执行或历史回填。
- C11 Schema 若获批，范围是 Review v0.2 第 2–5 节：获批后才可在已授权隔离环境创建并验证 V21。第 7 节是拟批准的恢复方案，个人正式库执行仍待专项。
- 本索引不是编码已经开始的证据。

未批准前，v0.22 继续是现行增量索引。

## 2. 批准之后才写入 CURRENT 的句子

不改写 CURRENT 里已有段落。在「当前执行状态」最上增加一条：

> v1.2 S1：**三份 Review v0.2 已归档，结论以批准记录为准**。产品/UI、Stage Plan、C11 Schema 的路径和 SHA 见审批索引 v0.23。v1.1 S1 仍是 CLOSED。历史回填、个人正式库执行和远端 Git 未授权。现行正式治理是 Project v1.10，SHA-256 `481a755a00989ee18f9cf140623a86f079d4152cd8714e628ed3d9734a383347`。Git 中的治理正文目前只到 v1.6，这是归档缺口，v1.6 不是重新生效的现行治理。v1.11 未核准正式生效前不是现行治理。

在读取顺序第 1 条之前增加：

> 已批准时先读 v1.2 S1 的产品/UI Review v0.2、Stage Plan Review v0.2 和 C11 Schema Review v0.2。未批准前，这三份只是候选。

在「现行文件」表增加上述三行，治理状态写 Project v1.10。不把 v1.11 写进读取顺序。不把仓库内 v1.6 写成现行正式治理。

## 3. 治理原件的最小补齐清单

下面 10 个文件都能按原字节取得。以后的文档任务只做复制和 SHA 复核，不改字节。本轮不复制。复制进 Git 之前，现行治理仍然是 Project v1.10，不回退到 v1.6。

| # | 文件 | SHA-256 | 本机位置 | Git |
|---|---|---|---|---|
| 1 | `bookkeeping-ai-collaboration-governance-v1.10.md` | `481a755a00989ee18f9cf140623a86f079d4152cd8714e628ed3d9734a383347` | `~/Downloads/bookkeeping-project-v1.10-four-documents-20261030/` | 无 |
| 2 | `bookkeeping-feature-batch-integration-acceptance-process-review-v0.2.md` | `0ddf3edc8b2ac6dedb0d44ec41ae171ff8148af951629953908fa64ab7eaedf7` | 同上 | 无 |
| 3 | `bookkeeping-feature-batch-integration-acceptance-approval-record-v0.1.md` | `535df1bf84d1d568b13c11b7db0163068aa900d31f5b47db85e5b49a468940c6` | 同上 | 无 |
| 4 | `bookkeeping-project-instructions-v1.10.md` | `530963a859a6c12c76660927c16639b738948f13d018a7e0a97063505757f764` | 同上。入库不等于 Project 设置已替换 | 无 |
| 5 | `bookkeeping-ai-collaboration-governance-v1.9.md` | `4c0940e5ec6ac7dc25ca43b26bf2229365d8535d68d670b91e71d02d06e187bb` | `~/Downloads/bookkeeping-governance-v1.9-project-update-20261028/` | 无 |
| 6 | `bookkeeping-cross-chat-specialists-unified-delivery-review-v0.1.md` | `ed8ecc548814caaada32f4671cceeb91964e7ece54076bc9d9021581f97e9dce` | 同上 | 无 |
| 7 | `bookkeeping-cross-chat-specialists-unified-delivery-approval-record-v0.1.md` | `e145ac3cc0238d603704bbee12006fbd1885f74568feaea8f40aa39e1fe6dfb7` | 同上 | 无 |
| 8 | `bookkeeping-approval-index-current-2026-10-28-v0.1.md` | `84ec9c98d944cf7d51a009b919de2a78b6b0464300736856b9b7d7fb860d4656` | 同上 | 无 |
| 9 | `bookkeeping-project-instructions-v1.9.md` | `83fe35dbdf43f2500c32afecb151d819223738bc1e9b8933fd31bae6a730ca05` | 同上。历史保留 | 无 |
| 10 | `bookkeeping-ai-collaboration-governance-v1.8.md` | `a8fb22ac207e06be3341826be5690d91b7c7f6d74e81937b26e5a2cb0bd987b0` | 只在三份 2026-10-28 交接 ZIP 内，不是独立治理包，也不在工作树 | 无 |

v1.10 正文点名、但本机 Downloads 与这些 ZIP 中没有原字节的 7 个文件，不补写：

| 点名文件 | 被引用的 SHA-256 | 结果 |
|---|---|---|
| `bookkeeping-ai-collaboration-governance-v1.7.md` | `4fcf410092429fa97aa21baaddc91b027fa0d006cee08af59e46b49bac4c56c1` | 未找到 |
| v1.7 Review | `4e07ba40e26b3a9775c842efae493849393ed172b7000d0a3742ab271d99f5eb` | 未找到 |
| v1.6 Review | `735f7c95c9926be98858cca86cdd3afceeab237393cd2fb4b6584cd15c4eb8a3` | 未找到 |
| v1.5 Review | `5466857cb12dd4dd54ba21f086b9f9b4b8af6e178b811cf691f34e07e5796671` | 未找到 |
| v1.8 Review | `21549fb126fad498060d099149fc88bb6a1936723237de3fa690d102f00e3b05` | 未找到 |
| v1.8 批准记录 | `6fa905d8697eeb555072a0cd740cefcdca8e8f31d2729c74169d33bb328223ea` | 未找到 |
| 批次机制 Review v0.1 | `2790bbaf15d184f49f61fcc6359e67a31bb93a222bb09bcfa3dc38682ef2cf55` | 未找到 |

## 4. v1.11

在 `~/Downloads` 的 bookkeeping ZIP、解压目录，以及本仓库两个工作树中，没有名为 v1.11 的治理包，也没有 ZIP 成员名包含 v1.11。没有全文，没有批准 SHA。未核准正式生效前，v1.11 不得当作现行治理，也不进入补齐清单。

## 5. 本方案不授权

复制上表、改 CURRENT、新建 v0.23、编码、Flyway、个人库操作、Push、远端分支、PR、合并 `main`、部署。

## Change Log

- v0.1（2026-11-09）：登记当时的 v0.1 Review。原字节保留。
- v0.2（2026-11-09）：组长审查后，把现行治理定为 Project v1.10。Git 只到 v1.6 记为归档缺口。批准文本改为三份 Review v0.2。v1.11 在核准前不是现行治理。
