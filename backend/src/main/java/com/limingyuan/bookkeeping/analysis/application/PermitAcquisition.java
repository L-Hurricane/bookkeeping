package com.limingyuan.bookkeeping.analysis.application;

import java.time.Instant;

/** Result of one atomic permit and quota attempt. */
public sealed interface PermitAcquisition {
    record Reused(AnalysisSnapshot snapshot) implements PermitAcquisition { }

    record Granted(GenerationPermit permit) implements PermitAcquisition { }

    record Busy(Instant leaseExpiresAt) implements PermitAcquisition { }

    record QuotaExceeded() implements PermitAcquisition { }
}
