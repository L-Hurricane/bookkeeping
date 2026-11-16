# 04丨记账软件｜v1.1 S1-T03/T05 正式 Schema 专项审批 · Review v0.1

- 日期：2026-11-05
- 状态：**REVIEW / 负责人待批准；本文件不是现行授权**。
- 组长：交付主 Chat；候选来源：v1.1 Cursor 在本地 `v1.1/s1-m01@4c6e785d2263a35ed0a34dc271e71d572ac6cf9b` 的只读设计回执。
- 上位依据：已批准 S1 完整 Task Plan `ddc6aaab074c345239b7967a9d0b2ff2d9b42ed9deff4b3223879998c3ba0603` 与批准记录 `019b9a703dd69170e418f3829bca69d5f68da7bb20c86976fc130d51ff463e05`；治理 v1.10。
- 目的：一次审阅两项**分别生效**的正式结构设计/DDL，不包含个人/生产库执行、迁移回填、远端 Git、部署、v1.0 个人标签数据修复；不修改既有 Flyway V1–V18。

## 1. 当前交付事实和必要纠正

- M01 T00–T02 合成开发、自测与 HL-06 / HL-AT-08 修复已报告：后端11/11、小程序257/257、typecheck/lint PASS；最新 Commit `4c6e785d...`，仅本地，无远端操作；**这是 DEV PASS，不是独立 Gate、负责人 UAT 或 Stage PASS**。
- 保存后自动定位已加载页之外记录：P2，最终 Gate 复核；BootstrapIntegrationTest 17/18 迁移断言差异：P3，最终固定候选前核对。
- 本轮对 M02 Cursor SQL 有**最小技术纠正**：MySQL 8.4.3 的 `ALTER TABLE ... ADD COLUMN ... ADD CONSTRAINT CHECK ... ALGORITHM=INSTANT` 组合不可依赖，公开 Bug #117450 复现 ERROR 1845。应把 `ADD CHECK` 移到后续必须采用 `COPY` 的外键操作中，先独立 `INSTANT` 加列；且 `COPY` 期间阻断目标表并发写入，不能误称只有首尾短锁。
- 来源：MySQL 8.4 `17.12.1 Online DDL Operations`、`15.1.9 ALTER TABLE Statement`、Bug #117450，2026-11-05 查核。已批业务模型不变。

## 2. 审批对象 A / S1-T03 / M03 Transfer 可选名称

以下是本次拟批准的**正式数据库结构**（执行前需核实真实 `SHOW CREATE TABLE` 兼容性；此时只授权隔离开发/白名单测试环境按已批准任务实施）：

```sql
ALTER TABLE transfers
    ADD COLUMN name VARCHAR(255) NULL,
    ALGORITHM=INSTANT;
```

- 无新增索引；旧记录 `NULL`，界面仍显示已批准转账回退文案，NULL 不是持久存储名；新提交空白名称规范化为 NULL，最大255。
- 新转账名称必须在双方流水、CRUD、账户名称搜索以及 S1-T04 中验证，不把这个 SQL 批准误作业务功能已通过。
- `CREATE_TRANSFER` 幂等兼容：旧记录保留 v1 五字段哈希；新请求一律 v2，使用固定版本前缀、原五字段及规范化名称（NULL / 空白统一为空串）。服务根据历史存储哈希区分：旧 Key + 不带名称 → 幂等重放；旧 Key + 非空名称 → 409；新 Key 同内容 → 重放；新 Key 同 Key 名称变化 → 409；不同 Key 合法创建两条；创建后编辑名称不改变原创建幂等事实，使用原创建请求重放只返回当前记录不回写旧名称。**不修改历史 idempotency_records.request_hash**。
- STOP：实测该 DDL 不是 INSTANT、历史幂等兼容失败、金额/双侧时间/账户隔离行为变化；禁止未经批准变更已有数据。

## 3. 审批对象 B / S1-T05 / M02 一级账户分组与排序

**固定设计取舍：**一级分组，一账户最多归属一组；未分组为 `group_id IS NULL` 的虚拟分区；已存在账户保持历史 `id ASC` 顺序，无历史 DML 回填。`accounts.display_order` 继续允许 NULL，未手工重排时服务使用 `COALESCE(display_order,id), id`；首次有效重排时，在一个事务内对受影响的该组/未分组集合赋明确顺序。组自身按 `display_order,id`。跨用户关系通过复合外键限制；删除组先解绑含归档账户的成员再删组。所有组/账户余额仍派生于冻结财务规则。

拟批 DDL **四步**，每个步为独立的 MySQL DDL 隐式提交单元；如果任一步失败，立即 STOP，不自选其他算法、不禁用 FK 校验、不声称整体事务自动回滚。

