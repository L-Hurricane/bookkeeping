package com.limingyuan.bookkeeping.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class LedgerOrderingTest {
    @Test void keepsPrimaryKeysAndAddsStableTieBreakers() {
        assertThat(LedgerOrdering.TRANSACTION_LIST)
                .startsWith("t.business_time DESC, t.first_save_order DESC")
                .contains("t.first_saved_at_utc DESC")
                .endsWith("t.id DESC");
        assertThat(LedgerOrdering.LEDGER)
                .startsWith("business_time DESC, first_save_order DESC")
                .contains("first_saved_at_utc DESC")
                .contains("kind DESC")
                .endsWith("id DESC");
        assertThat(LedgerOrdering.KEY_EXPENSE_TIEBREAK)
                .contains("t.first_saved_at_utc DESC")
                .endsWith("t.id DESC");
    }

    @Test void callSiteConcatenationKeepsSqlTokensSeparated() {
        assertThat(" ORDER BY " + LedgerOrdering.LEDGER)
                .startsWith(" ORDER BY business_time ")
                .doesNotContain("BYbusiness_time");
        assertThat(" ORDER BY " + LedgerOrdering.TRANSACTION_LIST)
                .startsWith(" ORDER BY t.business_time ")
                .doesNotContain("BYt.business_time");
        assertThat(" ORDER BY ABS(t.amount) DESC, " + LedgerOrdering.KEY_EXPENSE_TIEBREAK + " LIMIT 5")
                .contains("DESC, t.business_time")
                .contains("t.id DESC LIMIT 5")
                .doesNotContain("DESCLIMIT");
    }
}
