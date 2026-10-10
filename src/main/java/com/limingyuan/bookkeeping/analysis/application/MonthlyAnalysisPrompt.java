package com.limingyuan.bookkeeping.analysis.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Initial two-field prompt. Loading it does not call a model. */
public final class MonthlyAnalysisPrompt {
    public static final String VERSION = "v1";

    private MonthlyAnalysisPrompt() { }

    public static String text() {
        try (var input = MonthlyAnalysisPrompt.class.getResourceAsStream("/analysis/monthly-analysis-prompt-v1.txt")) {
            if (input == null) throw new IllegalStateException("monthly analysis prompt v1 is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            throw new IllegalStateException("monthly analysis prompt v1 is unreadable", ex);
        }
    }
}
