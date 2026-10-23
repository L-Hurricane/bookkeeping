package com.limingyuan.bookkeeping.account;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AccountLifecycleMigrationContractTest {
    @Test void v15ContainsOnlyTheApprovedColumnAndCheck() throws Exception {
        String sql;
        try (var in = getClass().getResourceAsStream("/db/migration/V15__account_lifecycle.sql")) {
            assertThat(in).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sql).contains("ADD COLUMN lifecycle VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'");
        assertThat(sql).contains("ALGORITHM=INSTANT");
        assertThat(sql).contains("ADD CONSTRAINT ck_accounts_lifecycle");
        assertThat(sql).contains("CHECK (lifecycle IN ('ACTIVE', 'ARCHIVED'))");
        assertThat(sql.toLowerCase()).doesNotContain("index");
        assertThat(sql.toLowerCase()).doesNotContain("update ");
        assertThat(sql.toLowerCase()).doesNotContain("insert ");
    }
}
