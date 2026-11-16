ALTER TABLE transactions
    ADD COLUMN counterparty_name VARCHAR(255) NULL,
    ALGORITHM=INSTANT;
