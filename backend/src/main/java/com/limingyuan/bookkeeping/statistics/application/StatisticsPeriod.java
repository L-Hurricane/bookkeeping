package com.limingyuan.bookkeeping.statistics.application;

import com.limingyuan.bookkeeping.common.api.ApiFailure;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** Beijing natural bounds for the approved statistics presets. */
public final class StatisticsPeriod {
    public static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final long MAX_CUSTOM_DAYS = 3660;

    private StatisticsPeriod() { }

    public record Range(String preset, LocalDate from, LocalDate to) {
        public LocalDateTime start() { return from.atStartOfDay(); }
        public LocalDateTime endInclusive() { return to.atTime(23, 59, 59); }
        public LocalDateTime openingCutoff() { return from.minusDays(1).atTime(23, 59, 59); }
    }

    public static Range resolve(String preset, String from, String to, Clock clock) {
        String name = preset == null ? "" : preset.trim();
        LocalDate today = LocalDate.now(clock.withZone(BEIJING));
        return switch (name) {
            case "MONTH" -> new Range(name, today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()));
            case "QUARTER" -> quarter(today);
            case "YEAR" -> new Range(name, today.withDayOfYear(1), today.withMonth(12).withDayOfMonth(31));
            case "ROLLING_MONTH" -> new Range(name, today.minusMonths(1), today);
            case "ROLLING_3_MONTHS" -> new Range(name, today.minusMonths(3), today);
            case "ROLLING_YEAR" -> new Range(name, today.minusYears(1), today);
            case "CUSTOM" -> custom(from, to);
            default -> throw new ApiFailure(400, "VALIDATION_FAILED", "时间范围必须为本月、本季度、本年、近1月、近3月、近1年或自定义");
        };
    }

    /** Daily samples for a short range, otherwise month ends plus the range end. */
    public static List<LocalDate> samples(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        List<LocalDate> dates = new ArrayList<>();
        if (days <= 31) {
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) dates.add(day);
            return dates;
        }
        LocalDate cursor = from.withDayOfMonth(from.lengthOfMonth());
        while (!cursor.isAfter(to)) {
            dates.add(cursor);
            LocalDate next = cursor.plusMonths(1);
            cursor = next.withDayOfMonth(next.lengthOfMonth());
        }
        if (dates.isEmpty() || !dates.get(dates.size() - 1).equals(to)) dates.add(to);
        return List.copyOf(dates);
    }

    private static Range quarter(LocalDate today) {
        int startMonth = ((today.getMonthValue() - 1) / 3) * 3 + 1;
        LocalDate from = LocalDate.of(today.getYear(), startMonth, 1);
        LocalDate to = from.plusMonths(2).withDayOfMonth(from.plusMonths(2).lengthOfMonth());
        return new Range("QUARTER", from, to);
    }

    private static Range custom(String fromRaw, String toRaw) {
        LocalDate from = date(fromRaw, "开始日期");
        LocalDate to = date(toRaw, "结束日期");
        if (from.isAfter(to)) throw new ApiFailure(400, "VALIDATION_FAILED", "开始日期不能晚于结束日期");
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_CUSTOM_DAYS) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "自定义范围最长 3660 天");
        }
        return new Range("CUSTOM", from, to);
    }

    private static LocalDate date(String raw, String label) {
        try { return LocalDate.parse(raw); }
        catch (DateTimeParseException | NullPointerException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", label + "必须为 YYYY-MM-DD");
        }
    }
}
