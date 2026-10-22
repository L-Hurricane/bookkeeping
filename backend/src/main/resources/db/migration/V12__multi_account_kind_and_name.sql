-- S1 multi-account: keep Account IDs and (id, user_id) ownership FKs.
-- Drop one-account-per-user uniqueness; add kind; enforce per-user name uniqueness.
--
-- Index order matters for MySQL InnoDB: fk_accounts_user references users(id) and
-- requires an index whose leftmost column is accounts.user_id. Before V12 that
-- supporting index is uk_accounts_user (user_id). uk_accounts_id_user (id, user_id)
-- cannot cover it. Add uk_accounts_user_name (user_id, name) first so the FK keeps
-- a supporting index, then drop uk_accounts_user.

ALTER TABLE accounts
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'NORMAL' AFTER name;

ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_kind CHECK (kind IN ('NORMAL', 'CREDIT'));

ALTER TABLE accounts
    ADD CONSTRAINT uk_accounts_user_name UNIQUE (user_id, name);

ALTER TABLE accounts
    DROP INDEX uk_accounts_user;
