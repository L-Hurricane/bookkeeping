package com.limingyuan.bookkeeping.analysis.application;

import com.limingyuan.bookkeeping.analysis.domain.LedgerAnalysisPeriod;
import com.limingyuan.bookkeeping.statistics.application.LedgerStatisticsService.CategoryNode;
import com.limingyuan.bookkeeping.statistics.application.LedgerStatisticsService.Flow;
import com.limingyuan.bookkeeping.statistics.application.LedgerStatisticsService.Report;
import com.limingyuan.bookkeeping.statistics.application.LedgerStatisticsService.Slice;
import com.limingyuan.bookkeeping.statistics.application.LedgerStatisticsService.TagNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic whole-ledger facts. The fingerprint and model payload contain aggregates only.
 * Account names, transaction names, and notes are not included.
 */
public record LedgerAnalysisFacts(
        String periodKind,
        String period,
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
        String incomeCategories,
        String expenseCategories,
        String incomeTags,
        String expenseTags) {

    public static LedgerAnalysisFacts from(LedgerAnalysisPeriod.Bounds bounds, Report current, Report prior) {
        return new LedgerAnalysisFacts(bounds.periodKind(), bounds.period(), current.netWorth().opening(),
                current.netWorth().closing(), current.netWorth().change(), current.income().net(), current.expense().net(),
                count(current.income()), count(current.expense()), bounds.priorPeriod(), prior.netWorth().change(),
                prior.income().net(), prior.expense().net(), tree(current.income()), tree(current.expense()),
                tags(current.income()), tags(current.expense()));
    }

    public boolean emptyPeriod() {
        return incomeCount == 0 && expenseCount == 0;
    }

    public String fingerprint() {
        return "ledger-1:" + HexFormat.of().formatHex(sha256(json().getBytes(StandardCharsets.UTF_8)));
    }

    /** Model user message. No account names, transaction names, or notes. */
    public String json() {
        return "{\"periodKind\":" + quote(periodKind)
                + ",\"period\":" + quote(period)
                + ",\"timeZone\":\"Asia/Shanghai\",\"currency\":\"CNY\""
                + ",\"opening\":" + quote(opening)
                + ",\"closing\":" + quote(closing)
                + ",\"change\":" + quote(change)
                + ",\"incomeNet\":" + quote(incomeNet)
                + ",\"expenseNet\":" + quote(expenseNet)
                + ",\"incomeCount\":" + incomeCount
                + ",\"expenseCount\":" + expenseCount
                + ",\"incomeCategories\":" + incomeCategories
                + ",\"expenseCategories\":" + expenseCategories
                + ",\"incomeTags\":" + incomeTags
                + ",\"expenseTags\":" + expenseTags
                + ",\"priorPeriod\":" + quote(priorPeriod)
                + ",\"priorChange\":" + quote(priorChange)
                + ",\"priorIncomeNet\":" + quote(priorIncomeNet)
                + ",\"priorExpenseNet\":" + quote(priorExpenseNet) + "}";
    }

    public String overview() {
        return periodLabel() + "全账本净值：期初" + opening + "元，期末" + closing + "元，变化" + change + "元。";
    }

    public String structure() {
        return "收入净额" + incomeNet + "元，" + incomeCount + "笔。支出净额" + expenseNet + "元，" + expenseCount
                + "笔。分类与标签金额以服务端汇总为准，重叠标签不能相加。";
    }

    public String comparison() {
        return "对比" + priorPeriod + "：净值变化" + priorChange + "元，收入净额" + priorIncomeNet + "元，支出净额"
                + priorExpenseNet + "元。";
    }

    private String periodLabel() {
        return "YEAR".equals(periodKind) ? period + "年" : period;
    }

    private static int count(Flow flow) {
        int total = 0;
        for (CategoryNode node : flow.categories()) total += node.transactionCount();
        if (flow.uncategorized() != null) total += flow.uncategorized().transactionCount();
        return total;
    }

    private static String tree(Flow flow) {
        var out = new StringBuilder();
        out.append('[');
        for (int i = 0; i < flow.categories().size(); i++) {
            if (i > 0) out.append(',');
            category(out, flow.categories().get(i));
        }
        if (flow.uncategorized() != null) {
            if (!flow.categories().isEmpty()) out.append(',');
            slice(out, "未分类", flow.uncategorized());
        }
        out.append(']');
        return out.toString();
    }

    private static String tags(Flow flow) {
        var out = new StringBuilder();
        out.append('[');
        for (int i = 0; i < flow.tags().size(); i++) {
            if (i > 0) out.append(',');
            tag(out, flow.tags().get(i));
        }
        if (flow.untagged() != null) {
            if (!flow.tags().isEmpty()) out.append(',');
            slice(out, "无标签", flow.untagged());
        }
        out.append(']');
        return out.toString();
    }

    private static void category(StringBuilder out, CategoryNode node) {
        out.append("{\"name\":").append(quote(node.name()))
                .append(",\"count\":").append(node.transactionCount())
                .append(",\"amount\":").append(quote(node.amount()))
                .append(",\"share\":").append(quote(node.share()))
                .append(",\"children\":[");
        List<CategoryNode> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) out.append(',');
            category(out, children.get(i));
        }
        out.append("]}");
    }

    private static void tag(StringBuilder out, TagNode node) {
        out.append("{\"name\":").append(quote(node.name()))
                .append(",\"count\":").append(node.transactionCount())
                .append(",\"amount\":").append(quote(node.amount()))
                .append(",\"share\":").append(quote(node.share()))
                .append(",\"children\":[");
        for (int i = 0; i < node.children().size(); i++) {
            if (i > 0) out.append(',');
            tag(out, node.children().get(i));
        }
        out.append("]}");
    }

    private static void slice(StringBuilder out, String name, Slice slice) {
        out.append("{\"name\":").append(quote(name))
                .append(",\"count\":").append(slice.transactionCount())
                .append(",\"amount\":").append(quote(slice.amount()))
                .append(",\"share\":").append(quote(slice.share()))
                .append(",\"children\":[]}");
    }

    private static String quote(String value) {
        if (value == null) return "null";
        var out = new StringBuilder();
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\');
            out.append(c);
        }
        out.append('"');
        return out.toString();
    }

    private static byte[] sha256(byte[] utf8) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(utf8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
