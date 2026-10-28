ALTER TABLE accounts
    ADD COLUMN lifecycle VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ALGORITHM=INSTANT;

ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_lifecycle
        CHECK (lifecycle IN ('ACTIVE', 'ARCHIVED'));
