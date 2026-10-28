# 04丨记账软件｜2026-10-25 审批索引 v0.7

- **状态**：现行索引入口的增量。不是新的 Git、部署或生产授权。
- **相对 v0.5 / v0.6**：两份原件字节不改。v0.5 SHA-256 仍是 `b3a950a8223b14d5656e9907ef8c2e95da5539dabad4ee69ef9203c278a8c8f2`。v0.6 SHA-256 仍是 `8294176cd2b25fdf7e6316f476035a48df961838ce7fa23858a9857e2dfd3ac2`。

## S3 结项所依据的已有批准

| 事项 | 原件 | SHA-256 |
|---|---|---|
| S3 Plan Approved v0.1 | `docs/implementation/bookkeeping-v1.0-stage-s3-plan-v0.1-approved.md` | `c871888bfa1ee09996e005adcdbe94afb568837699a425631c02a8dc396a79ef` |
| S3 Plan Review v0.1 | `docs/implementation/history/bookkeeping-v1.0-stage-s3-plan-taskplan-review-v0.1.md` | `6ef022619da77202502a5b636ec9be24cd785ee47c75b0946d79cbfd79229b06` |
| S3 Plan 批准记录 | `docs/implementation/bookkeeping-v1.0-stage-s3-plan-v0.1-approval-record.md` | `3b4e92b615ac2f3254a46769bc43e7bb585725f965f79a733c412203854ba05d` |
| DB-CT-01 草案 | `docs/implementation/s3-t00-db-ct-01-schema-draft-2026-10-25.md` | `752e2febcc0b79dc4dd82229c73a2f6219e00571f7807f27d3b4dbdfc4342a03` |
| DB-CT-01 批准记录 | `docs/implementation/s3-db-ct-01-approval-2026-10-25.md` | `cbef969c52d6c5cd3a79d8e76472a28e79428fef5899044be9c018b63c63ccc0` |

## T06 复验证据

证据目录在仓库外：`/Users/limingyuan/Documents/code/personal/bookkeeping-s3-t06-retest/`。

| 证据 | SHA-256 |
|---|---|
| 组长给出的首轮 Testing FAIL 报告 | `cf8a14a881179668f66bd6214a9741f06a96eb0f41984b9f9d4e58836d6f7076` |
| 复验 Testing 报告 `testing-report.md` | `baf6fe14c42fb9d72440530145050e2e5ba96c5ca4f9772d83a5ddd97075fdda` |
| 复验 Supervisor 报告 `supervisor-report.md` | `fb327744999b78d17b075d4941e0dd099403ed5c74f8d560efa1c8030d3ed7b4` |
| 修复提交 | `d929b77556b3d949a5fcbbffd431c5c1643109f9` |

## T07

负责人原话：“功能没问题”。这是基本功能验收，不是计划五项场景的逐条 PASS。
