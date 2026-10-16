package com.limingyuan.bookkeeping.analysis.application;

import java.time.Instant;
import java.time.LocalDate;

/** Holder proof for one in-flight generation. Quota is attributed to {@code permitDate}. */
public record GenerationPermit(
        long generationId,
        long fencingToken,
        long userId,
        String yearMonth,
        String contextFingerprint,
        LocalDate permitDate,
        Instant leaseExpiresAt) { }
