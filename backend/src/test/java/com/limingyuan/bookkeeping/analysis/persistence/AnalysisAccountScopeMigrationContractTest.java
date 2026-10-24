package com.limingyuan.bookkeeping.analysis.persistence;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * No-DB contract for V14: account scope is additive, legacy rows stay nullable, no destructive DML.
 */
class AnalysisAccountScopeMigrationContractTest {
    @Test void v14AddsNullableAccountScopeWithoutDeletingOrGuessingLegacyRows() throws Exception {
        String sql;
        try (var in = getClass().getResourceAsStream("/db/migration/V14__analysis_account_scope.sql")) {
            assertThat(in).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sql).contains("ADD COLUMN account_id BIGINT NULL");
        assertThat(sql).contains("uk_analysis_generations_account_scope");
        assertThat(sql).contains("UNIQUE (user_id, account_id, analysis_month, context_fingerprint)");
        assertThat(sql).contains("DROP INDEX uk_analysis_generations_scope");
        // Eligible user_id left-prefix index must exist before DROP (MySQL 8.4 Error 1553 /
        // fk_analysis_generations_user). Account-scoped unique is that replacement index.
        int accountScopeUnique = sql.indexOf("uk_analysis_generations_account_scope");
        int dropOldScope = sql.indexOf("DROP INDEX uk_analysis_generations_scope");
        assertThat(accountScopeUnique).isGreaterThanOrEqualTo(0);
        assertThat(dropOldScope).isGreaterThan(accountScopeUnique);
        assertThat(sql).contains("fk_analysis_snapshots_account");
        assertThat(sql).contains("fk_analysis_generations_account");
        assertThat(sql).contains("REFERENCES accounts (id, user_id)");
        assertThat(sql).doesNotContainIgnoringCase("DELETE FROM analysis_");
        assertThat(sql).doesNotContainIgnoringCase("UPDATE analysis_snapshots SET account_id");
        assertThat(sql).doesNotContainIgnoringCase("UPDATE analysis_generations SET account_id");
        assertThat(sql).doesNotContain("V11__");
        assertThat(sql).doesNotContain("DROP TABLE");
    }
}
