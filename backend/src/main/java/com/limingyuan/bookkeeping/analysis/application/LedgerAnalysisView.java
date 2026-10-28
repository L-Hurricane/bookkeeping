package com.limingyuan.bookkeeping.analysis.application;

import java.time.Instant;

/** Whole-ledger analysis response. Amounts stay decimal strings from the statistics service. */
public record LedgerAnalysisView(
        String periodKind,
        String period,
        String fingerprint,
        boolean emptyPeriod,
        String viewState,
        String opening,
        String closing,
        String change,
        String incomeNet,
        String expenseNet,
        int incomeCount,
        int expenseCount,
        String priorPeriod,
        String priorChange,
        String priorIncomeNet,
        String priorExpenseNet,
        Snapshot snapshot,
        Generation generation) {

    public record Snapshot(long id, String fingerprint, boolean stale, String monthlyOverview, String expenseStructure,
            String surplusReasons, String pointsToWatch, String provider, String model, String promptVersion,
            Instant generatedAt) { }

    public record Generation(String state, boolean active) { }
}
