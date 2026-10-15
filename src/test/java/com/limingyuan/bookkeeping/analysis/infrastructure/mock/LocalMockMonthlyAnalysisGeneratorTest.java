package com.limingyuan.bookkeeping.analysis.infrastructure.mock;

import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationFailed;
import com.limingyuan.bookkeeping.analysis.application.ModelOutputValidator;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisModelInput;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisPrompt;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalMockMonthlyAnalysisGeneratorTest {
    @Test void returnsTheFixedContractWithoutAProviderIdentity() {
        var generation = new LocalMockMonthlyAnalysisGenerator(false).generate(sample(), MonthlyAnalysisPrompt.text());
        assertThat(generation.provider()).isEqualTo("local-mock");
        assertThat(generation.model()).isEqualTo("deterministic-acceptance");
        assertThat(generation.providerRequestId()).isNull();
        assertThat(generation.content()).doesNotContain("dashscope", "qwen");
        var parsed = ModelOutputValidator.parse(generation.content());
        assertThat(parsed.surplusReasons()).isEqualTo(LocalMockMonthlyAnalysisGenerator.SURPLUS);
        assertThat(parsed.pointsToWatch()).isEqualTo(LocalMockMonthlyAnalysisGenerator.POINTS);
        assertThat(parsed.pointsToWatch()).doesNotContain("元");
    }

    @Test void failureModeThrowsBeforeReturningContent() {
        var generator = new LocalMockMonthlyAnalysisGenerator(true);
        assertThatThrownBy(() -> generator.generate(sample(), MonthlyAnalysisPrompt.text()))
                .isInstanceOfSatisfying(AnalysisGenerationFailed.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(AnalysisGenerationFailed.Kind.PROVIDER_ERROR);
                    assertThat(failure.httpStatus()).isNull();
                });
    }

    private static MonthlyAnalysisModelInput sample() {
        return new MonthlyAnalysisModelInput("2026-03", "+08:00", "CNY", MonthlyAnalysisModelInput.UNIT,
                new MonthlyAnalysisModelInput.MonthlySummary("50.00", "20.00", "30.00", 1, 1),
                java.util.List.of(), java.util.List.of(),
                new MonthlyAnalysisModelInput.UncategorizedInput(
                        new MonthlyAnalysisModelInput.UncategorizedSide("0.00", 0),
                        new MonthlyAnalysisModelInput.UncategorizedSide("0.00", 0)),
                java.util.List.of());
    }
}
