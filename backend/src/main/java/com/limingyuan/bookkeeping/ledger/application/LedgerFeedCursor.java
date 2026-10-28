package com.limingyuan.bookkeeping.ledger.application;

import com.limingyuan.bookkeeping.common.api.ApiFailure;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Stable position in the mixed ledger order. It is not a balance. */
public record LedgerFeedCursor(LocalDateTime businessTime, long firstSaveOrder, LocalDateTime firstSavedAt,
        String kind, long accountId, long id) {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    public String encode() {
        return String.join("|", TIME.format(businessTime), Long.toString(firstSaveOrder), TIME.format(firstSavedAt),
                kind, Long.toString(accountId), Long.toString(id));
    }

    public static LedgerFeedCursor parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 6) throw invalid();
        try {
            long order = Long.parseLong(parts[1]);
            long accountId = Long.parseLong(parts[4]);
            long id = Long.parseLong(parts[5]);
            if (order < 0 || accountId < 1 || id < 1) throw invalid();
            if (!parts[3].equals("TRANSACTION") && !parts[3].equals("TRANSFER") && !parts[3].equals("BALANCE_ADJUSTMENT")) {
                throw invalid();
            }
            return new LedgerFeedCursor(LocalDateTime.parse(parts[0], TIME), order, LocalDateTime.parse(parts[2], TIME),
                    parts[3], accountId, id);
        } catch (DateTimeParseException | NumberFormatException ex) {
            throw invalid();
        }
    }

    private static ApiFailure invalid() {
        return new ApiFailure(400, "VALIDATION_FAILED", "分页位置无法识别");
    }
}
