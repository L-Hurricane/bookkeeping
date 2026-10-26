package com.limingyuan.bookkeeping.analysis.application;

import java.time.Instant;
import java.time.LocalDate;

/** In-flight whole-ledger generation. There is no account id on this permit. */
public record LedgerGenerationPermit(
        long generationId,
        long fencingToken,
        long userId,
        String periodKind,
        String period,
        String contextFingerprint,
        LocalDate permitDate,
        Instant leaseExpiresAt) { }
