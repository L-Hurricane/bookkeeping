package com.limingyuan.bookkeeping.analysis.application;

/** Final four sections. Ready for a later snapshot, without persisting one. */
public record MonthlyAnalysisReport(
        String monthlyOverview,
        String expenseStructure,
        String surplusReasons,
        String pointsToWatch) { }
