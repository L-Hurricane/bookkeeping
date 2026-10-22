-- S1-T04: cross-account reassignment keeps the original first_save_order (TX-04 / T02).
-- Account-scoped UNIQUE(account_id, first_save_order) blocks moving a historical order
-- onto an account that already used the same order. New creates still allocate from
-- accounts.last_first_save_order under the account write lock; ledger index is unchanged.
-- Tie-breakers (first_saved_at_utc, id[, kind]) keep list/ledger order stable when orders collide.

ALTER TABLE transactions
    DROP INDEX uk_transactions_account_order;
