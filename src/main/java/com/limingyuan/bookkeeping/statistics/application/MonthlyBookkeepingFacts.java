package com.limingyuan.bookkeeping.statistics.application;

import java.util.List;

/** Bookkeeping facts for one account and natural month. Tags and balance adjustments are absent. */
public record MonthlyBookkeepingFacts(
        long accountId,
        String yearMonth,
        String netIncome,
        String netExpense,
        String surplus,
        int incomeTransactionCount,
        int expenseTransactionCount,
        List<CategorySlice> incomeCategories,
        List<CategorySlice> expenseCategories,
        UncategorizedSlice uncategorizedIncome,
        UncategorizedSlice uncategorizedExpense,
        List<KeyExpenseSlice> keyExpenses) {

    public record CategorySlice(long categoryId, String name, int transactionCount, String netAmount,
            List<CategorySlice> children) { }

    public record UncategorizedSlice(int transactionCount, String netAmount) { }

    public record KeyExpenseSlice(String transactionType, String categoryPath, String transactionName,
            String signedAmount, String businessTime) { }
}
