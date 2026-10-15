package com.limingyuan.bookkeeping.analysis.application;

/** Default admission. It does not look at provider settings or usernames. */
public final class OpenAnalysisAdmission implements AnalysisGenerationAdmission {
    @Override
    public void admit() { }
}
