package com.limingyuan.bookkeeping.analysis.application;

import com.limingyuan.bookkeeping.analysis.domain.LedgerAnalysisPeriod;
import com.limingyuan.bookkeeping.statistics.application.LedgerStatisticsService;
import org.springframework.stereotype.Service;

/** Whole-ledger facts for one natural month or year. Archived accounts stay in. Deleted accounts are already gone. */
@Service
public class LedgerAnalysisFactsService {
    private final LedgerStatisticsService statistics;

    public LedgerAnalysisFactsService(LedgerStatisticsService statistics) {
        this.statistics = statistics;
    }

    public LedgerAnalysisFacts build(String periodKind, String period) {
        LedgerAnalysisPeriod.Bounds bounds = LedgerAnalysisPeriod.parse(periodKind, period);
        var current = statistics.report("CUSTOM", bounds.from().toString(), bounds.to().toString(), "ALL", null);
        LedgerAnalysisPeriod.Bounds priorBounds = LedgerAnalysisPeriod.parse(
                bounds.periodKind(), bounds.priorPeriod());
        var prior = statistics.report("CUSTOM", priorBounds.from().toString(), priorBounds.to().toString(), "ALL", null);
        return LedgerAnalysisFacts.from(bounds, current, prior);
    }
}
