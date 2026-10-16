package com.limingyuan.bookkeeping.analysis.application;

/** In-memory result of one technical run. It is not a snapshot and does not consume a quota. */
public record GeneratedMonthlyAnalysis(MonthlyAnalysisReport report, MonthlyAnalysisGeneration generation) { }
