CREATE TABLE categories (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    type VARCHAR(16) NOT NULL,
    name VARCHAR(100) NOT NULL,
    parent_id BIGINT NULL,
    parent_scope_id BIGINT GENERATED ALWAYS AS (COALESCE(parent_id, 0)) STORED NOT NULL,
    created_at_utc DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_categories_scope_name UNIQUE (user_id, parent_scope_id, name),
    CONSTRAINT uk_categories_id_user_type UNIQUE (id, user_id, type),
    CONSTRAINT fk_categories_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories (id),
    CONSTRAINT ck_categories_type CHECK (type IN ('INCOME', 'EXPENSE')),
    INDEX idx_categories_user_type (user_id, type),
    INDEX idx_categories_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
