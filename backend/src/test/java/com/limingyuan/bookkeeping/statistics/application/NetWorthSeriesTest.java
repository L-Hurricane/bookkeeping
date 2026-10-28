package com.limingyuan.bookkeeping.statistics.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class NetWorthSeriesTest {
    @Test
    void carriesAQuietDayAndKeepsSameSecondAndFutureEventsOnTheirOwnCutoff() {
        List<NetWorthSeries.Movement> movements = List.of(
                movement("2026-03-31T22:00:00", "-2000.00"),
                movement("2026-04-01T08:00:00", "2000.00"),
                movement("2026-05-06T10:00:00", "-50.00"),
                movement("2026-05-06T10:00:00", "9.00"),
                movement("2026-05-07T00:00:00", "100.00"));
        List<BigDecimal> values = NetWorthSeries.atCutoffs(new BigDecimal("1000.00"), movements, List.of(
                LocalDateTime.parse("2026-03-30T23:59:59"),
                LocalDateTime.parse("2026-03-31T23:59:59"),
                LocalDateTime.parse("2026-04-01T23:59:59"),
                LocalDateTime.parse("2026-05-06T09:59:59"),
                LocalDateTime.parse("2026-05-06T23:59:59"),
                LocalDateTime.parse("2026-05-07T23:59:59")));
        assertEquals(new BigDecimal("1000.00"), values.get(0));
        assertEquals(new BigDecimal("-1000.00"), values.get(1));
        assertEquals(new BigDecimal("1000.00"), values.get(2));
        assertEquals(new BigDecimal("1000.00"), values.get(3));
        assertEquals(new BigDecimal("959.00"), values.get(4));
        assertEquals(new BigDecimal("1059.00"), values.get(5));
    }

    private static NetWorthSeries.Movement movement(String time, String delta) {
        return new NetWorthSeries.Movement(LocalDateTime.parse(time), new BigDecimal(delta));
    }
}
