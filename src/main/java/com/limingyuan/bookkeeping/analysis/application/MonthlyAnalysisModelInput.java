package com.limingyuan.bookkeeping.analysis.application;

import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.CategoryFact;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.KeyExpenseFact;
import java.util.ArrayList;
import java.util.List;

/** Read-only model input projected from one analysis context. It does not read the database. */
public record MonthlyAnalysisModelInput(
        String month,
        String timeZone,
        String currency,
        String unit,
        MonthlySummary monthlySummary,
        List<CategoryInput> incomeCategories,
        List<CategoryInput> expenseCategories,
        UncategorizedInput uncategorized,
        List<KeyExpenseInput> keyExpenseTransactions) {

    public static final String UNIT = "元";

    public MonthlyAnalysisModelInput {
        incomeCategories = List.copyOf(incomeCategories);
        expenseCategories = List.copyOf(expenseCategories);
        keyExpenseTransactions = List.copyOf(keyExpenseTransactions);
    }

    public static MonthlyAnalysisModelInput from(MonthlyAnalysisContext context) {
        return new MonthlyAnalysisModelInput(context.yearMonth(), context.timeZone(), context.currency(), UNIT,
                new MonthlySummary(context.netIncome(), context.netExpense(), context.surplus(),
                        context.incomeTransactionCount(), context.expenseTransactionCount()),
                categories(context.incomeCategories()), categories(context.expenseCategories()),
                new UncategorizedInput(side(context.uncategorizedIncome()), side(context.uncategorizedExpense())),
                keys(context.keyExpenses()));
    }

    public String json() {
        var out = new StringBuilder();
        out.append('{');
        string(out, "month", month);
        string(out, "timeZone", timeZone);
        string(out, "currency", currency);
        string(out, "unit", unit);
        out.append(",\"monthlySummary\":");
        summary(out, monthlySummary);
        out.append(",\"incomeCategories\":");
        categories(out, incomeCategories);
        out.append(",\"expenseCategories\":");
        categories(out, expenseCategories);
        out.append(",\"uncategorized\":");
        uncategorized(out, uncategorized);
        out.append(",\"keyExpenseTransactions\":[");
        for (int i = 0; i < keyExpenseTransactions.size(); i++) {
            if (i > 0) out.append(',');
            key(out, keyExpenseTransactions.get(i));
        }
        out.append("]}");
        return out.toString();
    }

    private static List<CategoryInput> categories(List<CategoryFact> facts) {
        var inputs = new ArrayList<CategoryInput>();
        for (var fact : facts) inputs.add(category(fact));
        return inputs;
    }

    private static CategoryInput category(CategoryFact fact) {
        return new CategoryInput(fact.name(), fact.netAmount(), fact.transactionCount(), categories(fact.children()));
    }

    private static UncategorizedSide side(MonthlyAnalysisContext.UncategorizedFact fact) {
        return new UncategorizedSide(fact.netAmount(), fact.transactionCount());
    }

    private static List<KeyExpenseInput> keys(List<KeyExpenseFact> facts) {
        var inputs = new ArrayList<KeyExpenseInput>();
        for (var fact : facts) {
            inputs.add(new KeyExpenseInput(fact.transactionType(), fact.categoryPath(), fact.transactionName(),
                    fact.signedAmount(), fact.businessTime()));
        }
        return inputs;
    }

    private static void summary(StringBuilder out, MonthlySummary summary) {
        out.append('{');
        string(out, "netIncome", summary.netIncome());
        string(out, "netExpense", summary.netExpense());
        string(out, "surplus", summary.surplus());
        number(out, "incomeTransactionCount", summary.incomeTransactionCount());
        number(out, "expenseTransactionCount", summary.expenseTransactionCount());
        out.append('}');
    }

    private static void categories(StringBuilder out, List<CategoryInput> categories) {
        out.append('[');
        for (int i = 0; i < categories.size(); i++) {
            if (i > 0) out.append(',');
            category(out, categories.get(i));
        }
        out.append(']');
    }

    private static void category(StringBuilder out, CategoryInput category) {
        out.append('{');
        string(out, "name", category.name());
        string(out, "netAmount", category.netAmount());
        number(out, "transactionCount", category.transactionCount());
        out.append(",\"children\":");
        categories(out, category.children());
        out.append('}');
    }

    private static void uncategorized(StringBuilder out, UncategorizedInput input) {
        out.append('{');
        out.append("\"income\":");
        side(out, input.income());
        out.append(",\"expense\":");
        side(out, input.expense());
        out.append('}');
    }

    private static void side(StringBuilder out, UncategorizedSide side) {
        out.append('{');
        string(out, "netAmount", side.netAmount());
        number(out, "transactionCount", side.transactionCount());
        out.append('}');
    }

    private static void key(StringBuilder out, KeyExpenseInput expense) {
        out.append('{');
        string(out, "transactionType", expense.transactionType());
        string(out, "categoryPath", expense.categoryPath());
        string(out, "transactionName", expense.transactionName());
        string(out, "signedAmount", expense.signedAmount());
        string(out, "businessTime", expense.businessTime());
        out.append('}');
    }

    private static void string(StringBuilder out, String name, String value) {
        if (out.charAt(out.length() - 1) != '{') out.append(',');
        out.append('"').append(name).append("\":");
        if (value == null) {
            out.append("null");
            return;
        }
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static void number(StringBuilder out, String name, int value) {
        if (out.charAt(out.length() - 1) != '{') out.append(',');
        out.append('"').append(name).append("\":").append(value);
    }

    public record MonthlySummary(String netIncome, String netExpense, String surplus, int incomeTransactionCount,
            int expenseTransactionCount) { }

    public record CategoryInput(String name, String netAmount, int transactionCount, List<CategoryInput> children) {
        public CategoryInput {
            children = List.copyOf(children);
        }
    }

    public record UncategorizedInput(UncategorizedSide income, UncategorizedSide expense) { }

    public record UncategorizedSide(String netAmount, int transactionCount) { }

    public record KeyExpenseInput(String transactionType, String categoryPath, String transactionName,
            String signedAmount, String businessTime) { }
}
