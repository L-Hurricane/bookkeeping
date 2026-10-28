package com.limingyuan.bookkeeping.analysis.application;

import java.time.Instant;

/** Result of asking for one whole-ledger generation permit. */
public sealed interface LedgerPermitAcquisition {
    record Reused(AnalysisSnapshot snapshot) implements LedgerPermitAcquisition { }

    record Granted(LedgerGenerationPermit permit) implements LedgerPermitAcquisition { }

    record Busy(Instant leaseExpiresAt) implements LedgerPermitAcquisition { }

    record QuotaExceeded() implements LedgerPermitAcquisition { }
}
