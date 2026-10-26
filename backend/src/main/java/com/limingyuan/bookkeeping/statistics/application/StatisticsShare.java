package com.limingyuan.bookkeeping.statistics.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Signed amount / abs(net), as a percent with two decimal places. Zero net has no ratio. */
public final class StatisticsShare {
    private StatisticsShare() { }

    public static String of(BigDecimal amount, BigDecimal net) {
        if (net.signum() == 0) return null;
        return amount.multiply(new BigDecimal("100")).divide(net.abs(), 2, RoundingMode.HALF_UP).toPlainString();
    }
}
