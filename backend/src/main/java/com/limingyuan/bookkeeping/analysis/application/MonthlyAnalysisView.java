package com.limingyuan.bookkeeping.analysis.application;

import java.time.Instant;
import java.util.List;

/** What the current user can see for one account month. Built from one facts read. */
public record MonthlyAnalysisView(
        String month,
        String fingerprint,
        boolean emptyMonth,
        String viewState,
        Facts facts,
        Snapshot snapshot,
        Generation generation) {

    public record Facts(
            String timeZone,
            String currency,
            String netIncome,
            String netExpense,
            String surplus,
            int incomeTransactionCount,
            int expenseTransactionCount,
            List<Category> incomeCategories,
            List<Category> expenseCategories,
            Uncategorized uncategorizedIncome,
            Uncategorized uncategorizedExpense,
            List<KeyExpense> keyExpenses) { }

    public record Category(String name, int transactionCount, String netAmount, List<Category> children) { }

    public record Uncategorized(int transactionCount, String netAmount) { }

    public record KeyExpense(
            String transactionType,
            String categoryPath,
            String transactionName,
            String signedAmount,
            String businessTime) { }

    /** A successful report. {@code stale} compares this snapshot with the current fingerprint. */
    public record Snapshot(
            long id,
            String fingerprint,
            boolean stale,
            String monthlyOverview,
            String expenseStructure,
            String surplusReasons,
            String pointsToWatch,
            String provider,
            String model,
            String promptVersion,
            Instant generatedAt) { }

    /** Operational state for the current fingerprint. {@code active} is false once the lease has expired. */
    public record Generation(String state, boolean active) { }
}
