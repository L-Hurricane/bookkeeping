-- v1.0 P0 V18, approved database design review v0.3 section 5.
-- Source SHA-256 7844121057e2d14504c9ca21baf82d8b83fff8a9638b2bb7cd56e8c5525b47ae.
-- Statements below are the approved draft text.

ALTER TABLE analysis_snapshots
    ADD COLUMN report_scope VARCHAR(16) NULL AFTER account_id,
    ADD COLUMN period_kind VARCHAR(16) NULL AFTER report_scope;

ALTER TABLE analysis_generations
    ADD COLUMN report_scope VARCHAR(16) NULL AFTER account_id,
    ADD COLUMN period_kind VARCHAR(16) NULL AFTER report_scope;

UPDATE analysis_snapshots
SET report_scope = CASE WHEN account_id IS NULL THEN 'LEGACY' ELSE 'ACCOUNT' END,
    period_kind = 'MONTH'
WHERE report_scope IS NULL;

UPDATE analysis_generations
SET report_scope = CASE WHEN account_id IS NULL THEN 'LEGACY' ELSE 'ACCOUNT' END,
    period_kind = 'MONTH'
WHERE report_scope IS NULL;

ALTER TABLE analysis_snapshots
    MODIFY analysis_month VARCHAR(7) NOT NULL,
    ALGORITHM=COPY, LOCK=SHARED;

ALTER TABLE analysis_generations
    MODIFY analysis_month VARCHAR(7) NOT NULL,
    ALGORITHM=COPY, LOCK=SHARED;

ALTER TABLE analysis_snapshots
    MODIFY report_scope VARCHAR(16) NOT NULL DEFAULT 'ACCOUNT',
    MODIFY period_kind VARCHAR(16) NOT NULL DEFAULT 'MONTH',
    ADD CONSTRAINT chk_analysis_snapshots_scope CHECK (
        (report_scope = 'ACCOUNT' AND period_kind = 'MONTH'
            AND account_id IS NOT NULL AND account_id > 0)
        OR (report_scope = 'LEDGER' AND account_id IS NULL AND period_kind IN ('MONTH', 'YEAR'))
        OR (report_scope = 'LEGACY' AND period_kind = 'MONTH' AND account_id IS NULL)
    ),
    ADD CONSTRAINT chk_analysis_snapshots_period_text CHECK (
        (period_kind = 'MONTH' AND CHAR_LENGTH(analysis_month) = 7
            AND analysis_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$')
        OR (period_kind = 'YEAR' AND CHAR_LENGTH(analysis_month) = 4
            AND analysis_month REGEXP '^[0-9]{4}$')
    );

ALTER TABLE analysis_generations
    MODIFY report_scope VARCHAR(16) NOT NULL DEFAULT 'ACCOUNT',
    MODIFY period_kind VARCHAR(16) NOT NULL DEFAULT 'MONTH',
    ADD CONSTRAINT chk_analysis_generations_scope CHECK (
        (report_scope = 'ACCOUNT' AND period_kind = 'MONTH'
            AND account_id IS NOT NULL AND account_id > 0)
        OR (report_scope = 'LEDGER' AND account_id IS NULL AND period_kind IN ('MONTH', 'YEAR'))
        OR (report_scope = 'LEGACY' AND period_kind = 'MONTH' AND account_id IS NULL)
    ),
    ADD CONSTRAINT chk_analysis_generations_period_text CHECK (
        (period_kind = 'MONTH' AND CHAR_LENGTH(analysis_month) = 7
            AND analysis_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$')
        OR (period_kind = 'YEAR' AND CHAR_LENGTH(analysis_month) = 4
            AND analysis_month REGEXP '^[0-9]{4}$')
    );

ALTER TABLE analysis_generations
    ADD COLUMN scope_account_key BIGINT
        GENERATED ALWAYS AS (
            CASE report_scope
                WHEN 'ACCOUNT' THEN account_id
                WHEN 'LEDGER' THEN 0
                ELSE NULL
            END
        ) STORED,
    ALGORITHM=COPY, LOCK=SHARED;

ALTER TABLE analysis_generations
    ADD CONSTRAINT uk_analysis_generations_coordination
        UNIQUE (user_id, scope_account_key, period_kind, analysis_month, context_fingerprint);

ALTER TABLE analysis_generations
    DROP INDEX uk_analysis_generations_account_scope;

ALTER TABLE analysis_snapshots
    ADD INDEX idx_analysis_snapshots_period
        (user_id, report_scope, period_kind, analysis_month, context_fingerprint, id);
