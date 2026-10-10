package com.limingyuan.bookkeeping.analysis.application;

/** Releasing a permit. A stale permit does not release another holder's reservation. */
public sealed interface FailureSettlement {
    record Applied() implements FailureSettlement { }

    record StalePermit() implements FailureSettlement { }
}
