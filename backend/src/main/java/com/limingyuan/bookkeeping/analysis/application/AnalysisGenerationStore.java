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
}
