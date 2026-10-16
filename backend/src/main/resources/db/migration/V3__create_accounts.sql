CREATE TABLE accounts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    currency CHAR(3) NOT NULL,
    opening_balance DECIMAL(19,2) NOT NULL,
    created_time DATETIME(0) NOT NULL,
    last_first_save_order BIGINT NOT NULL DEFAULT 0,
    updated_at_utc DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_accounts_user UNIQUE (user_id),
    CONSTRAINT uk_accounts_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_accounts_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_accounts_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_accounts_order CHECK (last_first_save_order >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
