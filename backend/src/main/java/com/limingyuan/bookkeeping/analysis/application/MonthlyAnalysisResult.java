package com.limingyuan.bookkeeping.analysis.application;

/** A completed generate call. Outcome is SUCCESS or REUSED. Failures leave this type. */
public record MonthlyAnalysisResult(String outcome, MonthlyAnalysisView analysis) { }
