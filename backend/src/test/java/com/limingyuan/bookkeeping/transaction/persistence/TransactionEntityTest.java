package com.limingyuan.bookkeeping.transaction.persistence;

import com.limingyuan.bookkeeping.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class TransactionEntityTest {
    @Test void reassignAccountKeepsTypeAndFirstSaveIdentity() {
        var firstSaved = LocalDateTime.of(2026, 9, 24, 4, 0, 0, 123_000_000);
        var entity = new TransactionEntity(1L, 10L, TransactionType.EXPENSE, new BigDecimal("20.00"), "跨账户",
                3L, LocalDateTime.of(2026, 9, 24, 12, 30, 0), firstSaved, 7L, "备注");
        entity.reassignAccount(20L);
        assertThat(entity.getAccountId()).isEqualTo(20L);
        assertThat(entity.getType()).isEqualTo(TransactionType.EXPENSE);
        assertThat(entity.getFirstSaveOrder()).isEqualTo(7L);
        assertThat(entity.getFirstSavedAtUtc()).isEqualTo(firstSaved);
        assertThat(entity.getAmount()).isEqualByComparingTo("20.00");
        assertThat(entity.getCategoryId()).isEqualTo(3L);
        assertThat(entity.getName()).isEqualTo("跨账户");
        assertThat(entity.getRemark()).isEqualTo("备注");
    }
}
