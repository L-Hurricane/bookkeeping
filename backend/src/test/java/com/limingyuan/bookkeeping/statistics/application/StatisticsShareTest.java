package com.limingyuan.bookkeeping.statistics.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class StatisticsShareTest {
    @Test
    void signedShareUsesTheAbsoluteNetAndOmitsAZeroNet() {
        assertEquals("40.00", StatisticsShare.of(new BigDecimal("400.00"), new BigDecimal("1000.00")));
        assertEquals("-40.00", StatisticsShare.of(new BigDecimal("-400.00"), new BigDecimal("-1000.00")));
        assertEquals("-40.00", StatisticsShare.of(new BigDecimal("-400.00"), new BigDecimal("1000.00")));
        assertEquals("125.00", StatisticsShare.of(new BigDecimal("100.00"), new BigDecimal("80.00")));
        assertEquals("-25.00", StatisticsShare.of(new BigDecimal("-20.00"), new BigDecimal("80.00")));
        assertNull(StatisticsShare.of(new BigDecimal("10.00"), new BigDecimal("0.00")));
    }
}
