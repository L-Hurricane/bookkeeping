package com.limingyuan.bookkeeping.analysis.application;

/** Finalizing a permit. A stale permit does not write a snapshot or consume quota. */
public sealed interface SuccessSettlement {
    record Applied(AnalysisSnapshot snapshot) implements SuccessSettlement { }

    record StalePermit() implements SuccessSettlement { }
}
