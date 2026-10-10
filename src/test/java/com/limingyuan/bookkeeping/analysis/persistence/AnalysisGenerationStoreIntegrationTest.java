package com.limingyuan.bookkeeping.analysis.persistence;

import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationStore;
import com.limingyuan.bookkeeping.analysis.application.AnalysisProvenance;
import com.limingyuan.bookkeeping.analysis.application.FailureSettlement;
import com.limingyuan.bookkeeping.analysis.application.GenerationPermit;
import com.limingyuan.bookkeeping.analysis.application.GenerationState;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisReport;
import com.limingyuan.bookkeeping.analysis.application.PermitAcquisition;
import com.limingyuan.bookkeeping.analysis.application.SuccessSettlement;
import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AnalysisGenerationStoreIntegrationTest {
    private static final Instant NOON = Instant.parse("2026-10-10T04:00:00Z");
    private static final String MONTH = "2026-10";

    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    private final MutableClock clock = new MutableClock(NOON);
    private JdbcAnalysisGenerationStore store;
    private final List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() {
        clock.set(NOON);
        store = new JdbcAnalysisGenerationStore(jdbc, transactions, clock, JdbcAnalysisGenerationStore.LEASE);
    }

    @AfterEach void cleanup() {
        for (long id : fixtures) {
            jdbc.sql("DELETE FROM analysis_generations WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM analysis_snapshots WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM analysis_quota_days WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM analysis_user_locks WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM idempotency_records WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM balance_adjustments WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM transaction_tags WHERE transaction_id IN (SELECT id FROM transactions WHERE user_id = :id)").param("id", id).update();
            jdbc.sql("DELETE FROM transactions WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM tags WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM accounts WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
            users.deleteById(id);
        }
    }

    @Test void firstPermitReservesOneSlot() {
        long userId = newUser();
        var permit = granted(store.acquire(userId, MONTH, fingerprint("a")));
        assertThat(permit.fencingToken()).isEqualTo(1L);
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(store.findCoordination(userId, MONTH, fingerprint("a")).orElseThrow().state()).isEqualTo(GenerationState.IN_PROGRESS);
        assertThat(quota(userId)).isEqualTo(new Quota(1, 0));
        assertThat(store.findSuccessful(userId, MONTH, fingerprint("a"))).isEmpty();
    }

    @Test void oneScopeCannotGrantTwoPermits() throws Exception {
        long userId = newUser();
        var results = concurrent(() -> store.acquire(userId, MONTH, fingerprint("same")), 2);
        assertThat(results.stream().filter(PermitAcquisition.Granted.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(PermitAcquisition.Busy.class::isInstance)).hasSize(1);
        assertThat(quota(userId)).isEqualTo(new Quota(1, 0));
    }

    @Test void differentScopesShareTheDailyLimitWhileInProgress() {
        long userId = newUser();
        for (int i = 0; i < AnalysisGenerationStore.DAILY_SUCCESS_LIMIT; i++) granted(store.acquire(userId, MONTH, fingerprint("p" + i)));
        assertThat(store.acquire(userId, "2026-11", fingerprint("other"))).isInstanceOf(PermitAcquisition.QuotaExceeded.class);
        assertThat(quota(userId)).isEqualTo(new Quota(3, 0));
    }

    @Test void fourthSuccessfulAnalysisIsRejected() {
        long userId = newUser();
        for (int i = 0; i < 3; i++) succeed(userId, fingerprint("done" + i), "报告" + i);
        assertThat(store.acquire(userId, MONTH, fingerprint("fourth"))).isInstanceOf(PermitAcquisition.QuotaExceeded.class);
        assertThat(quota(userId)).isEqualTo(new Quota(0, 3));
        assertThat(store.findSuccessful(userId, MONTH, fingerprint("fourth"))).isEmpty();
    }

    @Test void reusingASnapshotDoesNotConsumeQuota() {
        long userId = newUser();
        var first = succeed(userId, fingerprint("reuse"), "第一次");
        var again = store.acquire(userId, MONTH, fingerprint("reuse"));
        assertThat(again).isInstanceOf(PermitAcquisition.Reused.class);
        assertThat(((PermitAcquisition.Reused) again).snapshot().id()).isEqualTo(first.id());
        assertThat(quota(userId)).isEqualTo(new Quota(0, 1));
    }

    @Test void modelFailureReleasesTheReservation() {
        long userId = newUser();
        var permit = granted(store.acquire(userId, MONTH, fingerprint("fail")));
        assertThat(store.completeFailure(permit)).isInstanceOf(FailureSettlement.Applied.class);
        assertThat(quota(userId)).isEqualTo(new Quota(0, 0));
        assertThat(store.findSuccessful(userId, MONTH, fingerprint("fail"))).isEmpty();
        assertThat(store.findCoordination(userId, MONTH, fingerprint("fail")).orElseThrow().state()).isEqualTo(GenerationState.FAILED);
        var retry = granted(store.acquire(userId, MONTH, fingerprint("fail")));
        assertThat(retry.fencingToken()).isGreaterThan(permit.fencingToken());
        assertThat(quota(userId)).isEqualTo(new Quota(1, 0));
    }

    @Test void snapshotAndQuotaCommitTogether() {
        long userId = newUser();
        var permit = granted(store.acquire(userId, MONTH, fingerprint("atomic")));
        jdbc.sql("UPDATE analysis_quota_days SET reserved_count = 0 WHERE user_id = :id").param("id", userId).update();
        assertThatThrownBy(() -> store.completeSuccess(permit, report("不会留下"), provenance()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.findSuccessful(userId, MONTH, fingerprint("atomic"))).isEmpty();
        assertThat(countSnapshots(userId)).isZero();
    }

    @Test void expiredLeaseCanBeRecoveredAndTheOldTokenCannotSucceed() {
        long userId = newUser();
        var expired = granted(store.acquire(userId, MONTH, fingerprint("lease")));
        clock.advance(Duration.ofMinutes(4));
        assertThat(store.completeSuccess(expired, report("过期"), provenance())).isInstanceOf(SuccessSettlement.StalePermit.class);
        assertThat(countSnapshots(userId)).isZero();
        var recovered = granted(store.acquire(userId, MONTH, fingerprint("lease")));
        assertThat(recovered.fencingToken()).isGreaterThan(expired.fencingToken());
        assertThat(store.completeSuccess(expired, report("旧令牌"), provenance())).isInstanceOf(SuccessSettlement.StalePermit.class);
        var snapshot = applied(store.completeSuccess(recovered, report("恢复后"), provenance()));
        assertThat(snapshot.report().monthlyOverview()).isEqualTo("恢复后");
        assertThat(countSnapshots(userId)).isEqualTo(1);
        assertThat(quota(userId)).isEqualTo(new Quota(0, 1));
    }

    @Test void expiredReservationsOnOtherScopesAreReclaimed() {
        long userId = newUser();
        var first = granted(store.acquire(userId, MONTH, fingerprint("s0")));
        var second = granted(store.acquire(userId, MONTH, fingerprint("s1")));
        var third = granted(store.acquire(userId, "2026-11", fingerprint("s2")));
        clock.advance(Duration.ofMinutes(4));
        var fourth = granted(store.acquire(userId, "2026-12", fingerprint("s3")));
        assertThat(fourth.fencingToken()).isEqualTo(1L);
        assertThat(quota(userId)).isEqualTo(new Quota(1, 0));
        assertThat(countSnapshots(userId)).isZero();
        for (var expired : List.of(first, second, third)) {
            assertThat(store.completeSuccess(expired, report("过期"), provenance())).isInstanceOf(SuccessSettlement.StalePermit.class);
            assertThat(store.completeFailure(expired)).isInstanceOf(FailureSettlement.StalePermit.class);
            assertThat(store.findCoordination(userId, expired.yearMonth(), expired.contextFingerprint()).orElseThrow().state())
                    .isEqualTo(GenerationState.FAILED);
        }
        assertThat(quota(userId)).isEqualTo(new Quota(1, 0));
    }

    @Test void reclamationLeavesConsumedQuotaAndLivePermits() {
        long userId = newUser();
        var saved = succeed(userId, fingerprint("done"), "已成功");
        var expired = granted(store.acquire(userId, "2026-11", fingerprint("expired")));
        clock.advance(Duration.ofMinutes(2));
        var live = granted(store.acquire(userId, "2026-12", fingerprint("live")));
        clock.advance(Duration.ofMinutes(2));
        granted(store.acquire(userId, MONTH, fingerprint("next")));
        assertThat(store.findCoordination(userId, live.yearMonth(), live.contextFingerprint()).orElseThrow().state())
                .isEqualTo(GenerationState.IN_PROGRESS);
        assertThat(store.findSuccessful(userId, MONTH, fingerprint("done")).orElseThrow()).isEqualTo(saved);
        applied(store.completeSuccess(live, report("仍有效"), provenance()));
        assertThat(store.completeSuccess(expired, report("过期"), provenance())).isInstanceOf(SuccessSettlement.StalePermit.class);
        assertThat(store.completeFailure(expired)).isInstanceOf(FailureSettlement.StalePermit.class);
        assertThat(quota(userId)).isEqualTo(new Quota(1, 2));
        assertThat(countSnapshots(userId)).isEqualTo(2);
    }

    @Test void reclamationDoesNotTouchAnotherUsersReservation() {
        long owner = newUser();
        long other = newUser();
        granted(store.acquire(other, MONTH, fingerprint("other")));
        for (int i = 0; i < 3; i++) granted(store.acquire(owner, MONTH, fingerprint("held" + i)));
        clock.advance(Duration.ofMinutes(4));
        granted(store.acquire(owner, "2026-11", fingerprint("recovered")));
        assertThat(quota(owner)).isEqualTo(new Quota(1, 0));
        assertThat(quota(other)).isEqualTo(new Quota(1, 0));
        assertThat(store.findCoordination(other, MONTH, fingerprint("other")).orElseThrow().state())
                .isEqualTo(GenerationState.IN_PROGRESS);
    }

    @Test void expiredReservationIsReleasedOnItsOriginalPermitDate() {
        clock.set(Instant.parse("2026-10-10T15:50:00Z"));
        var dated = new JdbcAnalysisGenerationStore(jdbc, transactions, clock, Duration.ofMinutes(30));
        long userId = newUser();
        var held = new ArrayList<GenerationPermit>();
        for (int i = 0; i < 3; i++) held.add(granted(dated.acquire(userId, MONTH, fingerprint("night" + i))));
        assertThat(held).allSatisfy(permit -> assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 10, 10)));
        clock.set(Instant.parse("2026-10-10T16:30:00Z"));
        var next = granted(dated.acquire(userId, "2026-11", fingerprint("after-midnight")));
        assertThat(next.permitDate()).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(quotaOn(userId, LocalDate.of(2026, 10, 10))).isEqualTo(new Quota(0, 0));
        assertThat(quotaOn(userId, LocalDate.of(2026, 10, 11))).isEqualTo(new Quota(1, 0));
        for (var expired : held) {
            assertThat(dated.completeSuccess(expired, report("过期"), provenance())).isInstanceOf(SuccessSettlement.StalePermit.class);
            assertThat(dated.completeFailure(expired)).isInstanceOf(FailureSettlement.StalePermit.class);
        }
        assertThat(quotaOn(userId, LocalDate.of(2026, 10, 10))).isEqualTo(new Quota(0, 0));
    }

    @Test void successAfterMidnightUsesThePermitDate() {
        clock.set(Instant.parse("2026-10-10T15:50:00Z"));
        var dated = new JdbcAnalysisGenerationStore(jdbc, transactions, clock, Duration.ofHours(2));
        long userId = newUser();
        var permit = granted(dated.acquire(userId, MONTH, fingerprint("midnight")));
        assertThat(permit.permitDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        clock.set(Instant.parse("2026-10-10T16:10:00Z"));
        var snapshot = applied(dated.completeSuccess(permit, report("跨日"), provenance()));
        assertThat(snapshot.permitDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(snapshot.generatedAt()).isEqualTo(Instant.parse("2026-10-10T16:10:00Z"));
        assertThat(quotaOn(userId, LocalDate.of(2026, 10, 10))).isEqualTo(new Quota(0, 1));
        assertThat(quotaOn(userId, LocalDate.of(2026, 10, 11))).isEqualTo(new Quota(0, 0));
    }

    @Test void historicalSnapshotsStayImmutable() {
        long userId = newUser();
        var first = succeed(userId, fingerprint("old"), "历史概况");
        var second = succeed(userId, fingerprint("new"), "最新概况");
        assertThat(store.findSuccessful(userId, MONTH, fingerprint("old")).orElseThrow()).isEqualTo(first);
        assertThat(store.findLatest(userId, MONTH).orElseThrow().id()).isEqualTo(second.id());
        assertThat(countSnapshots(userId)).isEqualTo(2);
    }

    @Test void usersDoNotSeeEachOthersSnapshotsOrQuota() {
        long first = newUser();
        long second = newUser();
        for (int i = 0; i < 3; i++) succeed(first, fingerprint("u" + i), "用户一");
        assertThat(store.findLatest(second, MONTH)).isEmpty();
        assertThat(store.findSuccessful(second, MONTH, fingerprint("u0"))).isEmpty();
        granted(store.acquire(second, MONTH, fingerprint("own")));
        assertThat(quota(first)).isEqualTo(new Quota(0, 3));
        assertThat(quota(second)).isEqualTo(new Quota(1, 0));
    }

    @Test void concurrentSettlementCannotExceedTheDailyCap() throws Exception {
        long userId = newUser();
        var acquisitions = concurrent(() -> store.acquire(userId, MONTH, fingerprint(UUID.randomUUID().toString())), 6);
        var permits = acquisitions.stream().filter(PermitAcquisition.Granted.class::isInstance)
                .map(item -> ((PermitAcquisition.Granted) item).permit()).toList();
        assertThat(permits).hasSize(3);
        assertThat(acquisitions.stream().filter(PermitAcquisition.QuotaExceeded.class::isInstance)).hasSize(3);
        var settlements = concurrent(() -> store.completeSuccess(permits.get(0), report("一次"), provenance()), 2);
        assertThat(settlements.stream().filter(SuccessSettlement.Applied.class::isInstance)).hasSize(1);
        assertThat(settlements.stream().filter(SuccessSettlement.StalePermit.class::isInstance)).hasSize(1);
        applied(store.completeSuccess(permits.get(1), report("两次"), provenance()));
        applied(store.completeSuccess(permits.get(2), report("三次"), provenance()));
        assertThat(countSnapshots(userId)).isEqualTo(3);
        assertThat(quota(userId)).isEqualTo(new Quota(0, 3));
        assertThat(store.acquire(userId, MONTH, fingerprint("extra"))).isInstanceOf(PermitAcquisition.QuotaExceeded.class);
    }

    private com.limingyuan.bookkeeping.analysis.application.AnalysisSnapshot succeed(long userId, String fingerprint, String overview) {
        var permit = granted(store.acquire(userId, MONTH, fingerprint));
        return applied(store.completeSuccess(permit, report(overview), provenance()));
    }

    private static GenerationPermit granted(PermitAcquisition acquisition) {
        assertThat(acquisition).isInstanceOf(PermitAcquisition.Granted.class);
        return ((PermitAcquisition.Granted) acquisition).permit();
    }

    private static com.limingyuan.bookkeeping.analysis.application.AnalysisSnapshot applied(SuccessSettlement settlement) {
        assertThat(settlement).isInstanceOf(SuccessSettlement.Applied.class);
        return ((SuccessSettlement.Applied) settlement).snapshot();
    }

    private Quota quota(long userId) {
        return quotaOn(userId, LocalDate.of(2026, 10, 10));
    }

    private Quota quotaOn(long userId, LocalDate date) {
        return jdbc.sql("""
                SELECT reserved_count, consumed_count FROM analysis_quota_days
                WHERE user_id = :userId AND permit_date = :permitDate
                """).param("userId", userId).param("permitDate", date)
                .query((rs, row) -> new Quota(rs.getInt("reserved_count"), rs.getInt("consumed_count"))).optional()
                .orElse(new Quota(0, 0));
    }

    private int countSnapshots(long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM analysis_snapshots WHERE user_id = :id").param("id", userId).query(Integer.class).single();
    }

    private <T> List<T> concurrent(java.util.concurrent.Callable<T> call, int threads) throws Exception {
        var ready = new CountDownLatch(threads);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long newUser() {
        String name = "analysis_store_" + UUID.randomUUID();
        bootstrap.ensureUser(name, tokens.generate());
        long id = users.findByUsername(name).orElseThrow().getId();
        fixtures.add(id);
        return id;
    }

    private static String fingerprint(String suffix) {
        return "1:" + suffix;
    }

    private static MonthlyAnalysisReport report(String overview) {
        return new MonthlyAnalysisReport(overview, "支出结构", "结余解释", "值得关注");
    }

    private static AnalysisProvenance provenance() {
        return new AnalysisProvenance("dashscope", "qwen3.7-flash", "v1", null, null, null, 12L, null);
    }

    private record Quota(int reserved, int consumed) { }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }

        private void set(Instant instant) { this.instant = instant; }

        private void advance(Duration duration) { instant = instant.plus(duration); }

        @Override public Instant instant() { return instant; }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
    }
}
