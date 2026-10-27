# S6 家庭账本复测 Gate v0.1

这是 `58557e9ed0dcb68a20b5b83d7b361f15e104c5df` 的独立复测记录。不是 S6 CLOSED，不是真实模型 PASS，不启动 T08。

- Testing：[S6 family independent testing](f7fef343-5729-4e23-bda9-520b4e0cca05)，结论 PASS。
- Supervisor：[S6 family independent supervisor](4bb5e342-92d9-4e43-a292-a6b9c94de467)，结论 PASS。
- 缺陷以监督为准：P0 = 0，P1 = 0，P2 = 0，P3 = 1（`S6-DATASET-P3-01`）。
- 受审产品提交是上面的 SHA。本记录在其后写入，不改变产品代码。
- E15 为 NOT AUTOMATED。真实供应商生成没有调用。
