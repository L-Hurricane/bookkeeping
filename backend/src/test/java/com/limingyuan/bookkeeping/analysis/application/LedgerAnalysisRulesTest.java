package com.limingyuan.bookkeeping.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.limingyuan.bookkeeping.account.application.AccountPermanentDeletionAnalysisCounter;
import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationStore;
import com.limingyuan.bookkeeping.analysis.application.AnalysisProvenance;
import com.limingyuan.bookkeeping.analysis.application.InMemoryAnalysisGenerationStore;
import com.limingyuan.bookkeeping.analysis.application.LedgerAnalysisFacts;
import com.limingyuan.bookkeeping.analysis.application.LedgerPermitAcquisition;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisReport;
import com.limingyuan.bookkeeping.analysis.application.PermitAcquisition;
import com.limingyuan.bookkeeping.analysis.domain.AnalysisReportKey;
import com.limingyuan.bookkeeping.analysis.domain.LedgerAnalysisPeriod;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LedgerAnalysisRulesTest {
    @Test
    void naturalMonthAndYearRejectOtherRanges() {
        var month = LedgerAnalysisPeriod.parse("MONTH", "2026-10");
        assertThat(month.from()).hasToString("2026-10-01");
        assertThat(month.to()).hasToString("2026-10-31");
        assertThat(month.priorPeriod()).isEqualTo("2026-09");
        var year = LedgerAnalysisPeriod.parse("YEAR", "2025");
        assertThat(year.from()).hasToString("2025-01-01");
        assertThat(year.to()).hasToString("2025-12-31");
        assertThat(year.priorPeriod()).isEqualTo("2024");
        assertThatThrownBy(() -> LedgerAnalysisPeriod.parse("QUARTER", "2026-10")).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(() -> LedgerAnalysisPeriod.parse("YEAR", "2026-10")).isInstanceOf(ApiFailure.class);
        assertThatThrownBy(() -> LedgerAnalysisPeriod.parse("MONTH", "2026")).isInstanceOf(ApiFailure.class);
    }

    @Test
    void nullAccountIsNotALedgerReport() {
        assertThat(AnalysisReportKey.nullAccountMeansLedger()).isFalse();
        assertThat(AnalysisReportKey.ledger("MONTH", "2026-10").userVisibleLedger()).isTrue();
        assertThat(AnalysisReportKey.legacyMonth("2026-10").userVisibleLedger()).isFalse();
        assertThat(AnalysisReportKey.legacyMonth("2026-10").accountId()).isNull();
    }

    @Test
    void modelPayloadKeepsAggregatesAndOmitsNotes() {
        var facts = new LedgerAnalysisFacts("MONTH", "2026-10", "10.00", "12.00", "2.00", "5.00", "3.00", 2, 1,
                "2026-09", "1.00", "4.00", "3.00", "[{\"name\":\"早餐\"}]", "[]", "[]", "[]");
        assertThat(facts.json()).contains("早餐").doesNotContain("备注").doesNotContain("transactionName");
        assertThat(facts.fingerprint()).startsWith("ledger-1:");
        assertThat(facts.overview()).contains("期初10.00元").contains("期末12.00元");
        assertThat(facts.comparison()).contains("2026-09");
    }

    @Test
    void dailyQuotaIsSharedAndLegacyRowsStayHidden() {
        var store = new InMemoryAnalysisGenerationStore(Clock.fixed(Instant.parse("2026-10-26T02:00:00Z"), ZoneOffset.UTC));
        for (int i = 0; i < AnalysisGenerationStore.DAILY_SUCCESS_LIMIT; i++) {
            assertThat(store.acquire(1L, 10L, "2026-10", "account-" + i)).isInstanceOf(PermitAcquisition.Granted.class);
        }
        assertThat(store.acquireLedger(1L, "MONTH", "2026-10", "ledger")).isInstanceOf(LedgerPermitAcquisition.QuotaExceeded.class);
        var otherDay = new InMemoryAnalysisGenerationStore(Clock.fixed(Instant.parse("2026-10-26T02:00:00Z"), ZoneOffset.UTC));
        var granted = (LedgerPermitAcquisition.Granted) otherDay.acquireLedger(2L, "YEAR", "2025", "year");
        otherDay.completeLedgerSuccess(granted.permit(), new MonthlyAnalysisReport("概况", "结构", "对比", "关注"),
                new AnalysisProvenance("local-mock", "deterministic-acceptance", "ledger-v1", null, null, null, 0L, null));
        assertThat(otherDay.findSuccessful(2L, 10L, "2025", "year")).isEmpty();
        assertThat(otherDay.findSuccessfulLedger(2L, "YEAR", "2025", "year")).isPresent();
    }

    @Test
    void ledgerDeletionSqlNamesTheScope() {
        assertThat(AccountPermanentDeletionAnalysisCounter.DELETE_LEDGER_SNAPSHOTS_SQL).contains("report_scope = 'LEDGER'");
        assertThat(AccountPermanentDeletionAnalysisCounter.DELETE_LEDGER_SNAPSHOTS_SQL).contains("account_id IS NULL");
        assertThat(AccountPermanentDeletionAnalysisCounter.DELETE_LEDGER_GENERATIONS_SQL).contains("report_scope = 'LEDGER'");
    }
}
