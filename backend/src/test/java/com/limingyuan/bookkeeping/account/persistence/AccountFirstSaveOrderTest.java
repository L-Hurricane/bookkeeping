package com.limingyuan.bookkeeping.account.persistence;

import com.limingyuan.bookkeeping.account.domain.AccountKind;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountFirstSaveOrderTest {
    @Test void sharedOrderRaisesBothCountersAndDoesNotMoveBackwards() {
        var earlier = account();
        var later = account();
        earlier.allocateNextFirstSaveOrder(LocalDateTime.parse("2026-10-23T01:00:00"));
        earlier.allocateNextFirstSaveOrder(LocalDateTime.parse("2026-10-23T01:00:01"));
        later.allocateNextFirstSaveOrder(LocalDateTime.parse("2026-10-23T01:00:02"));
        assertThat(earlier.getLastFirstSaveOrder()).isEqualTo(2);
        assertThat(later.getLastFirstSaveOrder()).isEqualTo(1);
        long shared = Math.max(earlier.getLastFirstSaveOrder(), later.getLastFirstSaveOrder()) + 1;
        var now = LocalDateTime.parse("2026-10-23T01:00:03");
        earlier.raiseFirstSaveOrderTo(shared, now);
        later.raiseFirstSaveOrderTo(shared, now);
        assertThat(shared).isEqualTo(3);
        assertThat(earlier.getLastFirstSaveOrder()).isEqualTo(3);
        assertThat(later.getLastFirstSaveOrder()).isEqualTo(3);
        assertThatThrownBy(() -> earlier.raiseFirstSaveOrderTo(2, now)).isInstanceOf(IllegalStateException.class);
    }

    private static AccountEntity account() {
        return new AccountEntity(1, "现金", AccountKind.NORMAL, new BigDecimal("0.00"),
                LocalDateTime.parse("2026-01-01T00:00:00"), LocalDateTime.parse("2026-01-01T00:00:00"));
    }
}
