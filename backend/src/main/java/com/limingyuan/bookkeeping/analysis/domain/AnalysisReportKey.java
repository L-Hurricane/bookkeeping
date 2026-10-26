package com.limingyuan.bookkeeping.analysis.domain;

/**
 * Report identity. A null account id is not a ledger report unless {@code reportScope} says LEDGER.
 * LEGACY rows stay a separate history and are not readable as ledger reports.
 */
public record AnalysisReportKey(String reportScope, String periodKind, Long accountId, String period) {
    public static AnalysisReportKey ledger(String periodKind, String period) {
        LedgerAnalysisPeriod.Bounds bounds = LedgerAnalysisPeriod.parse(periodKind, period);
        return new AnalysisReportKey("LEDGER", bounds.periodKind(), null, bounds.period());
    }

    public static AnalysisReportKey legacyMonth(String yearMonth) {
        LedgerAnalysisPeriod.Bounds bounds = LedgerAnalysisPeriod.parse("MONTH", yearMonth);
        return new AnalysisReportKey("LEGACY", "MONTH", null, bounds.period());
    }

    /** Null account id never implies a ledger report by itself. */
    public static boolean nullAccountMeansLedger() {
        return false;
    }

    public boolean userVisibleLedger() {
        return "LEDGER".equals(reportScope) && accountId == null
                && ("MONTH".equals(periodKind) || "YEAR".equals(periodKind));
    }
}
