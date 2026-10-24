package com.limingyuan.bookkeeping.analysis.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic account-scoped store for no-DB regression tests.
 * Mirrors the V14 scope key (userId, accountId, month, fingerprint). Rows without an
 * account id are never returned by account-scoped lookups (legacy invisible semantics).
 */
final class InMemoryAnalysisGenerationStore implements AnalysisGenerationStore {
    private static final ZoneId PERMIT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration LEASE = Duration.ofMinutes(3);

    private final Clock clock;
    private final AtomicLong snapshotIds = new AtomicLong(1);
    private final AtomicLong generationIds = new AtomicLong(1);
    private final List<AnalysisSnapshot> snapshots = new ArrayList<>();
    private final Map<Scope, Generation> generations = new HashMap<>();
    private final Map<QuotaKey, int[]> quotas = new HashMap<>();
    private final List<AnalysisSnapshot> retainedLegacy = new ArrayList<>();

    InMemoryAnalysisGenerationStore(Clock clock) {
        this.clock = clock;
    }

    /** Retain an unscoped legacy row that must stay present but invisible to account lookups. */
    void retainLegacyUnscoped(AnalysisSnapshot legacy) {
        retainedLegacy.add(legacy);
    }

    int retainedLegacyCount() {
        return retainedLegacy.size();
    }

    @Override
    public synchronized Optional<AnalysisSnapshot> findSuccessful(long userId, long accountId, String yearMonth,
            String contextFingerprint) {
        return snapshots.stream()
                .filter(s -> s.userId() == userId && s.accountId() == accountId && s.yearMonth().equals(yearMonth)
                        && s.contextFingerprint().equals(contextFingerprint))
                .min(Comparator.comparingLong(AnalysisSnapshot::id));
    }

    @Override
    public synchronized Optional<AnalysisSnapshot> findLatest(long userId, long accountId, String yearMonth) {
        return snapshots.stream()
                .filter(s -> s.userId() == userId && s.accountId() == accountId && s.yearMonth().equals(yearMonth))
                .max(Comparator.comparingLong(AnalysisSnapshot::id));
    }

    @Override
    public synchronized Optional<GenerationStatus> findCoordination(long userId, long accountId, String yearMonth,
            String contextFingerprint) {
        Generation generation = generations.get(new Scope(userId, accountId, yearMonth, contextFingerprint));
        return generation == null ? Optional.empty() : Optional.of(generation.status());
    }

    @Override
    public synchronized PermitAcquisition acquire(long userId, long accountId, String yearMonth, String contextFingerprint) {
        var existing = findSuccessful(userId, accountId, yearMonth, contextFingerprint);
        if (existing.isPresent()) return new PermitAcquisition.Reused(existing.get());
        Instant now = clock.instant();
        LocalDate permitDate = LocalDate.ofInstant(now, PERMIT_ZONE);
        Scope scope = new Scope(userId, accountId, yearMonth, contextFingerprint);
        Generation current = generations.get(scope);
        if (current == null) {
            if (!reserve(userId, permitDate)) return new PermitAcquisition.QuotaExceeded();
            Generation created = new Generation(generationIds.getAndIncrement(), userId, accountId, yearMonth,
                    contextFingerprint, GenerationState.IN_PROGRESS, 1L, permitDate, now.plus(LEASE), true, null);
            generations.put(scope, created);
            return new PermitAcquisition.Granted(created.permit());
        }
        if (current.state == GenerationState.IN_PROGRESS && current.leaseExpiresAt.isAfter(now)) {
            return new PermitAcquisition.Busy(current.leaseExpiresAt);
        }
        if (current.state == GenerationState.SUCCEEDED) {
            throw new IllegalStateException("successful generation has no snapshot");
        }
        if (current.quotaReserved) release(userId, current.permitDate);
        if (!reserve(userId, permitDate)) {
            current.state = GenerationState.FAILED;
            current.quotaReserved = false;
            current.fencingToken++;
            return new PermitAcquisition.QuotaExceeded();
        }
        current.state = GenerationState.IN_PROGRESS;
        current.fencingToken++;
        current.permitDate = permitDate;
        current.leaseExpiresAt = now.plus(LEASE);
        current.quotaReserved = true;
        return new PermitAcquisition.Granted(current.permit());
    }

