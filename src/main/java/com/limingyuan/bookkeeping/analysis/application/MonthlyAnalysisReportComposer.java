package com.limingyuan.bookkeeping.analysis.application;

import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext;

/** Composes the four sections from one context and one model response. It does not call a model. */
public final class MonthlyAnalysisReportComposer {
    private MonthlyAnalysisReportComposer() { }

    public static MonthlyAnalysisReport compose(MonthlyAnalysisContext context, String modelJson) {
        var interpretation = ModelOutputValidator.parse(modelJson);
        ModelOutputValidator.requireConsistentAmounts(interpretation, context.netIncome(), context.netExpense(),
                context.surplus());
        return new MonthlyAnalysisReport(DeterministicMonthlyReport.monthlyOverview(context),
                DeterministicMonthlyReport.expenseStructure(context),
                DeterministicMonthlyReport.surplusFact(context) + "\n" + interpretation.surplusReasons(),
                interpretation.pointsToWatch());
    }
}
