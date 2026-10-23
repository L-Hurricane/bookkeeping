package com.limingyuan.bookkeeping.account.application;

import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class AccountPermanentDeletionAnalysisCounter {
    private final JdbcClient jdbc;
    private final TrustedUserContext context;

    public AccountPermanentDeletionAnalysisCounter(JdbcClient jdbc, TrustedUserContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    public List<Long> snapshotIdsForAccount(long accountId) {
        long userId = context.userId();
        return jdbc.sql("""
                SELECT id FROM analysis_snapshots
                WHERE user_id = :userId AND account_id = :accountId
                ORDER BY id
                """).param("userId", userId).param("accountId", accountId).query(Long.class).list();
    }

    public List<Long> generationIdsForAccount(long accountId) {
        long userId = context.userId();
        return jdbc.sql("""
                SELECT id FROM analysis_generations
                WHERE user_id = :userId AND account_id = :accountId
                ORDER BY id
                """).param("userId", userId).param("accountId", accountId).query(Long.class).list();
    }

    public boolean hasReferenceConflict(long accountId) {
        long userId = context.userId();
        Integer invalidGenerationRefs = jdbc.sql("""
                SELECT COUNT(*)
                FROM analysis_generations g
                WHERE g.user_id = :userId
                  AND g.account_id = :accountId
                  AND g.snapshot_id IS NOT NULL
                  AND NOT EXISTS (
                    SELECT 1
                    FROM analysis_snapshots s
                    WHERE s.id = g.snapshot_id
                      AND s.user_id = :userId
                      AND s.account_id = :accountId
                  )
                """).param("userId", userId).param("accountId", accountId).query(Integer.class).single();
        if (invalidGenerationRefs != null && invalidGenerationRefs > 0) {
            return true;
        }
        Integer inboundRefs = jdbc.sql("""
                SELECT COUNT(*)
                FROM analysis_snapshots s
                WHERE s.user_id = :userId
                  AND s.account_id = :accountId
                  AND EXISTS (
                    SELECT 1
                    FROM analysis_generations g
                    WHERE g.snapshot_id = s.id
                      AND NOT (g.user_id = :userId AND g.account_id = :accountId)
                  )
                """).param("userId", userId).param("accountId", accountId).query(Integer.class).single();
        return inboundRefs != null && inboundRefs > 0;
    }

    public void deleteGenerationsForAccount(long accountId) {
        long userId = context.userId();
        jdbc.sql("""
                DELETE FROM analysis_generations
                WHERE user_id = :userId AND account_id = :accountId
                """).param("userId", userId).param("accountId", accountId).update();
    }

    public void deleteSnapshotsForAccount(long accountId) {
        long userId = context.userId();
        jdbc.sql("""
                DELETE FROM analysis_snapshots
                WHERE user_id = :userId AND account_id = :accountId
                """).param("userId", userId).param("accountId", accountId).update();
    }

}
