package com.limingyuan.bookkeeping.analysis.application;

/** Monthly analysis generation. Application code depends on this port, not on a provider client. */
public interface MonthlyAnalysisGenerator {
    MonthlyAnalysisGeneration generate(MonthlyAnalysisModelInput input, String systemPrompt);
}
