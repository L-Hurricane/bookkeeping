package com.limingyuan.bookkeeping.analysis.application;

import java.time.Instant;
import java.time.LocalDate;

/** One immutable successful analysis. Later ledger edits do not change it. */
public record AnalysisSnapshot(
        long id,
        long userId,
        String yearMonth,
        String contextFingerprint,
        MonthlyAnalysisReport report,
        AnalysisProvenance provenance,
        LocalDate permitDate,
        Instant generatedAt) { }