```sql
-- B1 建表
CREATE TABLE account_groups (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    display_order BIGINT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_account_groups_id_user UNIQUE (id, user_id),
    CONSTRAINT uk_account_groups_user_name UNIQUE (user_id, name),
    INDEX idx_account_groups_user_order (user_id, display_order, id),
    CONSTRAINT fk_account_groups_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_account_groups_name CHECK (
        name = TRIM(name) AND CHAR_LENGTH(name) BETWEEN 1 AND 64 AND name <> '未分组'
    ),
    CONSTRAINT ck_account_groups_order CHECK (display_order > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- B2 仅增可空列（与 CHECK 分开，以满足 INSTANT 语义）
ALTER TABLE accounts
    ADD COLUMN group_id BIGINT NULL,
    ADD COLUMN display_order BIGINT NULL,
    ALGORITHM=INSTANT;

-- B3 仅加索引
ALTER TABLE accounts
    ADD INDEX idx_accounts_user_lifecycle_group
        (user_id, lifecycle, group_id, display_order, id),
    ADD INDEX idx_accounts_group_user (group_id, user_id),
    ALGORITHM=INPLACE, LOCK=NONE;

-- B4 外键 + 约束在 COPY 阶段完成，不关闭外键检查
ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_display_order
        CHECK (display_order IS NULL OR display_order > 0),
    ADD CONSTRAINT fk_accounts_group_user
        FOREIGN KEY (group_id, user_id)
        REFERENCES account_groups (id, user_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    ALGORITHM=COPY;
```

- `foreign_key_checks` 全程保持 ON。B4 的 `COPY` 是实质复制/检查，目标 `accounts` 不允许并发 DML，可能需要额外磁盘空间、可能等待相关元数据锁。正式个人/生产环境执行不在本次授权中。
- **合成验证是实施前硬条件**：在已授权隔离/白名单的 MySQL 8.4.3 环境，按当前真实 V18 表约束全量演练 B1–B4，特别验证 accounts 被其他业务表引用时 B4 COPY 可执行、DB CHECK 合法、复合 FK 拒跨用户、旧账户组 NULL/排序、归档解绑、失败后的中间结构与回退；若任何 SQL 实测不兼容，STOP 并向组长报告具体差异，需要变更正式 DDL 时另向负责人提交修订，绝不暗换算法或删约束。
- 未授权历史 DML；本候选**不包含** `UPDATE accounts SET display_order=id`，也不进行 `NOT NULL` 改造。回退需先识别已完成的 DDL 步及是否存在用户新写入；在新功能尚未写入时可按约束/索引/列/新表的逆序形成回退候选，**不得将回退当作已授权的真实库删除**。若任一步失败，保留中间状态、停止后续步骤，合成环境处理按现行授权执行。

## 4. 数据迁移衔接：另一个独立审批对象，不并入上述 Schema

- 负责人报告此前导出/脚本的数据截止 2026-11-01；若本周未完成，下一次快照可能延至 **2026-11-08（周日）**。期间新产生的记账事实必须纳入源/目标差异核对，不得假设原个人库等同最初导入状态。
- 金额符号问题的一次性转换修复现有隔离分支证据 `localtrial/lt01-t02@e849747...`；**合成修复不代表新的个人账本导入已获准/已修正**。
- 将下一份完整源快照冻结为新的数据文件 SHA、来源时间区间、分类/账户/标签映射版本、脚本 SHA。先设计源/目标差异清单和按来源标识去重策略，区分用户期间手工在目标端新增的账务记录与源端新增记录，避免覆写、重复导入和错误金额继续叠加。
- D01 的私有 CSV/映射/账户/余额操作和任何受控重导须另具体批准，仍由负责人本人 Mac 执行；不得向 Cursor/Chat/Trello 传原始个人记录或凭据；不得因为可接受重建而未核账就清库。

## 5. 负责人集中审批请求及边界

**A：批准 M03 §2 的正式 Schema 设计/DDL（含旧新哈希兼容矩阵）。**

**B：批准 M02 §3 的正式 Schema 设计/DDL 四步及可空排序、零历史回填、COPY 安全边界。** B4 实施前必须通过合成版实际 Flyway/索引/约束的全项演练，不符合即 STOP 重新提交方案，批准不等于真实库执行。

批准 A、B 后，组长可依法安排单一 Coding Agent 在本地 S1 分支串行实施 T04→T06/T07，每项独立 Commit、定向合成测试，沿用原计划 T09/T10/T11 Gate。**不存在 M03/M02 原定 Core 缩减，也不添加新独立 Stage Gate**。

不包含：真实个人库/生产库的任意 Flyway 执行或历史回填；v1.0 个人标签条件 DELETE；MoneyWiz 二次真实导入、文件复制与修复；任何新远端分支/PR/Push、`main` Merge 或部署；改写 V1–V18；禁用 `foreign_key_checks`。

## Change Log

- Review v0.1 / 2026-11-05：在 T03/T05 Cursor 定向核查、M01 修复 PASS 回执基础上合并申请两个独立 Schema；修正 MySQL 8.4.3 `INSTANT + CHECK` 组合及 `COPY` 并发风险；保留 M03 旧哈希方案与 M02 零历史回填方案；列明新 MoneyWiz 快照衔接及未授权范围。
