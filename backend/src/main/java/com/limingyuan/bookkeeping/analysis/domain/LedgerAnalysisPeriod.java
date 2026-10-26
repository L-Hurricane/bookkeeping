package com.limingyuan.bookkeeping.analysis.domain;

import com.limingyuan.bookkeeping.common.api.ApiFailure;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/** Beijing natural month or year. Quarters and arbitrary ranges are rejected. */
public final class LedgerAnalysisPeriod {
    private LedgerAnalysisPeriod() { }

    public record Bounds(String periodKind, String period, LocalDate from, LocalDate to, String priorPeriod) { }

    public static Bounds parse(String periodKind, String period) {
        String kind = periodKind == null ? "" : periodKind.trim();
        String text = period == null ? "" : period.trim();
        try {
            if ("MONTH".equals(kind) && text.matches("[0-9]{4}-(0[1-9]|1[0-2])")) {
                YearMonth month = YearMonth.parse(text);
                return new Bounds(kind, text, month.atDay(1), month.atEndOfMonth(), month.minusMonths(1).toString());
            }
            if ("YEAR".equals(kind) && text.matches("[0-9]{4}")) {
                int year = Integer.parseInt(text);
                if (year < 1) throw rejected();
                return new Bounds(kind, text, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31),
                        String.format("%04d", year - 1));
            }
        } catch (DateTimeParseException ex) {
            throw rejected();
        }
        throw rejected();
    }

    private static ApiFailure rejected() {
        return new ApiFailure(400, "VALIDATION_FAILED", "分析期间必须是自然月 YYYY-MM 或自然年 YYYY");
    }
}
