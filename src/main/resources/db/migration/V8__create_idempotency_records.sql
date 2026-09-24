CREATE TABLE idempotency_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    scope VARCHAR(64) NOT NULL,
    operation_id VARCHAR(64) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    result_code VARCHAR(32) NOT NULL,
    resource_id BIGINT NULL,
    created_at_utc DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_user_scope_operation UNIQUE (user_id, scope, operation_id),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_idempotency_created (created_at_utc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
