package com.limingyuan.bookkeeping.analysis.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Whole-ledger prompt. Loading it does not call a model. */
public final class LedgerAnalysisPrompt {
    public static final String VERSION = "ledger-v1";

    private LedgerAnalysisPrompt() { }

    public static String text() {
        try (var input = LedgerAnalysisPrompt.class.getResourceAsStream("/analysis/ledger-analysis-prompt-v1.txt")) {
            if (input == null) throw new IllegalStateException("ledger analysis prompt is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException ex) {
            throw new IllegalStateException("ledger analysis prompt is unreadable", ex);
        }
    }
}
