package com.limingyuan.bookkeeping.account.application;

import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.util.HashSet;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class AccountPermanentDeletionAnalysisCounter {
    /**
     * {@code account_id IS NULL} must be written out. {@code account_id = :accountId} is UNKNOWN
     * for those rows, so {@code NOT (user_id = :userId AND account_id = :accountId)} skips them.
     */
    public static final String INBOUND_REFERENCE_SQL = """
            SELECT COUNT(*)
            FROM analysis_snapshots s
            WHERE s.user_id = :userId
              AND s.account_id = :accountId
              AND EXISTS (
                SELECT 1
                FROM analysis_generations g
                WHERE g.snapshot_id = s.id
                  AND (
                    g.user_id <> :userId
                    OR g.account_id IS NULL
                    OR g.account_id <> :accountId
                  )
              )
            """;

    /** Locking read of the same outside-account predicate, including NULL account_id. */
    public static final String INBOUND_LOCK_SQL = """
            SELECT g.id
            FROM analysis_generations g
            WHERE g.snapshot_id IN (:snapshotIds)
              AND (
                g.user_id <> :userId
                OR g.account_id IS NULL
                OR g.account_id <> :accountId
              )
            FOR UPDATE
            """;

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
        Integer inboundRefs = jdbc.sql(INBOUND_REFERENCE_SQL)
                .param("userId", userId).param("accountId", accountId).query(Integer.class).single();
        return inboundRefs != null && inboundRefs > 0;
    }

    /**
     * Same predicate as {@link #INBOUND_REFERENCE_SQL}. A NULL {@code account_id} does not equal
     * the target account, so it is a foreign reference and must not be deleted.
     */
    public static boolean generationReferencesFromOutside(long userId, long accountId, long generationUserId,
            Long generationAccountId) {
        return generationUserId != userId || generationAccountId == null || generationAccountId != accountId;
    }

    public List<Long> lockSnapshotIdsForAccount(long accountId) {
        long userId = context.userId();
        return jdbc.sql("""
                SELECT id FROM analysis_snapshots
                WHERE user_id = :userId AND account_id = :accountId
                ORDER BY id
                FOR UPDATE
                """).param("userId", userId).param("accountId", accountId).query(Long.class).list();
    }

    public List<LockedGeneration> lockGenerationsForAccount(long accountId) {
        long userId = context.userId();
        return jdbc.sql("""
                SELECT id, snapshot_id FROM analysis_generations
                WHERE user_id = :userId AND account_id = :accountId
                ORDER BY id
                FOR UPDATE
                """).param("userId", userId).param("accountId", accountId).query((row, rowNum) ->
                new LockedGeneration(row.getLong("id"), (Long) row.getObject("snapshot_id"))).list();
    }

    /**
     * Current read of generations that point at this account's snapshots from another user,
     * another account, or a NULL account. Those rows stay; the delete is refused.
     */
    public boolean lockInboundReferenceConflict(long accountId, List<Long> snapshotIds) {
        if (snapshotIds.isEmpty()) {
            return false;
        }
        long userId = context.userId();
        List<Long> inboundRefs = jdbc.sql(INBOUND_LOCK_SQL)
                .param("snapshotIds", snapshotIds).param("userId", userId).param("accountId", accountId)
                .query(Long.class).list();
        return !inboundRefs.isEmpty();
    }

    public static boolean outboundConflict(List<Long> snapshotIds, List<LockedGeneration> generations) {
        var owned = new HashSet<>(snapshotIds);
        for (var generation : generations) {
            if (generation.snapshotId() != null && !owned.contains(generation.snapshotId())) {
                return true;
            }
        }
        return false;
    }

    public record LockedGeneration(long id, Long snapshotId) {
    }

    public void deleteGenerationsByIds(long accountId, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        long userId = context.userId();
        jdbc.sql("""
                DELETE FROM analysis_generations
                WHERE user_id = :userId AND account_id = :accountId AND id IN (:ids)
                """).param("userId", userId).param("accountId", accountId).param("ids", ids).update();
    }

    public void deleteSnapshotsByIds(long accountId, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        long userId = context.userId();
        jdbc.sql("""
                DELETE FROM analysis_snapshots
                WHERE user_id = :userId AND account_id = :accountId AND id IN (:ids)
                """).param("userId", userId).param("accountId", accountId).param("ids", ids).update();
    }

}
