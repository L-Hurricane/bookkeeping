package com.limingyuan.bookkeeping.analysis.application;

import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.CategoryFact;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;

/** Authoritative overview, expense structure, and surplus fact. No model call. */
public final class DeterministicMonthlyReport {
    private DeterministicMonthlyReport() { }

    public static String monthlyOverview(MonthlyAnalysisContext context) {
        var text = new StringBuilder();
        text.append(month(context.yearMonth())).append("账本概况：净收入").append(context.netIncome()).append("元，")
                .append(context.incomeTransactionCount()).append("笔；净支出").append(context.netExpense()).append("元，")
                .append(context.expenseTransactionCount()).append("笔；结余").append(context.surplus()).append("元。");
        if (context.incomeTransactionCount() == 0) text.append("本月账本没有收入记录。");
        if (context.expenseTransactionCount() == 0) text.append("本月账本没有支出记录。");
        return text.toString();
    }

    public static String expenseStructure(MonthlyAnalysisContext context) {
        if (context.expenseTransactionCount() == 0) return "本月账本没有支出记录。";
        var text = new StringBuilder();
        text.append("支出净额").append(context.netExpense()).append("元。");
        boolean percent = allowsPercentage(context);
        for (var category : context.expenseCategories()) appendCategory(text, category, context.netExpense(), percent);
        var uncategorized = context.uncategorizedExpense();
        if (uncategorized.transactionCount() == 0) {
            text.append("本月没有未分类支出。");
        } else {
            text.append("未分类支出净额").append(uncategorized.netAmount()).append("元，")
                    .append(uncategorized.transactionCount()).append("笔");
            if (percent) text.append("，占支出净额").append(percent(uncategorized.netAmount(), context.netExpense())).append('%');
            text.append('。');
        }
        return text.toString();
    }

    public static String surplusFact(MonthlyAnalysisContext context) {
        return month(context.yearMonth()) + "结余为" + context.surplus() + "元。净收入" + context.netIncome()
                + "元，净支出" + context.netExpense() + "元。";
    }

    private static void appendCategory(StringBuilder text, CategoryFact category, String netExpense, boolean percent) {
        text.append(category.name()).append("净额").append(category.netAmount()).append("元，")
                .append(category.transactionCount()).append("笔");
        if (percent) text.append("，占支出净额").append(percent(category.netAmount(), netExpense)).append('%');
        text.append('。');
        if (category.children().isEmpty()) return;
        text.append("其中");
        for (int i = 0; i < category.children().size(); i++) {
            if (i > 0) text.append('；');
            var child = category.children().get(i);
            text.append(child.name()).append(child.netAmount()).append("元，").append(child.transactionCount()).append("笔");
        }
        text.append('。');
    }

    private static boolean allowsPercentage(MonthlyAnalysisContext context) {
        if (new BigDecimal(context.netExpense()).signum() <= 0) return false;
        if (new BigDecimal(context.uncategorizedExpense().netAmount()).signum() < 0) return false;
        for (var category : context.expenseCategories()) if (hasNegative(category)) return false;
        return true;
    }

    private static boolean hasNegative(CategoryFact category) {
        if (new BigDecimal(category.netAmount()).signum() < 0) return true;
        for (var child : category.children()) if (hasNegative(child)) return true;
        return false;
    }

    private static String percent(String part, String whole) {
        return new BigDecimal(part).movePointRight(2).divide(new BigDecimal(whole), 2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String month(String yearMonth) {
        var parsed = YearMonth.parse(yearMonth);
        return parsed.getYear() + "年" + parsed.getMonthValue() + "月";
    }
}
