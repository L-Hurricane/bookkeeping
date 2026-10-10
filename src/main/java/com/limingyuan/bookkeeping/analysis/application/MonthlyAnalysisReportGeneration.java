package com.limingyuan.bookkeeping.analysis.application;

import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext;
import org.springframework.stereotype.Service;

/** Facts, model input, prompt, generator, validator, and four-section composition. No snapshot or quota. */
@Service
public class MonthlyAnalysisReportGeneration {
    private final MonthlyAnalysisFactsService facts;
    private final MonthlyAnalysisGenerator generator;

    public MonthlyAnalysisReportGeneration(MonthlyAnalysisFactsService facts, MonthlyAnalysisGenerator generator) {
        this.facts = facts;
        this.generator = generator;
    }

    public GeneratedMonthlyAnalysis generate(long accountId, String yearMonth) {
        return generate(facts.build(accountId, yearMonth));
    }

    public GeneratedMonthlyAnalysis generate(MonthlyAnalysisContext context) {
        var input = MonthlyAnalysisModelInput.from(context);
        var generation = generator.generate(input, MonthlyAnalysisPrompt.text());
        return new GeneratedMonthlyAnalysis(MonthlyAnalysisReportComposer.compose(context, generation.content()), generation);
    }
}