    @Override
    public synchronized SuccessSettlement completeSuccess(GenerationPermit permit, MonthlyAnalysisReport report,
            AnalysisProvenance provenance) {
        Generation current = generations.get(new Scope(permit.userId(), permit.accountId(), permit.yearMonth(),
                permit.contextFingerprint()));
        if (!holds(current, permit)) return new SuccessSettlement.StalePermit();
        var snapshot = new AnalysisSnapshot(snapshotIds.getAndIncrement(), permit.userId(), permit.accountId(),
                permit.yearMonth(), permit.contextFingerprint(), report, provenance, permit.permitDate(), clock.instant());
        snapshots.add(snapshot);
        consume(permit.userId(), current.permitDate);
        current.state = GenerationState.SUCCEEDED;
        current.quotaReserved = false;
        current.snapshotId = snapshot.id();
        current.fencingToken++;
        return new SuccessSettlement.Applied(snapshot);
    }

    @Override
    public synchronized FailureSettlement completeFailure(GenerationPermit permit) {
        Generation current = generations.get(new Scope(permit.userId(), permit.accountId(), permit.yearMonth(),
                permit.contextFingerprint()));
        if (!holds(current, permit)) return new FailureSettlement.StalePermit();
        release(permit.userId(), current.permitDate);
        current.state = GenerationState.FAILED;
        current.quotaReserved = false;
        current.fencingToken++;
        return new FailureSettlement.Applied();
    }

    private boolean reserve(long userId, LocalDate permitDate) {
        int[] counts = quotas.computeIfAbsent(new QuotaKey(userId, permitDate), key -> new int[] {0, 0});
        if (counts[0] + counts[1] >= DAILY_SUCCESS_LIMIT) return false;
        counts[0]++;
        return true;
    }

    private void release(long userId, LocalDate permitDate) {
        int[] counts = quotas.get(new QuotaKey(userId, permitDate));
        if (counts == null || counts[0] < 1) throw new IllegalStateException("quota reservation was not released");
        counts[0]--;
    }

    private void consume(long userId, LocalDate permitDate) {
        int[] counts = quotas.get(new QuotaKey(userId, permitDate));
        if (counts == null || counts[0] < 1) throw new IllegalStateException("quota reservation was not consumed");
        counts[0]--;
        counts[1]++;
    }

    private boolean holds(Generation current, GenerationPermit permit) {
        return current != null && current.id == permit.generationId() && current.fencingToken == permit.fencingToken()
                && current.userId == permit.userId() && current.accountId == permit.accountId()
                && current.yearMonth.equals(permit.yearMonth())
                && current.contextFingerprint.equals(permit.contextFingerprint())
                && current.state == GenerationState.IN_PROGRESS && current.quotaReserved
                && current.permitDate.equals(permit.permitDate()) && current.leaseExpiresAt.isAfter(clock.instant());
    }

    private record Scope(long userId, long accountId, String yearMonth, String fingerprint) { }

    private record QuotaKey(long userId, LocalDate permitDate) { }

    private static final class Generation {
        private final long id;
        private final long userId;
        private final long accountId;
        private final String yearMonth;
        private final String contextFingerprint;
        private GenerationState state;
        private long fencingToken;
        private LocalDate permitDate;
        private Instant leaseExpiresAt;
        private boolean quotaReserved;
        private Long snapshotId;

        private Generation(long id, long userId, long accountId, String yearMonth, String contextFingerprint,
                GenerationState state, long fencingToken, LocalDate permitDate, Instant leaseExpiresAt,
                boolean quotaReserved, Long snapshotId) {
            this.id = id;
            this.userId = userId;
            this.accountId = accountId;
            this.yearMonth = yearMonth;
            this.contextFingerprint = contextFingerprint;
            this.state = state;
            this.fencingToken = fencingToken;
            this.permitDate = permitDate;
            this.leaseExpiresAt = leaseExpiresAt;
            this.quotaReserved = quotaReserved;
            this.snapshotId = snapshotId;
        }

        private GenerationPermit permit() {
            return new GenerationPermit(id, fencingToken, userId, accountId, yearMonth, contextFingerprint, permitDate,
                    leaseExpiresAt);
        }

        private GenerationStatus status() {
            return new GenerationStatus(id, userId, accountId, yearMonth, contextFingerprint, state, leaseExpiresAt,
                    permitDate, snapshotId);
        }
    }
}
