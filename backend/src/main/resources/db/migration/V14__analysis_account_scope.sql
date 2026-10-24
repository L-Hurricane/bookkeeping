-- S1-T07R: scope analysis snapshots and generation coordination by account.
-- Keep AnalysisContextFingerprint as a pure facts hash (no account identity).
-- Legacy rows: account_id stays NULL; do not guess ownership and do not delete.
-- Account-scoped queries require account_id = :accountId, so NULL legacy rows
-- become invisible in the per-account UI until the user regenerates.

ALTER TABLE analysis_snapshots
    ADD COLUMN account_id BIGINT NULL AFTER user_id;

ALTER TABLE analysis_generations
    ADD COLUMN account_id BIGINT NULL AFTER user_id;

-- MySQL 8.4 Error 1553: fk_analysis_generations_user (user_id) is served by the
-- left prefix of uk_analysis_generations_scope. Create the account-scoped unique
-- index first so an eligible user_id left-prefix index remains before DROP.
ALTER TABLE analysis_generations
    ADD CONSTRAINT uk_analysis_generations_account_scope
        UNIQUE (user_id, account_id, analysis_month, context_fingerprint);

ALTER TABLE analysis_generations
    DROP INDEX uk_analysis_generations_scope;

ALTER TABLE analysis_snapshots
    ADD INDEX idx_analysis_snapshots_account_scope
        (user_id, account_id, analysis_month, context_fingerprint, id);

ALTER TABLE analysis_snapshots
    ADD INDEX idx_analysis_snapshots_account_month
        (user_id, account_id, analysis_month, id);

ALTER TABLE analysis_generations
    ADD INDEX idx_analysis_generations_account_scope
        (user_id, account_id, analysis_month, context_fingerprint);

-- Match transactions/adjustments: ownership is (account_id, user_id).
-- NULL account_id skips the FK check in MySQL, preserving unattributed legacy rows.
ALTER TABLE analysis_snapshots
    ADD CONSTRAINT fk_analysis_snapshots_account
        FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id);

ALTER TABLE analysis_generations
    ADD CONSTRAINT fk_analysis_generations_account
        FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id);
