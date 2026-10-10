package com.limingyuan.bookkeeping.analysis.application;

import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFacts;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFacts.UncategorizedSlice;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFactsReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.assertThat;

class MonthlyAnalysisServiceTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void getUsesOneFactsReadAndDoesNotGenerate() {
        var source = facts();
        MonthlyAnalysisContext expected = new MonthlyAnalysisFactsService((accountId, yearMonth) -> source).build(7L, "2026-10");
        var reads = new int[] {0};
        MonthlyBookkeepingFactsReader reader = (accountId, yearMonth) -> {
            if (++reads[0] > 1) throw new IllegalStateException("facts were read twice");
            return source;
        };
        var store = new RecordingStore();
        var service = new MonthlyAnalysisService(new MonthlyAnalysisFactsService(reader), store,
                new MonthlyAnalysisReportGeneration(new MonthlyAnalysisFactsService(reader), (input, prompt) -> {
                    throw new AssertionError("model");
                }), new TrustedUserContext(), Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"), ZoneOffset.UTC));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new TrustedUserContext.Identity(4L, 0L), null, List.of()));
        var view = service.get(7L, "2026-10");
        assertThat(reads[0]).isEqualTo(1);
        assertThat(view.fingerprint()).isEqualTo(expected.fingerprint());
        assertThat(view.facts().netIncome()).isEqualTo("3.00");
        assertThat(view.facts().netExpense()).isEqualTo("1.50");
        assertThat(view.facts().incomeTransactionCount()).isEqualTo(1);
        assertThat(view.viewState()).isEqualTo("NO_ANALYSIS");
        assertThat(view.snapshot()).isNull();
        assertThat(store.acquireCalls).isZero();
    }

    private static MonthlyBookkeepingFacts facts() {
        return new MonthlyBookkeepingFacts(7L, "2026-10", "3.00", "1.50", "1.50", 1, 1, List.of(), List.of(),
                new UncategorizedSlice(0, "0.00"), new UncategorizedSlice(0, "0.00"), List.of());
    }

    private static final class RecordingStore implements AnalysisGenerationStore {
        private int acquireCalls;

        @Override public Optional<AnalysisSnapshot> findSuccessful(long userId, String yearMonth, String contextFingerprint) {
            return Optional.empty();
        }
        @Override public Optional<AnalysisSnapshot> findLatest(long userId, String yearMonth) { return Optional.empty(); }
        @Override public Optional<GenerationStatus> findCoordination(long userId, String yearMonth, String contextFingerprint) {
            return Optional.empty();
        }
        @Override public PermitAcquisition acquire(long userId, String yearMonth, String contextFingerprint) {
            acquireCalls++;
            return new PermitAcquisition.QuotaExceeded();
        }
        @Override public SuccessSettlement completeSuccess(GenerationPermit permit, MonthlyAnalysisReport report, AnalysisProvenance provenance) {
            throw new AssertionError("success");
        }
        @Override public FailureSettlement completeFailure(GenerationPermit permit) { throw new AssertionError("failure"); }
    }
}
