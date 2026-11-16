package com.limingyuan.bookkeeping.transaction.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class TransactionRequestHashTest {
    @Test void v2PrefixDiffersFromTheUnprefixedHashAndFromABlankCounterparty() {
        byte[] legacy = TransactionRequestHash.sha256(8L, "EXPENSE", "30.00", 3L, List.of(),
                "2026-09-20T12:00:00+08:00", "午餐", "");
        byte[] blank = TransactionRequestHash.sha256V2(8L, "EXPENSE", "30.00", 3L, List.of(),
                "2026-09-20T12:00:00+08:00", "午餐", "", "");
        byte[] named = hash("商店");
        assertThat(legacy).isNotEqualTo(blank);
        assertThat(blank).isNotEqualTo(named);
        assertThat(TransactionRequestHash.matchesStored(named, legacy, named, false)).isTrue();
        assertThat(TransactionRequestHash.matchesStored(named, legacy, blank, true)).isFalse();
        assertThat(TransactionRequestHash.matchesStored(legacy, legacy, blank, true)).isTrue();
        assertThat(TransactionRequestHash.matchesStored(legacy, legacy, named, false)).isFalse();
    }

    @Test void migrationAddsOnlyTheNullableColumn() throws Exception {
        try (var input = TransactionRequestHashTest.class.getResourceAsStream("/db/migration/V21__transaction_counterparty_name.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("ADD COLUMN counterparty_name VARCHAR(255) NULL");
            assertThat(sql).contains("ALGORITHM=INSTANT");
            assertThat(sql.toUpperCase()).doesNotContain("UPDATE");
            assertThat(sql.toUpperCase()).doesNotContain("INDEX");
            assertThat(sql.toUpperCase()).doesNotContain("CHECK");
        }
    }

    private static byte[] hash(String counterparty) {
        return TransactionRequestHash.sha256V2(8L, "EXPENSE", "30.00", 3L, List.of(),
                "2026-09-20T12:00:00+08:00", "午餐", "", counterparty);
    }
}
