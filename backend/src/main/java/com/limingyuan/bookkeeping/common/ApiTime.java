package com.limingyuan.bookkeeping.common;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** Stored Beijing local time, always labeled {@code +08:00}. Historical zone rules are not applied. */
public final class ApiTime {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneOffset BEIJING = ZoneOffset.ofHours(8);

    private ApiTime() { }

    public static String format(LocalDateTime local) {
        return OffsetDateTime.of(local, BEIJING).format(FORMATTER);
    }
}
