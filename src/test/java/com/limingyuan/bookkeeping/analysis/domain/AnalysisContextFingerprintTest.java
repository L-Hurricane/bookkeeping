package com.limingyuan.bookkeeping.analysis.domain;

import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.CategoryFact;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.KeyExpenseFact;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.UncategorizedFact;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisContextFingerprintTest {
    private static final String CANONICAL = """
            {"yearMonth":"2026-09","timeZone":"Asia/Shanghai","currency":"CNY","netIncome":"10.00","netExpense":"4.00","surplus":"6.00","incomeTransactionCount":1,"expenseTransactionCount":1,"incomeCategories":[{"categoryId":3,"name":"工资","transactionCount":1,"netAmount":"10.00","children":[]}],"expenseCategories":[{"categoryId":9,"name":"餐饮","transactionCount":1,"netAmount":"4.00","children":[{"categoryId":11,"name":"早餐","transactionCount":1,"netAmount":"4.00","children":[]}]}],"uncategorizedIncome":{"transactionCount":0,"netAmount":"0.00"},"uncategorizedExpense":{"transactionCount":2,"netAmount":"0.00"},"keyExpenses":[{"transactionType":"EXPENSE","categoryPath":"餐饮 > 早餐","transactionName":"豆浆","signedAmount":"4.00","businessTime":"2026-09-03T08:00:00+08:00"}]}""";

    @Test void canonicalJsonIsVersionedAndExcludesAccountIdentity() throws Exception {
        var context = sample(7L, "10.00", List.of(key("餐饮 > 早餐", "豆浆")));
        assertThat(AnalysisContextFingerprint.canonicalJson(context)).isEqualTo(CANONICAL.strip());
        assertThat(AnalysisContextFingerprint.canonicalJson(sample(99L, "10.00", List.of(key("餐饮 > 早餐", "豆浆")))))
                .isEqualTo(CANONICAL.strip());
        assertThat(CANONICAL).doesNotContain("accountId").doesNotContain("provider").doesNotContain("prompt");
        String fingerprint = context.fingerprint();
        assertThat(fingerprint).startsWith("1:").hasSize(66);
        assertThat(fingerprint.substring(2)).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(CANONICAL.strip().getBytes(StandardCharsets.UTF_8))));
        assertThat(context.fingerprint()).isEqualTo(fingerprint);
    }

    @Test void nullsStayNullAndFactChangesChangeTheFingerprint() {
        var original = sample(7L, "10.00", List.of(key("餐饮 > 早餐", "豆浆")));
        var missing = sample(7L, "10.00", List.of(new KeyExpenseFact("EXPENSE", null, null, "4.00",
                "2026-09-03T08:00:00+08:00")));
        assertThat(AnalysisContextFingerprint.canonicalJson(missing)).contains("\"categoryPath\":null", "\"transactionName\":null");
        assertThat(missing.fingerprint()).isNotEqualTo(original.fingerprint());
        assertThat(sample(7L, "10.01", List.of(key("餐饮 > 早餐", "豆浆"))).fingerprint()).isNotEqualTo(original.fingerprint());
        assertThat(sample(7L, "10.00", List.of(key("餐饮 > 早餐", "豆浆\"杯"))).fingerprint()).isNotEqualTo(original.fingerprint());
        assertThat(AnalysisContextFingerprint.canonicalJson(sample(7L, "10.00", List.of(key("餐饮 > 早餐", "豆浆\"杯")))))
                .contains("豆浆\\\"杯");
        assertThat(sample(7L, "10.00", List.of()).fingerprint()).isNotEqualTo(original.fingerprint());
    }

    private static MonthlyAnalysisContext sample(long accountId, String netIncome, List<KeyExpenseFact> keys) {
        return new MonthlyAnalysisContext(accountId, "2026-09", MonthlyAnalysisContext.TIME_ZONE, MonthlyAnalysisContext.CURRENCY,
                netIncome, "4.00", "6.00", 1, 1,
                List.of(new CategoryFact(3L, "工资", 1, netIncome, List.of())),
                List.of(new CategoryFact(9L, "餐饮", 1, "4.00", List.of(new CategoryFact(11L, "早餐", 1, "4.00", List.of())))),
                new UncategorizedFact(0, "0.00"), new UncategorizedFact(2, "0.00"), keys);
    }

    private static KeyExpenseFact key(String path, String name) {
        return new KeyExpenseFact("EXPENSE", path, name, "4.00", "2026-09-03T08:00:00+08:00");
    }
}
