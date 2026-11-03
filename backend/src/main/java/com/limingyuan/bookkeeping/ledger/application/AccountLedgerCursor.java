package com.limingyuan.bookkeeping.ledger.application;

import com.limingyuan.bookkeeping.common.api.ApiFailure;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Position in one account ledger. Bound to that account and the active filter. */
public record AccountLedgerCursor(long accountId, String scope, LocalDateTime businessTime, long firstSaveOrder,
        LocalDateTime firstSavedAt, String kind, long id) {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS");

    public String encode() {
        return String.join("|", "v1", Long.toString(accountId), scope, TIME.format(businessTime),
                Long.toString(firstSaveOrder), TIME.format(firstSavedAt), kind, Long.toString(id));
    }

    public static AccountLedgerCursor parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 8 || !parts[0].equals("v1") || parts[2].isBlank()) throw invalid();
        try {
            long accountId = Long.parseLong(parts[1]);
            long order = Long.parseLong(parts[4]);
            long id = Long.parseLong(parts[7]);
            if (accountId < 1 || order < 1 || id < 1) throw invalid();
            if (!parts[6].equals("TRANSACTION") && !parts[6].equals("TRANSFER") && !parts[6].equals("BALANCE_ADJUSTMENT")) {
                throw invalid();
            }
            return new AccountLedgerCursor(accountId, parts[2], LocalDateTime.parse(parts[3], TIME), order,
                    LocalDateTime.parse(parts[5], TIME), parts[6], id);
        } catch (DateTimeParseException | NumberFormatException ex) {
            throw invalid();
        }
    }

    public void requireBound(long accountId, String scope) {
        if (this.accountId != accountId || !this.scope.equals(scope)) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "分页位置与当前筛选不一致");
        }
    }

    private static ApiFailure invalid() {
        return new ApiFailure(400, "VALIDATION_FAILED", "分页位置无法识别");
    }
}
