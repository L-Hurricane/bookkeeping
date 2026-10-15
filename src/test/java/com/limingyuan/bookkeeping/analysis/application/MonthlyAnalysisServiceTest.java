package com.limingyuan.bookkeeping.analysis.application;

import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFacts;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFacts.UncategorizedSlice;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFactsReader;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        var service = service(store, reader);
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

    @Test void getPrefersAMatchingSnapshotAndDoesNotGenerate() {
        var source = facts();
        MonthlyAnalysisContext expected = new MonthlyAnalysisFactsService((accountId, yearMonth) -> source).build(7L, "2026-10");
        var store = new RecordingStore();
        store.matching = snapshot(3L, expected.fingerprint(), "匹配的较早分析");
        store.latest = snapshot(9L, "1:other", "较新的其他分析");
        var service = service(store, (accountId, yearMonth) -> facts());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new TrustedUserContext.Identity(4L, 0L), null, List.of()));
        var current = service.get(7L, "2026-10");
        assertThat(current.viewState()).isEqualTo("CURRENT");
        assertThat(current.snapshot().id()).isEqualTo(3L);
        assertThat(current.snapshot().stale()).isFalse();
        assertThat(current.snapshot().monthlyOverview()).isEqualTo("匹配的较早分析");
        assertThat(store.findLatestCalls).isZero();
        assertThat(store.acquireCalls).isZero();

        store.matching = null;
        var stale = service.get(7L, "2026-10");
        assertThat(stale.viewState()).isEqualTo("STALE");
        assertThat(stale.snapshot().id()).isEqualTo(9L);
        assertThat(stale.snapshot().stale()).isTrue();
        assertThat(stale.snapshot().monthlyOverview()).isEqualTo("较新的其他分析");
        assertThat(store.findLatestCalls).isEqualTo(1);
        assertThat(store.acquireCalls).isZero();
    }

    @Test void contradictoryLabeledAmountReleasesQuotaAndDoesNotRetry() {
        var source = new MonthlyBookkeepingFacts(7L, "2026-02", "50.00", "-20.00", "70.00", 1, 1, List.of(), List.of(),
                new UncategorizedSlice(0, "0.00"), new UncategorizedSlice(0, "0.00"), List.of());
        var context = new MonthlyAnalysisFactsService((accountId, yearMonth) -> source).build(7L, "2026-02");
        assertThat(context.netExpense()).isEqualTo("-20.00");
        assertThat(DeterministicMonthlyReport.monthlyOverview(context)).contains("净支出-20.00元", "结余70.00元");
        var calls = new int[] {0};
        String droppedSign = "{\"surplusReasons\":\"支出净额为负，结余高于净收入。\","
                + "\"pointsToWatch\":\"本月净支出20元。\"}";
        MonthlyAnalysisGenerator generator = (input, prompt) -> {
            if (++calls[0] > 1) throw new AssertionError("provider was called again");
            return new MonthlyAnalysisGeneration("dashscope", "qwen3.7-flash", droppedSign, 1L, 1L, 2L, 5L, "req");
        };
        var store = new OutcomeStore(context.fingerprint());
        var factsService = new MonthlyAnalysisFactsService((accountId, yearMonth) -> source);
        var service = new MonthlyAnalysisService(factsService, store,
                new MonthlyAnalysisReportGeneration(factsService, generator), new OpenAnalysisAdmission(),
                new TrustedUserContext(), Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"), ZoneOffset.UTC));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new TrustedUserContext.Identity(4L, 0L), null, List.of()));
        assertThatThrownBy(() -> service.generate(7L, "2026-02", context.fingerprint()))
                .isInstanceOfSatisfying(ApiFailure.class, failure -> {
                    assertThat(failure.status()).isEqualTo(502);
                    assertThat(failure.code()).isEqualTo("GENERATION_FAILED");
                });
        assertThat(calls[0]).isEqualTo(1);
        assertThat(store.successes).isZero();
        assertThat(store.failures).isEqualTo(1);
    }

    @Test void rejectedAdmissionDoesNotAcquireAPermit() {
        var source = facts();
        var context = new MonthlyAnalysisFactsService((accountId, yearMonth) -> source).build(7L, "2026-10");
        var store = new RecordingStore();
        var service = new MonthlyAnalysisService(new MonthlyAnalysisFactsService((accountId, yearMonth) -> source), store,
                new MonthlyAnalysisReportGeneration(new MonthlyAnalysisFactsService((accountId, yearMonth) -> source),
                        (input, prompt) -> { throw new AssertionError("model"); }),
                () -> { throw new ApiFailure(403, "OPERATION_FORBIDDEN", "当前本地模拟不接受此用户"); },
                new TrustedUserContext(), Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"), ZoneOffset.UTC));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new TrustedUserContext.Identity(4L, 0L), null, List.of()));
        assertThatThrownBy(() -> service.generate(7L, "2026-10", context.fingerprint()))
                .isInstanceOfSatisfying(ApiFailure.class, failure -> {
                    assertThat(failure.status()).isEqualTo(403);
                    assertThat(failure.code()).isEqualTo("OPERATION_FORBIDDEN");
                });
        assertThat(store.acquireCalls).isZero();
    }

    private static MonthlyAnalysisService service(RecordingStore store, MonthlyBookkeepingFactsReader reader) {
        return new MonthlyAnalysisService(new MonthlyAnalysisFactsService(reader), store,
                new MonthlyAnalysisReportGeneration(new MonthlyAnalysisFactsService(reader), (input, prompt) -> {
                    throw new AssertionError("model");
                }), new OpenAnalysisAdmission(), new TrustedUserContext(),
                Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"), ZoneOffset.UTC));
    }

    private static AnalysisSnapshot snapshot(long id, String fingerprint, String overview) {
        return new AnalysisSnapshot(id, 4L, "2026-10", fingerprint,
                new MonthlyAnalysisReport(overview, "支出", "结余", "关注"),
                new AnalysisProvenance("dashscope", "qwen3.7-flash", "v1", null, null, null, 1L, null),
                java.time.LocalDate.parse("2026-10-01"), Instant.parse("2026-10-01T00:00:00Z"));
    }

    private static MonthlyBookkeepingFacts facts() {
        return new MonthlyBookkeepingFacts(7L, "2026-10", "3.00", "1.50", "1.50", 1, 1, List.of(), List.of(),
                new UncategorizedSlice(0, "0.00"), new UncategorizedSlice(0, "0.00"), List.of());
    }

    private static final class RecordingStore implements AnalysisGenerationStore {
        private int acquireCalls;
        private int findLatestCalls;
        private AnalysisSnapshot matching;
        private AnalysisSnapshot latest;

        @Override public Optional<AnalysisSnapshot> findSuccessful(long userId, String yearMonth, String contextFingerprint) {
            return matching != null && matching.contextFingerprint().equals(contextFingerprint) ? Optional.of(matching) : Optional.empty();
        }
        @Override public Optional<AnalysisSnapshot> findLatest(long userId, String yearMonth) {
            findLatestCalls++;
            return Optional.ofNullable(latest);
        }
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

    private static final class OutcomeStore implements AnalysisGenerationStore {
        private final String fingerprint;
        private int successes;
        private int failures;

        private OutcomeStore(String fingerprint) { this.fingerprint = fingerprint; }

        @Override public Optional<AnalysisSnapshot> findSuccessful(long userId, String yearMonth, String contextFingerprint) {
            return Optional.empty();
        }
        @Override public Optional<AnalysisSnapshot> findLatest(long userId, String yearMonth) { return Optional.empty(); }
        @Override public Optional<GenerationStatus> findCoordination(long userId, String yearMonth, String contextFingerprint) {
            return Optional.empty();
        }
        @Override public PermitAcquisition acquire(long userId, String yearMonth, String contextFingerprint) {
            return new PermitAcquisition.Granted(new GenerationPermit(1L, 1L, userId, yearMonth, fingerprint,
                    LocalDate.parse("2026-10-13"), Instant.parse("2026-10-13T04:00:00Z")));
        }
        @Override public SuccessSettlement completeSuccess(GenerationPermit permit, MonthlyAnalysisReport report,
                AnalysisProvenance provenance) {
            successes++;
            throw new AssertionError("success snapshot");
        }
        @Override public FailureSettlement completeFailure(GenerationPermit permit) {
            failures++;
            return new FailureSettlement.Applied();
        }
    }
}
