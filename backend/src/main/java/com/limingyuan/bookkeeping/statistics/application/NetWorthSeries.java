package com.limingyuan.bookkeeping.statistics.application;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Folds opening balances and signed movements into inclusive business-time cutoffs. */
public final class NetWorthSeries {
    public record Movement(LocalDateTime time, BigDecimal delta) { }

    private NetWorthSeries() { }

    public static List<BigDecimal> atCutoffs(BigDecimal opening, List<Movement> movements, List<LocalDateTime> cutoffs) {
        List<BigDecimal> values = new ArrayList<>();
        BigDecimal running = opening;
        int index = 0;
        for (LocalDateTime cutoff : cutoffs) {
            while (index < movements.size() && !movements.get(index).time().isAfter(cutoff)) {
                running = running.add(movements.get(index).delta());
                index += 1;
            }
            values.add(running);
        }
        return List.copyOf(values);
    }
}
