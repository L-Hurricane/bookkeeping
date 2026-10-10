package com.limingyuan.bookkeeping.analysis.infrastructure.dashscope;

import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisFactsService;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisReportGeneration;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.CategoryFact;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.KeyExpenseFact;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.UncategorizedFact;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.assertThat;

/** One manual live call. Surefire's default includes do not run this class. */
@EnabledIfSystemProperty(named = "bookkeeping.analysis.live", matches = "true")
class MonthlyAnalysisLiveSmoke {
    @Test void oneSyntheticFlashCall() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        String baseUrl = System.getenv("DASHSCOPE_BASE_URL");
        if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY and DASHSCOPE_BASE_URL are required");
        }
        var generator = new QwenMonthlyAnalysisGenerator(QwenMonthlyAnalysisGenerator.httpClient(Duration.ofSeconds(10)),
                baseUrl, apiKey, "qwen3.7-flash", Duration.ofSeconds(60), 2048);
        var service = new MonthlyAnalysisReportGeneration(new MonthlyAnalysisFactsService((accountId, yearMonth) -> {
            throw new IllegalStateException("live smoke must not read a ledger");
        }), generator);
        var generated = service.generate(synthetic());
        var report = generated.report();
        var call = generated.generation();
        System.out.println("provider=" + call.provider());
        System.out.println("model=" + call.model());
        System.out.println("latencyMs=" + call.latencyMillis());
        System.out.println("promptTokens=" + call.promptTokens());
        System.out.println("completionTokens=" + call.completionTokens());
        System.out.println("totalTokens=" + call.totalTokens());
        System.out.println("requestId=" + call.providerRequestId());
        System.out.println("--- monthlyOverview ---");
        System.out.println(report.monthlyOverview());
        System.out.println("--- expenseStructure ---");
        System.out.println(report.expenseStructure());
        System.out.println("--- surplusReasons ---");
        System.out.println(report.surplusReasons());
        System.out.println("--- pointsToWatch ---");
        System.out.println(report.pointsToWatch());
        assertThat(call.provider()).isEqualTo("dashscope");
        assertThat(call.model()).contains("qwen3.7-flash").doesNotContain("plus");
        assertThat(report.monthlyOverview()).contains("净收入10000.00元", "净支出6600.00元", "结余3400.00元");
        assertThat(report.expenseStructure()).contains("房屋净额4800.00元", "购物净额1600.00元", "餐饮净额200.00元");
        assertThat(report.surplusReasons()).contains("结余为3400.00元", "净收入10000.00元", "净支出6600.00元");
        assertThat(report.pointsToWatch()).isNotBlank();
    }

    private static MonthlyAnalysisContext synthetic() {
        return new MonthlyAnalysisContext(1L, "2026-09", MonthlyAnalysisContext.TIME_ZONE, MonthlyAnalysisContext.CURRENCY,
                "10000.00", "6600.00", "3400.00", 1, 4,
                List.of(new CategoryFact(1L, "工资", 1, "10000.00", List.of())),
                List.of(new CategoryFact(2L, "房屋", 1, "4800.00", List.of(new CategoryFact(3L, "租房", 1, "4800.00", List.of()))),
                        new CategoryFact(4L, "购物", 1, "1600.00", List.of(new CategoryFact(5L, "电子产品", 1, "1600.00", List.of()))),
                        new CategoryFact(6L, "餐饮", 2, "200.00", List.of(new CategoryFact(7L, "午餐", 2, "200.00", List.of())))),
                new UncategorizedFact(0, "0.00"), new UncategorizedFact(0, "0.00"),
                List.of(new KeyExpenseFact("EXPENSE", "房屋 > 租房", "模拟房租", "4800.00", "2026-09-01T09:00:00+08:00"),
                        new KeyExpenseFact("EXPENSE", "购物 > 电子产品", "模拟显示器", "1600.00", "2026-09-12T15:00:00+08:00"),
                        new KeyExpenseFact("EXPENSE", "餐饮 > 午餐", "模拟午餐", "100.00", "2026-09-18T12:00:00+08:00"),
                        new KeyExpenseFact("EXPENSE", "餐饮 > 午餐", "模拟午餐", "100.00", "2026-09-19T12:00:00+08:00")));
    }
}
