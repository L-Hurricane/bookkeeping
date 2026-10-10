package com.limingyuan.bookkeeping.analysis.domain;

import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.CategoryFact;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.KeyExpenseFact;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.UncategorizedFact;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Contract 1. The hash is SHA-256 of the UTF-8 canonical JSON of analysis facts.
 * Account and user identity stay outside this value. Provider, model, prompt version,
 * and runtime fields are not written.
 */
public final class AnalysisContextFingerprint {
    public static final String CONTRACT_VERSION = "1";
    private static final HexFormat HEX = HexFormat.of();

    private AnalysisContextFingerprint() { }

    public static String of(MonthlyAnalysisContext context) {
        return CONTRACT_VERSION + ":" + HEX.formatHex(sha256(canonicalJson(context).getBytes(StandardCharsets.UTF_8)));
    }

    static String canonicalJson(MonthlyAnalysisContext context) {
        var json = new StringBuilder();
        json.append('{');
        field(json, "yearMonth", context.yearMonth());
        field(json, "timeZone", context.timeZone());
        field(json, "currency", context.currency());
        field(json, "netIncome", context.netIncome());
        field(json, "netExpense", context.netExpense());
        field(json, "surplus", context.surplus());
        field(json, "incomeTransactionCount", context.incomeTransactionCount());
        field(json, "expenseTransactionCount", context.expenseTransactionCount());
        json.append(",\"incomeCategories\":");
        categories(json, context.incomeCategories());
        json.append(",\"expenseCategories\":");
        categories(json, context.expenseCategories());
        json.append(",\"uncategorizedIncome\":");
        uncategorized(json, context.uncategorizedIncome());
        json.append(",\"uncategorizedExpense\":");
        uncategorized(json, context.uncategorizedExpense());
        json.append(",\"keyExpenses\":[");
        for (int i = 0; i < context.keyExpenses().size(); i++) {
            if (i > 0) json.append(',');
            keyExpense(json, context.keyExpenses().get(i));
        }
        json.append("]}");
        return json.toString();
    }

    private static void categories(StringBuilder json, List<CategoryFact> categories) {
        json.append('[');
        for (int i = 0; i < categories.size(); i++) {
            if (i > 0) json.append(',');
            category(json, categories.get(i));
        }
        json.append(']');
    }

    private static void category(StringBuilder json, CategoryFact category) {
        json.append('{');
        field(json, "categoryId", category.categoryId());
        field(json, "name", category.name());
        field(json, "transactionCount", category.transactionCount());
        field(json, "netAmount", category.netAmount());
        json.append(",\"children\":");
        categories(json, category.children());
        json.append('}');
    }

    private static void uncategorized(StringBuilder json, UncategorizedFact fact) {
        json.append('{');
        field(json, "transactionCount", fact.transactionCount());
        field(json, "netAmount", fact.netAmount());
        json.append('}');
    }

    private static void keyExpense(StringBuilder json, KeyExpenseFact expense) {
        json.append('{');
        field(json, "transactionType", expense.transactionType());
        field(json, "categoryPath", expense.categoryPath());
        field(json, "transactionName", expense.transactionName());
        field(json, "signedAmount", expense.signedAmount());
        field(json, "businessTime", expense.businessTime());
        json.append('}');
    }

    private static void field(StringBuilder json, String name, String value) {
        if (json.charAt(json.length() - 1) != '{') json.append(',');
        json.append('"').append(name).append("\":");
        writeString(json, value);
    }

    private static void field(StringBuilder json, String name, long value) {
        if (json.charAt(json.length() - 1) != '{') json.append(',');
        json.append('"').append(name).append("\":").append(value);
    }

    private static void writeString(StringBuilder json, String value) {
        if (value == null) {
            json.append("null");
            return;
        }
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) json.append(String.format("\\u%04x", (int) c));
                    else json.append(c);
                }
            }
        }
        json.append('"');
    }

    private static byte[] sha256(byte[] utf8) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(utf8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
