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
