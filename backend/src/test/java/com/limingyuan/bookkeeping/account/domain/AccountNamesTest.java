package com.limingyuan.bookkeeping.account.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountNamesTest {
    @Test void trimsAndAcceptsBoundaryLength() {
        assertThat(AccountNames.required("  微信  ")).isEqualTo("微信");
        assertThat(AccountNames.required("a".repeat(AccountNames.MAX_LENGTH))).hasSize(64);
    }

    @Test void rejectsNullBlankAndTooLong() {
        assertThatThrownBy(() -> AccountNames.required(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountNames.required("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountNames.required("a".repeat(AccountNames.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
