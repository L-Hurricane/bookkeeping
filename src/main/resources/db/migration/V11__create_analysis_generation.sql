CREATE TABLE analysis_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    analysis_month CHAR(7) NOT NULL,
    context_fingerprint VARCHAR(128) NOT NULL,
    monthly_overview TEXT NOT NULL,
    expense_structure TEXT NOT NULL,
    surplus_reasons TEXT NOT NULL,
    points_to_watch TEXT NOT NULL,
    provider VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    total_tokens INT NULL,
    latency_millis BIGINT NOT NULL,
    provider_request_id VARCHAR(128) NULL,
    permit_date DATE NOT NULL,
    generated_at_utc DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_analysis_snapshots_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_analysis_snapshots_scope (user_id, analysis_month, context_fingerprint, id),
    INDEX idx_analysis_snapshots_month (user_id, analysis_month, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Successful snapshots are insert-only. The application user cannot create an
-- immutability trigger while binary logging is enabled, so the store never updates this table.

CREATE TABLE analysis_quota_days (
    user_id BIGINT NOT NULL,
    permit_date DATE NOT NULL,
    reserved_count INT NOT NULL,
    consumed_count INT NOT NULL,
    PRIMARY KEY (user_id, permit_date),
    CONSTRAINT fk_analysis_quota_days_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_analysis_quota_non_negative CHECK (reserved_count >= 0 AND consumed_count >= 0),
    CONSTRAINT chk_analysis_quota_daily_limit CHECK (reserved_count + consumed_count <= 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE analysis_generations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    analysis_month CHAR(7) NOT NULL,
    context_fingerprint VARCHAR(128) NOT NULL,
    state VARCHAR(16) NOT NULL,
    fencing_token BIGINT NOT NULL,
    permit_date DATE NOT NULL,
    lease_expires_at_utc DATETIME(6) NOT NULL,
    quota_reserved TINYINT NOT NULL,
    snapshot_id BIGINT NULL,
    created_at_utc DATETIME(6) NOT NULL,
    updated_at_utc DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_analysis_generations_scope UNIQUE (user_id, analysis_month, context_fingerprint),
    CONSTRAINT fk_analysis_generations_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_analysis_generations_snapshot FOREIGN KEY (snapshot_id) REFERENCES analysis_snapshots (id),
    CONSTRAINT chk_analysis_generations_state CHECK (state IN ('IN_PROGRESS', 'FAILED', 'SUCCEEDED')),
    CONSTRAINT chk_analysis_generations_token CHECK (fencing_token > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE analysis_user_locks (
    user_id BIGINT NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_analysis_user_locks_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
