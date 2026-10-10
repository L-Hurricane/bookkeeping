package com.limingyuan.bookkeeping.analysis.application;

/** Operational generation state. It is not a snapshot state. */
public enum GenerationState {
    IN_PROGRESS,
    FAILED,
    SUCCEEDED
}
