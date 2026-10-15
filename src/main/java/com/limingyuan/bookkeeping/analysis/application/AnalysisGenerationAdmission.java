package com.limingyuan.bookkeeping.analysis.application;

/** Whether the current user may start a new generation. Stored-snapshot reuse is decided before this runs. */
public interface AnalysisGenerationAdmission {
    void admit();
}
