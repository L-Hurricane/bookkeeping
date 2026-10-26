package com.limingyuan.bookkeeping.statistics.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.limingyuan.bookkeeping.common.api.ApiFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatisticsPeriodTest {
    private static final Clock NOON = Clock.fixed(Instant.parse("2026-10-26T04:00:00Z"), ZoneOffset.UTC);

    @Test
    void monthQuarterAndYearUseBeijingNaturalBounds() {
        assertEquals(LocalDate.parse("2026-10-01"), StatisticsPeriod.resolve("MONTH", null, null, NOON).from());
        assertEquals(LocalDate.parse("2026-10-31"), StatisticsPeriod.resolve("MONTH", null, null, NOON).to());
        assertEquals(LocalDate.parse("2026-10-01"), StatisticsPeriod.resolve("QUARTER", null, null, NOON).from());
        assertEquals(LocalDate.parse("2026-12-31"), StatisticsPeriod.resolve("QUARTER", null, null, NOON).to());
        assertEquals(LocalDate.parse("2026-01-01"), StatisticsPeriod.resolve("YEAR", null, null, NOON).from());
        assertEquals(LocalDate.parse("2026-12-31"), StatisticsPeriod.resolve("YEAR", null, null, NOON).to());
    }

    @Test
    void monthStartsAtBeijingMidnightEvenWhenUtcIsStillThePreviousDay() {
        Clock lateEvening = Clock.fixed(Instant.parse("2026-10-31T16:30:00Z"), ZoneOffset.UTC);
        StatisticsPeriod.Range range = StatisticsPeriod.resolve("MONTH", null, null, lateEvening);
        assertEquals(LocalDate.parse("2026-11-01"), range.from());
        assertEquals(LocalDate.parse("2026-11-30"), range.to());
    }

    @Test
    void customRangeKeepsInclusiveDatesAndRejectsAnInvertedRange() {
        StatisticsPeriod.Range range = StatisticsPeriod.resolve("CUSTOM", "2026-01-15", "2026-02-02", NOON);
        assertEquals(LocalDate.parse("2026-01-15"), range.from());
        assertEquals(LocalDateTime.parse("2026-02-02T23:59:59"), range.endInclusive());
        assertEquals(LocalDateTime.parse("2026-01-14T23:59:59"), range.openingCutoff());
        assertThrows(ApiFailure.class, () -> StatisticsPeriod.resolve("CUSTOM", "2026-02-02", "2026-01-15", NOON));
    }

    @Test
    void samplesAreDailyInsideAMonthAndMonthlyBeyondIt() {
        assertEquals(31, StatisticsPeriod.samples(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")).size());
        List<LocalDate> year = StatisticsPeriod.samples(LocalDate.parse("2026-01-15"), LocalDate.parse("2026-12-31"));
        assertEquals(LocalDate.parse("2026-01-31"), year.get(0));
        assertEquals(LocalDate.parse("2026-12-31"), year.get(year.size() - 1));
        assertEquals(12, year.size());
    }
}
