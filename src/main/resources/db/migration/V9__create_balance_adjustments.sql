CREATE TABLE balance_adjustments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    adjustment_amount DECIMAL(19,2) NOT NULL,
    business_time DATETIME(0) NOT NULL,
    first_saved_at_utc DATETIME(6) NOT NULL,
    first_save_order BIGINT NOT NULL,
    remark TEXT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_adjustments_account_order UNIQUE (account_id, first_save_order),
    CONSTRAINT fk_adjustments_account FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id),
    CONSTRAINT ck_adjustments_amount CHECK (adjustment_amount <> 0),
    CONSTRAINT ck_adjustments_order CHECK (first_save_order > 0),
    INDEX idx_adjustments_ledger (account_id, business_time, first_save_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
