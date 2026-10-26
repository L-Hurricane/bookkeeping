package com.limingyuan.bookkeeping.analysis.application;

import java.util.Optional;

/**
 * Snapshot lookup and generation coordination. Each method commits before it returns,
 * so a model call cannot sit inside one of these transactions.
 * Scope is user + account + month + context fingerprint. The fingerprint itself stays
 * a pure facts hash; account identity is applied only at this persistence boundary.
 */
public interface AnalysisGenerationStore {
    int DAILY_SUCCESS_LIMIT = 3;

    Optional<AnalysisSnapshot> findSuccessful(long userId, long accountId, String yearMonth, String contextFingerprint);

    Optional<AnalysisSnapshot> findLatest(long userId, long accountId, String yearMonth);

    Optional<GenerationStatus> findCoordination(long userId, long accountId, String yearMonth, String contextFingerprint);

    PermitAcquisition acquire(long userId, long accountId, String yearMonth, String contextFingerprint);

    SuccessSettlement completeSuccess(GenerationPermit permit, MonthlyAnalysisReport report, AnalysisProvenance provenance);

    FailureSettlement completeFailure(GenerationPermit permit);

    Optional<AnalysisSnapshot> findSuccessfulLedger(long userId, String periodKind, String period, String contextFingerprint);

    Optional<AnalysisSnapshot> findLatestLedger(long userId, String periodKind, String period);

    Optional<GenerationStatus> findLedgerCoordination(long userId, String periodKind, String period, String contextFingerprint);

    LedgerPermitAcquisition acquireLedger(long userId, String periodKind, String period, String contextFingerprint);

    SuccessSettlement completeLedgerSuccess(LedgerGenerationPermit permit, MonthlyAnalysisReport report, AnalysisProvenance provenance);

    FailureSettlement completeLedgerFailure(LedgerGenerationPermit permit);
}
