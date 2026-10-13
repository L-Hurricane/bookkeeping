package com.limingyuan.bookkeeping.analysis.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Current two-field prompt. Version v1 stays on the classpath for traceability. Loading does not call a model. */
public final class MonthlyAnalysisPrompt {
    public static final String VERSION = "v2";

    private MonthlyAnalysisPrompt() { }

    public static String text() {
        try (var input = MonthlyAnalysisPrompt.class.getResourceAsStream("/analysis/monthly-analysis-prompt-v2.txt")) {
            if (input == null) throw new IllegalStateException("monthly analysis prompt v2 is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            throw new IllegalStateException("monthly analysis prompt v2 is unreadable", ex);
        }
    }
}
