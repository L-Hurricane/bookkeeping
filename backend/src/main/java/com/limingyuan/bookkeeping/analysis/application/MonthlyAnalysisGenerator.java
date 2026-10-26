package com.limingyuan.bookkeeping.analysis.application;

/** Monthly analysis generation. Application code depends on this port, not on a provider client. */
public interface MonthlyAnalysisGenerator {
    MonthlyAnalysisGeneration generate(MonthlyAnalysisModelInput input, String systemPrompt);

    /** User JSON is an already built aggregate payload. It must not be a raw ledger export. */
    default MonthlyAnalysisGeneration generateJson(String userJson, String systemPrompt) {
        throw new UnsupportedOperationException("ledger payload is not supported");
    }
}
