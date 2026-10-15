package com.limingyuan.bookkeeping.transaction.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TransactionLikePatternTest {
    @Test void likePatternEscapesForSqlBinding() {
        assertThat(TransactionService.likeContainsPattern("a%b_c\\d")).isEqualTo("%a\\%b\\_c\\\\d%");
    }
}
