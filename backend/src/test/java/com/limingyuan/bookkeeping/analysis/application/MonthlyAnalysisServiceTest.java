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
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonthlyAnalysisServiceTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void getUsesOneFactsReadAndDoesNotGenerate() {
        var source = facts(7L);
        MonthlyAnalysisContext expected = new MonthlyAnalysisFactsService((accountId, yearMonth) -> source).build(7L, "2026-10");
        var reads = new int[] {0};
        MonthlyBookkeepingFactsReader reader = (accountId, yearMonth) -> {
            if (++reads[0] > 1) throw new IllegalStateException("facts were read twice");
            return source;
        };
        var store = new RecordingStore();
        var service = service(store, reader);
        authenticate(4L);
        var view = service.get(7L, "2026-10");
        assertThat(reads[0]).isEqualTo(1);
        assertThat(view.fingerprint()).isEqualTo(expected.fingerprint());
        assertThat(view.facts().netIncome()).isEqualTo("3.00");
        assertThat(view.facts().netExpense()).isEqualTo("1.50");
        assertThat(view.facts().incomeTransactionCount()).isEqualTo(1);
        assertThat(view.viewState()).isEqualTo("NO_ANALYSIS");
        assertThat(view.snapshot()).isNull();
        assertThat(store.acquireCalls).isZero();
        assertThat(store.lastAccountId).isEqualTo(7L);
    }

    @Test void getPrefersAMatchingSnapshotAndDoesNotGenerate() {
        var source = facts(7L);
        MonthlyAnalysisContext expected = new MonthlyAnalysisFactsService((accountId, yearMonth) -> source).build(7L, "2026-10");
        var store = new RecordingStore();
        store.matching = snapshot(3L, 7L, expected.fingerprint(), "匹配的较早分析");
        store.latest = snapshot(9L, 7L, "1:other", "较新的其他分析");
        var service = service(store, (accountId, yearMonth) -> facts(7L));
        authenticate(4L);
        var current = service.get(7L, "2026-10");
        assertThat(current.viewState()).isEqualTo("CURRENT");
        assertThat(current.snapshot().id()).isEqualTo(3L);
        assertThat(current.snapshot().stale()).isFalse();
        assertThat(current.snapshot().monthlyOverview()).isEqualTo("匹配的较早分析");
        assertThat(store.findLatestCalls).isZero();
        assertThat(store.acquireCalls).isZero();
        assertThat(store.lastAccountId).isEqualTo(7L);

        store.matching = null;
        var stale = service.get(7L, "2026-10");
        assertThat(stale.viewState()).isEqualTo("STALE");
        assertThat(stale.snapshot().id()).isEqualTo(9L);
        assertThat(stale.snapshot().stale()).isTrue();
        assertThat(stale.snapshot().monthlyOverview()).isEqualTo("较新的其他分析");
        assertThat(store.findLatestCalls).isEqualTo(1);
        assertThat(store.acquireCalls).isZero();
        assertThat(store.lastAccountId).isEqualTo(7L);
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
        authenticate(4L);
        assertThatThrownBy(() -> service.generate(7L, "2026-02", context.fingerprint()))
                .isInstanceOfSatisfying(ApiFailure.class, failure -> {
                    assertThat(failure.status()).isEqualTo(502);
                    assertThat(failure.code()).isEqualTo("GENERATION_FAILED");
                });
        assertThat(calls[0]).isEqualTo(1);
        assertThat(store.successes).isZero();
        assertThat(store.failures).isEqualTo(1);
        assertThat(store.lastAccountId).isEqualTo(7L);
    }

    @Test void rejectedAdmissionDoesNotAcquireAPermit() {
        var source = facts(7L);
        var context = new MonthlyAnalysisFactsService((accountId, yearMonth) -> source).build(7L, "2026-10");
        var store = new RecordingStore();
        var service = new MonthlyAnalysisService(new MonthlyAnalysisFactsService((accountId, yearMonth) -> source), store,
                new MonthlyAnalysisReportGeneration(new MonthlyAnalysisFactsService((accountId, yearMonth) -> source),
                        (input, prompt) -> { throw new AssertionError("model"); }),
                () -> { throw new ApiFailure(403, "OPERATION_FORBIDDEN", "当前本地模拟不接受此用户"); },
                new TrustedUserContext(), Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"), ZoneOffset.UTC));
        authenticate(4L);
        assertThatThrownBy(() -> service.generate(7L, "2026-10", context.fingerprint()))
                .isInstanceOfSatisfying(ApiFailure.class, failure -> {
                    assertThat(failure.status()).isEqualTo(403);
                    assertThat(failure.code()).isEqualTo("OPERATION_FORBIDDEN");
                });
        assertThat(store.acquireCalls).isZero();
    }

    @Test void sameFingerprintAcrossAccountsNeverReusesOrSharesInProgress() {
        var clock = Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"), ZoneOffset.UTC);
        var store = new InMemoryAnalysisGenerationStore(clock);
        var reader = sameFactsReader(11L, 22L);
        var service = generatingService(store, reader, clock, "账户A报告");
        authenticate(4L);

        String fingerprint = service.get(11L, "2026-10").fingerprint();
        assertThat(service.get(22L, "2026-10").fingerprint()).isEqualTo(fingerprint);

        var generatedA = service.generate(11L, "2026-10", fingerprint);
        assertThat(generatedA.outcome()).isEqualTo("SUCCESS");
        assertThat(generatedA.analysis().viewState()).isEqualTo("CURRENT");
        assertThat(generatedA.analysis().snapshot().monthlyOverview()).isEqualTo("账户A报告");

        var reusedOnA = service.generate(11L, "2026-10", fingerprint);
        assertThat(reusedOnA.outcome()).isEqualTo("REUSED");
        assertThat(reusedOnA.analysis().snapshot().id()).isEqualTo(generatedA.analysis().snapshot().id());
        assertThat(store.acquire(4L, 11L, "2026-10", fingerprint)).isInstanceOf(PermitAcquisition.Reused.class);

        var onB = service.generate(22L, "2026-10", fingerprint);
        assertThat(onB.outcome()).isEqualTo("SUCCESS");
        assertThat(onB.analysis().snapshot().id()).isNotEqualTo(generatedA.analysis().snapshot().id());
        assertThat(onB.analysis().snapshot().monthlyOverview()).isEqualTo("账户A报告");

        var inProgressStore = new InMemoryAnalysisGenerationStore(clock);
        assertThat(inProgressStore.acquire(4L, 11L, "2026-10", fingerprint)).isInstanceOf(PermitAcquisition.Granted.class);
        assertThat(inProgressStore.acquire(4L, 11L, "2026-10", fingerprint)).isInstanceOf(PermitAcquisition.Busy.class);
        assertThat(inProgressStore.acquire(4L, 22L, "2026-10", fingerprint)).isInstanceOf(PermitAcquisition.Granted.class);
        assertThat(inProgressStore.acquire(4L, 22L, "2026-10", fingerprint)).isInstanceOf(PermitAcquisition.Busy.class);
    }

    @Test void differentFactsAcrossAccountsNeverShareStaleViaFindLatest() {
        var clock = Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"), ZoneOffset.UTC);
        var store = new InMemoryAnalysisGenerationStore(clock);
        var service = generatingService(store, differentFactsReader(11L, 22L), clock, "仅账户A");
        authenticate(4L);

        String fingerprintA = service.get(11L, "2026-10").fingerprint();
        String fingerprintB = service.get(22L, "2026-10").fingerprint();
        assertThat(fingerprintA).isNotEqualTo(fingerprintB);

        var generatedA = service.generate(11L, "2026-10", fingerprintA);
        assertThat(generatedA.outcome()).isEqualTo("SUCCESS");

        var viewB = service.get(22L, "2026-10");
        assertThat(viewB.viewState()).isEqualTo("NO_ANALYSIS");
        assertThat(viewB.snapshot()).isNull();
        assertThat(store.findLatest(4L, 22L, "2026-10")).isEmpty();
        assertThat(store.findLatest(4L, 11L, "2026-10").orElseThrow().id())
                .isEqualTo(generatedA.analysis().snapshot().id());
    }

    @Test void sameAccountCurrentStaleRepeatAndCoordinationStayCorrect() {
        var clock = Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"), ZoneOffset.UTC);
        var factsByFingerprint = new ConcurrentHashMap<Long, MonthlyBookkeepingFacts>();
        factsByFingerprint.put(7L, facts(7L, "3.00", "1.50"));
        MonthlyBookkeepingFactsReader reader = (accountId, yearMonth) -> factsByFingerprint.get(accountId);
        var store = new InMemoryAnalysisGenerationStore(clock);
        var service = generatingService(store, reader, clock, "同账户报告");
        authenticate(4L);

        String fingerprint = service.get(7L, "2026-10").fingerprint();
        var first = service.generate(7L, "2026-10", fingerprint);
        assertThat(first.outcome()).isEqualTo("SUCCESS");
        assertThat(service.get(7L, "2026-10").viewState()).isEqualTo("CURRENT");
        assertThat(service.generate(7L, "2026-10", fingerprint).outcome()).isEqualTo("REUSED");

        factsByFingerprint.put(7L, facts(7L, "9.00", "1.50"));
        var stale = service.get(7L, "2026-10");
        assertThat(stale.viewState()).isEqualTo("STALE");
        assertThat(stale.snapshot().monthlyOverview()).isEqualTo("同账户报告");
        assertThat(stale.fingerprint()).isNotEqualTo(fingerprint);

        assertThat(store.acquire(4L, 7L, "2026-10", stale.fingerprint())).isInstanceOf(PermitAcquisition.Granted.class);
        assertThat(store.acquire(4L, 7L, "2026-10", stale.fingerprint())).isInstanceOf(PermitAcquisition.Busy.class);
        assertThat(service.get(7L, "2026-10").viewState()).isEqualTo("GENERATION_IN_PROGRESS");
    }

    @Test void accountOwnershipIsolationKeepsForeignUserEmpty() {
        var clock = Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"), ZoneOffset.UTC);
        var store = new InMemoryAnalysisGenerationStore(clock);
        var ownerService = generatingService(store, (accountId, yearMonth) -> facts(accountId), clock, "属主报告");
        authenticate(4L);
        String fingerprint = ownerService.get(7L, "2026-10").fingerprint();
        assertThat(ownerService.generate(7L, "2026-10", fingerprint).outcome()).isEqualTo("SUCCESS");

        authenticate(99L);
        var foreign = service(store, (accountId, yearMonth) -> facts(accountId));
        assertThat(foreign.get(7L, "2026-10").viewState()).isEqualTo("NO_ANALYSIS");
        assertThat(foreign.get(7L, "2026-10").snapshot()).isNull();
        assertThat(store.findSuccessful(99L, 7L, "2026-10", fingerprint)).isEmpty();
        assertThat(store.findLatest(99L, 7L, "2026-10")).isEmpty();
    }

    @Test void legacyUnscopedRowsStayRetainedButInvisibleToAccountLookups() {
        var clock = Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"), ZoneOffset.UTC);
        var store = new InMemoryAnalysisGenerationStore(clock);
        var legacy = new AnalysisSnapshot(50L, 4L, 0L, "2026-10", "1:legacy",
                new MonthlyAnalysisReport("旧报告", "支出", "结余", "关注"),
                new AnalysisProvenance("dashscope", "qwen3.7-flash", "v1", null, null, null, 1L, null),
                LocalDate.parse("2026-10-01"), Instant.parse("2026-10-01T00:00:00Z"));
        store.retainLegacyUnscoped(legacy);
        assertThat(store.retainedLegacyCount()).isEqualTo(1);
        assertThat(store.findSuccessful(4L, 7L, "2026-10", "1:legacy")).isEmpty();
        assertThat(store.findLatest(4L, 7L, "2026-10")).isEmpty();

        authenticate(4L);
        var view = service(store, (accountId, yearMonth) -> facts(7L)).get(7L, "2026-10");
        assertThat(view.viewState()).isEqualTo("NO_ANALYSIS");
        assertThat(view.snapshot()).isNull();
        assertThat(store.retainedLegacyCount()).isEqualTo(1);
    }

    private static MonthlyAnalysisService service(AnalysisGenerationStore store, MonthlyBookkeepingFactsReader reader) {
        return new MonthlyAnalysisService(new MonthlyAnalysisFactsService(reader), store,
                new MonthlyAnalysisReportGeneration(new MonthlyAnalysisFactsService(reader), (input, prompt) -> {
                    throw new AssertionError("model");
                }), new OpenAnalysisAdmission(), new TrustedUserContext(),
                Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"), ZoneOffset.UTC));
    }

    private static MonthlyAnalysisService generatingService(AnalysisGenerationStore store,
            MonthlyBookkeepingFactsReader reader, Clock clock, String overview) {
        var factsService = new MonthlyAnalysisFactsService(reader);
        String body = "{\"surplusReasons\":\"结余来自账本\",\"pointsToWatch\":\"留意本月支出\"}";
        MonthlyAnalysisGenerator generator = (input, prompt) ->
                new MonthlyAnalysisGeneration("dashscope", "qwen3.7-flash", body, 1L, 1L, 2L, 5L, "req");
        var reports = new MonthlyAnalysisReportGeneration(factsService, generator) {
            @Override
            public GeneratedMonthlyAnalysis generate(MonthlyAnalysisContext context) {
                var generated = super.generate(context);
                return new GeneratedMonthlyAnalysis(
                        new MonthlyAnalysisReport(overview, generated.report().expenseStructure(),
                                generated.report().surplusReasons(), generated.report().pointsToWatch()),
                        generated.generation());
            }
        };
        return new MonthlyAnalysisService(factsService, store, reports, new OpenAnalysisAdmission(),
                new TrustedUserContext(), clock);
    }

    private static void authenticate(long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new TrustedUserContext.Identity(userId, 0L), null, List.of()));
    }

    private static AnalysisSnapshot snapshot(long id, long accountId, String fingerprint, String overview) {
        return new AnalysisSnapshot(id, 4L, accountId, "2026-10", fingerprint,
                new MonthlyAnalysisReport(overview, "支出", "结余", "关注"),
                new AnalysisProvenance("dashscope", "qwen3.7-flash", "v1", null, null, null, 1L, null),
                LocalDate.parse("2026-10-01"), Instant.parse("2026-10-01T00:00:00Z"));
    }

    private static MonthlyBookkeepingFacts facts(long accountId) {
        return facts(accountId, "3.00", "1.50");
    }

    private static MonthlyBookkeepingFacts facts(long accountId, String income, String expense) {
        String surplus = new java.math.BigDecimal(income).subtract(new java.math.BigDecimal(expense))
                .setScale(2).toPlainString();
        return new MonthlyBookkeepingFacts(accountId, "2026-10", income, expense, surplus, 1, 1, List.of(), List.of(),
                new UncategorizedSlice(0, "0.00"), new UncategorizedSlice(0, "0.00"), List.of());
    }

    private static MonthlyBookkeepingFactsReader sameFactsReader(long accountA, long accountB) {
        var shared = facts(accountA, "3.00", "1.50");
        return (accountId, yearMonth) -> {
            if (accountId == accountA || accountId == accountB) {
                return new MonthlyBookkeepingFacts(accountId, shared.yearMonth(), shared.netIncome(), shared.netExpense(),
                        shared.surplus(), shared.incomeTransactionCount(), shared.expenseTransactionCount(),
                        shared.incomeCategories(), shared.expenseCategories(), shared.uncategorizedIncome(),
                        shared.uncategorizedExpense(), shared.keyExpenses());
            }
            throw new AssertionError("unexpected account " + accountId);
        };
    }

    private static MonthlyBookkeepingFactsReader differentFactsReader(long accountA, long accountB) {
        return (accountId, yearMonth) -> {
            if (accountId == accountA) return facts(accountA, "3.00", "1.50");
            if (accountId == accountB) return facts(accountB, "8.00", "2.00");
            throw new AssertionError("unexpected account " + accountId);
        };
    }

    private static final class RecordingStore implements AnalysisGenerationStore {
        private int acquireCalls;
        private int findLatestCalls;
        private long lastAccountId;
        private AnalysisSnapshot matching;
        private AnalysisSnapshot latest;

        @Override public Optional<AnalysisSnapshot> findSuccessful(long userId, long accountId, String yearMonth,
                String contextFingerprint) {
            lastAccountId = accountId;
            return matching != null && matching.contextFingerprint().equals(contextFingerprint)
                    && matching.accountId() == accountId ? Optional.of(matching) : Optional.empty();
        }
        @Override public Optional<AnalysisSnapshot> findLatest(long userId, long accountId, String yearMonth) {
            lastAccountId = accountId;
            findLatestCalls++;
            return latest != null && latest.accountId() == accountId ? Optional.of(latest) : Optional.empty();
        }
        @Override public Optional<GenerationStatus> findCoordination(long userId, long accountId, String yearMonth,
                String contextFingerprint) {
            lastAccountId = accountId;
            return Optional.empty();
        }
        @Override public PermitAcquisition acquire(long userId, long accountId, String yearMonth, String contextFingerprint) {
            lastAccountId = accountId;
            acquireCalls++;
            return new PermitAcquisition.QuotaExceeded();
        }
        @Override public SuccessSettlement completeSuccess(GenerationPermit permit, MonthlyAnalysisReport report,
                AnalysisProvenance provenance) {
            throw new AssertionError("success");
        }
        @Override public FailureSettlement completeFailure(GenerationPermit permit) { throw new AssertionError("failure"); }
        @Override public Optional<AnalysisSnapshot> findSuccessfulLedger(long userId, String periodKind, String period, String contextFingerprint) { return Optional.empty(); }
        @Override public Optional<AnalysisSnapshot> findLatestLedger(long userId, String periodKind, String period) { return Optional.empty(); }
        @Override public Optional<GenerationStatus> findLedgerCoordination(long userId, String periodKind, String period, String contextFingerprint) { return Optional.empty(); }
        @Override public LedgerPermitAcquisition acquireLedger(long userId, String periodKind, String period, String contextFingerprint) { throw new AssertionError("ledger"); }
        @Override public SuccessSettlement completeLedgerSuccess(LedgerGenerationPermit permit, MonthlyAnalysisReport report, AnalysisProvenance provenance) { throw new AssertionError("ledger"); }
        @Override public FailureSettlement completeLedgerFailure(LedgerGenerationPermit permit) { throw new AssertionError("ledger"); }
    }

    private static final class OutcomeStore implements AnalysisGenerationStore {
        private final String fingerprint;
        private int successes;
        private int failures;
        private long lastAccountId;

        private OutcomeStore(String fingerprint) { this.fingerprint = fingerprint; }

        @Override public Optional<AnalysisSnapshot> findSuccessful(long userId, long accountId, String yearMonth,
                String contextFingerprint) {
            lastAccountId = accountId;
            return Optional.empty();
        }
        @Override public Optional<AnalysisSnapshot> findLatest(long userId, long accountId, String yearMonth) {
            lastAccountId = accountId;
            return Optional.empty();
        }
        @Override public Optional<GenerationStatus> findCoordination(long userId, long accountId, String yearMonth,
                String contextFingerprint) {
            lastAccountId = accountId;
            return Optional.empty();
        }
        @Override public PermitAcquisition acquire(long userId, long accountId, String yearMonth, String contextFingerprint) {
            lastAccountId = accountId;
            return new PermitAcquisition.Granted(new GenerationPermit(1L, 1L, userId, accountId, yearMonth, fingerprint,
                    LocalDate.parse("2026-10-13"), Instant.parse("2026-10-13T04:00:00Z")));
        }
        @Override public SuccessSettlement completeSuccess(GenerationPermit permit, MonthlyAnalysisReport report,
                AnalysisProvenance provenance) {
            successes++;
            throw new AssertionError("success snapshot");
        }
        @Override public Optional<AnalysisSnapshot> findSuccessfulLedger(long userId, String periodKind, String period, String contextFingerprint) { return Optional.empty(); }
        @Override public Optional<AnalysisSnapshot> findLatestLedger(long userId, String periodKind, String period) { return Optional.empty(); }
        @Override public Optional<GenerationStatus> findLedgerCoordination(long userId, String periodKind, String period, String contextFingerprint) { return Optional.empty(); }
        @Override public LedgerPermitAcquisition acquireLedger(long userId, String periodKind, String period, String contextFingerprint) { throw new AssertionError("ledger"); }
        @Override public SuccessSettlement completeLedgerSuccess(LedgerGenerationPermit permit, MonthlyAnalysisReport report, AnalysisProvenance provenance) { throw new AssertionError("ledger"); }
        @Override public FailureSettlement completeLedgerFailure(LedgerGenerationPermit permit) { throw new AssertionError("ledger"); }
        @Override public FailureSettlement completeFailure(GenerationPermit permit) {
            failures++;
            return new FailureSettlement.Applied();
        }
    }
}
