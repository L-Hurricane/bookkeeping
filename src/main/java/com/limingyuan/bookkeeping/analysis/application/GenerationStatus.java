package com.limingyuan.bookkeeping.analysis.application;

import java.time.Instant;
import java.time.LocalDate;

/** Coordination row for one generation scope. The fencing token stays with the holder. */
public record GenerationStatus(
        long generationId,
        long userId,
        String yearMonth,
        String contextFingerprint,
        GenerationState state,
        Instant leaseExpiresAt,
        LocalDate permitDate,
        Long snapshotId) { }
