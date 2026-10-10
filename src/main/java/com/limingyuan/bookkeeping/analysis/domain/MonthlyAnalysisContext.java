package com.limingyuan.bookkeeping.analysis.domain;

import java.util.List;

/** One immutable fact snapshot. Display and fingerprint both read this object. */
public record MonthlyAnalysisContext(
        long accountId,
        String yearMonth,
        String timeZone,
        String currency,
        String netIncome,
        String netExpense,
        String surplus,
        int incomeTransactionCount,
        int expenseTransactionCount,
        List<CategoryFact> incomeCategories,
        List<CategoryFact> expenseCategories,
        UncategorizedFact uncategorizedIncome,
        UncategorizedFact uncategorizedExpense,
        List<KeyExpenseFact> keyExpenses) {

    public static final String TIME_ZONE = "Asia/Shanghai";
    public static final String CURRENCY = "CNY";

    public MonthlyAnalysisContext {
        incomeCategories = List.copyOf(incomeCategories);
        expenseCategories = List.copyOf(expenseCategories);
        keyExpenses = List.copyOf(keyExpenses);
    }

    public String fingerprint() {
        return AnalysisContextFingerprint.of(this);
    }

    public record CategoryFact(long categoryId, String name, int transactionCount, String netAmount,
            List<CategoryFact> children) {
        public CategoryFact {
            children = List.copyOf(children);
        }
    }

    public record UncategorizedFact(int transactionCount, String netAmount) { }

    public record KeyExpenseFact(String transactionType, String categoryPath, String transactionName,
            String signedAmount, String businessTime) { }
}
