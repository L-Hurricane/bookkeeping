package com.limingyuan.bookkeeping.transfer;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TransferMigrationContractTest {
    @Test void v16IsTheApprovedSingleCreateTable() throws Exception {
        String sql;
        try (var in = getClass().getResourceAsStream("/db/migration/V16__create_transfers.sql")) {
            assertThat(in).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sql).isEqualTo("""
                CREATE TABLE transfers (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    user_id BIGINT NOT NULL,
                    from_account_id BIGINT NOT NULL,
                    to_account_id BIGINT NOT NULL,
                    amount DECIMAL(19,2) NOT NULL,
                    from_business_time DATETIME(0) NOT NULL,
                    to_business_time DATETIME(0) NOT NULL,
                    first_saved_at_utc DATETIME(6) NOT NULL,
                    first_save_order BIGINT NOT NULL,
                    updated_at_utc DATETIME(6) NOT NULL,
                    PRIMARY KEY (id),
                    INDEX idx_transfers_from_account_user (from_account_id, user_id),
                    INDEX idx_transfers_to_account_user (to_account_id, user_id),
                    INDEX idx_transfers_from_ledger (from_account_id, from_business_time, first_save_order),
                    INDEX idx_transfers_to_ledger (to_account_id, to_business_time, first_save_order),
                    CONSTRAINT fk_transfers_from_account FOREIGN KEY (from_account_id, user_id)
                        REFERENCES accounts (id, user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
                    CONSTRAINT fk_transfers_to_account FOREIGN KEY (to_account_id, user_id)
                        REFERENCES accounts (id, user_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
                    CONSTRAINT ck_transfers_amount CHECK (amount > 0),
                    CONSTRAINT ck_transfers_distinct_accounts CHECK (from_account_id <> to_account_id),
                    CONSTRAINT ck_transfers_order CHECK (first_save_order > 0)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
                """);
    }
}
