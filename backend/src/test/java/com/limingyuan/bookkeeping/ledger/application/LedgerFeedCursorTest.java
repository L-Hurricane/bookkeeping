package com.limingyuan.bookkeeping.ledger.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.limingyuan.bookkeeping.common.api.ApiFailure;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class LedgerFeedCursorTest {
    @Test
    void roundTripsTheMixedLedgerPosition() {
        var cursor = new LedgerFeedCursor(LocalDateTime.parse("2026-10-01T09:00:00"), 4,
                LocalDateTime.parse("2026-10-01T01:02:03"), "TRANSFER", 9, 12);
        assertEquals(cursor, LedgerFeedCursor.parse(cursor.encode()));
    }

    @Test
    void rejectsAShortOrUnknownKind() {
        assertThrows(ApiFailure.class, () -> LedgerFeedCursor.parse("2026-10-01T09:00:00|1"));
        assertThrows(ApiFailure.class, () -> LedgerFeedCursor.parse(
                "2026-10-01T09:00:00|1|2026-10-01T01:02:03|NOTE|9|12"));
    }
}
