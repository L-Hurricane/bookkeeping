package com.limingyuan.bookkeeping.analysis.persistence;

import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationStore;
import com.limingyuan.bookkeeping.analysis.application.AnalysisProvenance;
import com.limingyuan.bookkeeping.analysis.application.AnalysisSnapshot;
import com.limingyuan.bookkeeping.analysis.application.FailureSettlement;
import com.limingyuan.bookkeeping.analysis.application.GenerationPermit;
import com.limingyuan.bookkeeping.analysis.application.GenerationState;
import com.limingyuan.bookkeeping.analysis.application.GenerationStatus;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisReport;
import com.limingyuan.bookkeeping.analysis.application.PermitAcquisition;
import com.limingyuan.bookkeeping.analysis.application.SuccessSettlement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL snapshot and coordination store. The daily cap is enforced on the quota row, not in memory. */
public final class JdbcAnalysisGenerationStore implements AnalysisGenerationStore {
    public static final Duration LEASE = Duration.ofMinutes(3);
    static final ZoneId PERMIT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String SNAPSHOT_COLUMNS = """
            id, user_id, analysis_month, context_fingerprint, monthly_overview, expense_structure, surplus_reasons,
            points_to_watch, provider, model, prompt_version, prompt_tokens, completion_tokens, total_tokens,
            latency_millis, provider_request_id, permit_date, generated_at_utc
            """;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration lease;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public JdbcAnalysisGenerationStore(JdbcClient jdbc, PlatformTransactionManager transactionManager, Clock clock, Duration lease) {
        if (lease == null || lease.isZero() || lease.isNegative()) throw new IllegalArgumentException("lease must be positive");
        this.jdbc = jdbc;
        this.clock = clock;
        this.lease = lease;
        this.write = transaction(transactionManager, false);
        this.read = transaction(transactionManager, true);
    }

    @Override
    public Optional<AnalysisSnapshot> findSuccessful(long userId, String yearMonth, String contextFingerprint) {
        return read.execute(status -> snapshot(userId, yearMonth, contextFingerprint, true));
    }

    @Override
    public Optional<AnalysisSnapshot> findLatest(long userId, String yearMonth) {
        return read.execute(status -> jdbc.sql("SELECT " + SNAPSHOT_COLUMNS + """
                FROM analysis_snapshots WHERE user_id = :userId AND analysis_month = :yearMonth ORDER BY id DESC LIMIT 1
                """).param("userId", userId).param("yearMonth", yearMonth).query(this::snapshot).optional());
    }

    @Override
    public Optional<GenerationStatus> findCoordination(long userId, String yearMonth, String contextFingerprint) {
        return read.execute(status -> generation(userId, yearMonth, contextFingerprint).map(GenerationRow::status));
    }

    @Override
    public PermitAcquisition acquire(long userId, String yearMonth, String contextFingerprint) {
        requireScope(yearMonth, contextFingerprint);
        return write.execute(status -> {
            lockUser(userId);
            Instant now = now();
            reclaimExpiredReservations(userId, now);
            var existing = snapshot(userId, yearMonth, contextFingerprint, true);
            if (existing.isPresent()) return new PermitAcquisition.Reused(existing.get());
            LocalDate permitDate = LocalDate.ofInstant(now, PERMIT_ZONE);
            var row = generation(userId, yearMonth, contextFingerprint);
            if (row.isEmpty()) {
                if (!reserve(userId, permitDate)) return new PermitAcquisition.QuotaExceeded();
                return new PermitAcquisition.Granted(insertGeneration(userId, yearMonth, contextFingerprint, permitDate, now));
            }
            GenerationRow current = row.get();
            if (current.state == GenerationState.IN_PROGRESS && current.leaseExpiresAt.isAfter(now)) {
                return new PermitAcquisition.Busy(current.leaseExpiresAt);
            }
            if (current.state == GenerationState.SUCCEEDED) {
                throw new IllegalStateException("successful generation has no snapshot");
            }
            if (current.quotaReserved && !release(userId, current.permitDate)) {
                throw new IllegalStateException("quota reservation was not released");
            }
            if (!reserve(userId, permitDate)) {
                finish(current, GenerationState.FAILED, null, false);
                return new PermitAcquisition.QuotaExceeded();
            }
            return new PermitAcquisition.Granted(takeover(current, permitDate, now));
        });
    }

    @Override
    public SuccessSettlement completeSuccess(GenerationPermit permit, MonthlyAnalysisReport report, AnalysisProvenance provenance) {
        requireReport(report);
        requireProvenance(provenance);
        return write.execute(status -> {
            lockUser(permit.userId());
            GenerationRow row = generationById(permit.generationId()).orElse(null);
            if (!holds(row, permit)) return new SuccessSettlement.StalePermit();
            long snapshotId = insertSnapshot(permit, report, provenance, now());
            if (!consume(permit.userId(), row.permitDate)) throw new IllegalStateException("quota reservation was not consumed");
            if (!finish(row, GenerationState.SUCCEEDED, snapshotId, false)) {
                throw new IllegalStateException("generation permit changed");
            }
            return new SuccessSettlement.Applied(snapshotById(snapshotId).orElseThrow());
        });
    }

    @Override
    public FailureSettlement completeFailure(GenerationPermit permit) {
        return write.execute(status -> {
            lockUser(permit.userId());
            GenerationRow row = generationById(permit.generationId()).orElse(null);
            if (!holds(row, permit)) return new FailureSettlement.StalePermit();
            if (!release(permit.userId(), row.permitDate)) throw new IllegalStateException("quota reservation was not released");
            if (!finish(row, GenerationState.FAILED, null, false)) throw new IllegalStateException("generation permit changed");
            return new FailureSettlement.Applied();
        });
    }

    private void reclaimExpiredReservations(long userId, Instant now) {
        List<GenerationRow> expired = jdbc.sql("""
                SELECT id, user_id, analysis_month, context_fingerprint, state, fencing_token, permit_date,
                       lease_expires_at_utc, quota_reserved, snapshot_id
                FROM analysis_generations
                WHERE user_id = :userId AND state = 'IN_PROGRESS' AND quota_reserved = 1
                  AND lease_expires_at_utc <= :now
                """).param("userId", userId).param("now", timestamp(now)).query(this::generation).list();
        for (GenerationRow row : expired) {
            if (!release(userId, row.permitDate)) throw new IllegalStateException("quota reservation was not released");
            if (!finish(row, GenerationState.FAILED, null, false)) {
                throw new IllegalStateException("expired generation permit changed");
            }
        }
    }

    private GenerationPermit insertGeneration(long userId, String yearMonth, String fingerprint, LocalDate permitDate, Instant now) {
        Instant leaseExpiresAt = now.plus(lease);
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO analysis_generations
                    (user_id, analysis_month, context_fingerprint, state, fencing_token, permit_date, lease_expires_at_utc,
                     quota_reserved, snapshot_id, created_at_utc, updated_at_utc)
                VALUES
                    (:userId, :yearMonth, :fingerprint, 'IN_PROGRESS', 1, :permitDate, :lease, 1, NULL, :now, :now)
                """).param("userId", userId).param("yearMonth", yearMonth).param("fingerprint", fingerprint)
                .param("permitDate", permitDate).param("lease", timestamp(leaseExpiresAt)).param("now", timestamp(now)).update(keys);
        return new GenerationPermit(keys.getKey().longValue(), 1L, userId, yearMonth, fingerprint, permitDate, leaseExpiresAt);
    }

    private GenerationPermit takeover(GenerationRow current, LocalDate permitDate, Instant now) {
        Instant leaseExpiresAt = now.plus(lease);
        int updated = jdbc.sql("""
                UPDATE analysis_generations
                SET state = 'IN_PROGRESS', fencing_token = fencing_token + 1, permit_date = :permitDate,
                    lease_expires_at_utc = :lease, quota_reserved = 1, updated_at_utc = :now
                WHERE id = :id AND fencing_token = :token
                """).param("permitDate", permitDate).param("lease", timestamp(leaseExpiresAt)).param("now", timestamp(now))
                .param("id", current.id).param("token", current.fencingToken).update();
        if (updated != 1) throw new IllegalStateException("generation permit changed");
        return new GenerationPermit(current.id, current.fencingToken + 1, current.userId, current.yearMonth,
                current.contextFingerprint, permitDate, leaseExpiresAt);
    }

    private boolean finish(GenerationRow current, GenerationState state, Long snapshotId, boolean reserved) {
        return jdbc.sql("""
                UPDATE analysis_generations
                SET state = :state, fencing_token = fencing_token + 1, quota_reserved = :reserved,
                    snapshot_id = :snapshotId, updated_at_utc = :now
                WHERE id = :id AND fencing_token = :token AND state = 'IN_PROGRESS'
                """).param("state", state.name()).param("reserved", reserved ? 1 : 0).param("snapshotId", snapshotId)
                .param("now", timestamp(now())).param("id", current.id).param("token", current.fencingToken).update() == 1;
    }

    private long insertSnapshot(GenerationPermit permit, MonthlyAnalysisReport report, AnalysisProvenance provenance, Instant generatedAt) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO analysis_snapshots
                    (user_id, analysis_month, context_fingerprint, monthly_overview, expense_structure, surplus_reasons,
                     points_to_watch, provider, model, prompt_version, prompt_tokens, completion_tokens, total_tokens,
                     latency_millis, provider_request_id, permit_date, generated_at_utc)
                VALUES
                    (:userId, :yearMonth, :fingerprint, :overview, :structure, :surplus, :watch, :provider, :model,
                     :promptVersion, :promptTokens, :completionTokens, :totalTokens, :latency, :requestId, :permitDate, :generatedAt)
                """).param("userId", permit.userId()).param("yearMonth", permit.yearMonth())
                .param("fingerprint", permit.contextFingerprint()).param("overview", report.monthlyOverview())
                .param("structure", report.expenseStructure()).param("surplus", report.surplusReasons())
                .param("watch", report.pointsToWatch()).param("provider", provenance.provider()).param("model", provenance.model())
                .param("promptVersion", provenance.promptVersion()).param("promptTokens", provenance.promptTokens())
                .param("completionTokens", provenance.completionTokens()).param("totalTokens", provenance.totalTokens())
                .param("latency", provenance.latencyMillis()).param("requestId", blankToNull(provenance.providerRequestId()))
                .param("permitDate", permit.permitDate()).param("generatedAt", timestamp(generatedAt)).update(keys);
        return keys.getKey().longValue();
    }

    private boolean reserve(long userId, LocalDate permitDate) {
        jdbc.sql("""
                INSERT INTO analysis_quota_days (user_id, permit_date, reserved_count, consumed_count)
                VALUES (:userId, :permitDate, 0, 0)
                ON DUPLICATE KEY UPDATE user_id = user_id
                """).param("userId", userId).param("permitDate", permitDate).update();
        return jdbc.sql("""
                UPDATE analysis_quota_days
                SET reserved_count = reserved_count + 1
                WHERE user_id = :userId AND permit_date = :permitDate
                  AND reserved_count + consumed_count < :limit
                """).param("userId", userId).param("permitDate", permitDate).param("limit", DAILY_SUCCESS_LIMIT).update() == 1;
    }

    private boolean release(long userId, LocalDate permitDate) {
        return jdbc.sql("""
                UPDATE analysis_quota_days
                SET reserved_count = reserved_count - 1
                WHERE user_id = :userId AND permit_date = :permitDate AND reserved_count >= 1
                """).param("userId", userId).param("permitDate", permitDate).update() == 1;
    }

    private boolean consume(long userId, LocalDate permitDate) {
        return jdbc.sql("""
                UPDATE analysis_quota_days
                SET reserved_count = reserved_count - 1, consumed_count = consumed_count + 1
                WHERE user_id = :userId AND permit_date = :permitDate AND reserved_count >= 1
                """).param("userId", userId).param("permitDate", permitDate).update() == 1;
    }

    private void lockUser(long userId) {
        jdbc.sql("""
                INSERT INTO analysis_user_locks (user_id) VALUES (:userId)
                ON DUPLICATE KEY UPDATE user_id = user_id
                """).param("userId", userId).update();
    }

    private boolean holds(GenerationRow row, GenerationPermit permit) {
        return row != null && row.id == permit.generationId() && row.fencingToken == permit.fencingToken()
                && row.userId == permit.userId() && row.yearMonth.equals(permit.yearMonth())
                && row.contextFingerprint.equals(permit.contextFingerprint()) && row.state == GenerationState.IN_PROGRESS
                && row.quotaReserved && row.permitDate.equals(permit.permitDate()) && row.leaseExpiresAt.isAfter(now());
    }

    private Optional<AnalysisSnapshot> snapshot(long userId, String yearMonth, String fingerprint, boolean earliest) {
        String order = earliest ? "ASC" : "DESC";
        return jdbc.sql("SELECT " + SNAPSHOT_COLUMNS + """
                FROM analysis_snapshots
                WHERE user_id = :userId AND analysis_month = :yearMonth AND context_fingerprint = :fingerprint
                ORDER BY id %s LIMIT 1
                """.formatted(order)).param("userId", userId).param("yearMonth", yearMonth).param("fingerprint", fingerprint)
                .query(this::snapshot).optional();
    }

    private Optional<AnalysisSnapshot> snapshotById(long id) {
        return jdbc.sql("SELECT " + SNAPSHOT_COLUMNS + " FROM analysis_snapshots WHERE id = :id")
                .param("id", id).query(this::snapshot).optional();
    }

    private AnalysisSnapshot snapshot(ResultSet rs, int row) throws SQLException {
        return new AnalysisSnapshot(rs.getLong("id"), rs.getLong("user_id"), rs.getString("analysis_month"),
                rs.getString("context_fingerprint"),
                new MonthlyAnalysisReport(rs.getString("monthly_overview"), rs.getString("expense_structure"),
                        rs.getString("surplus_reasons"), rs.getString("points_to_watch")),
                new AnalysisProvenance(rs.getString("provider"), rs.getString("model"), rs.getString("prompt_version"),
                        number(rs, "prompt_tokens"), number(rs, "completion_tokens"), number(rs, "total_tokens"),
                        rs.getLong("latency_millis"), rs.getString("provider_request_id")),
                rs.getDate("permit_date").toLocalDate(), rs.getTimestamp("generated_at_utc").toInstant());
    }

    private Optional<GenerationRow> generation(long userId, String yearMonth, String fingerprint) {
        return jdbc.sql("""
                SELECT id, user_id, analysis_month, context_fingerprint, state, fencing_token, permit_date,
                       lease_expires_at_utc, quota_reserved, snapshot_id
                FROM analysis_generations
                WHERE user_id = :userId AND analysis_month = :yearMonth AND context_fingerprint = :fingerprint
                """).param("userId", userId).param("yearMonth", yearMonth).param("fingerprint", fingerprint)
                .query(this::generation).optional();
    }

    private Optional<GenerationRow> generationById(long id) {
        return jdbc.sql("""
                SELECT id, user_id, analysis_month, context_fingerprint, state, fencing_token, permit_date,
                       lease_expires_at_utc, quota_reserved, snapshot_id
                FROM analysis_generations WHERE id = :id
                """).param("id", id).query(this::generation).optional();
    }

    private GenerationRow generation(ResultSet rs, int row) throws SQLException {
        return new GenerationRow(rs.getLong("id"), rs.getLong("user_id"), rs.getString("analysis_month"),
                rs.getString("context_fingerprint"), GenerationState.valueOf(rs.getString("state")), rs.getLong("fencing_token"),
                rs.getDate("permit_date").toLocalDate(), rs.getTimestamp("lease_expires_at_utc").toInstant(),
                rs.getInt("quota_reserved") == 1, rs.getObject("snapshot_id") == null ? null : rs.getLong("snapshot_id"));
    }

    private static TransactionTemplate transaction(PlatformTransactionManager transactionManager, boolean readOnly) {
        var template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.setReadOnly(readOnly);
        return template;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Long number(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void requireScope(String yearMonth, String contextFingerprint) {
        YearMonth.parse(yearMonth);
        if (contextFingerprint == null || contextFingerprint.isBlank() || contextFingerprint.length() > 128) {
            throw new IllegalArgumentException("context fingerprint is required");
        }
    }

    private static void requireReport(MonthlyAnalysisReport report) {
        if (report == null || blank(report.monthlyOverview()) || blank(report.expenseStructure())
                || blank(report.surplusReasons()) || blank(report.pointsToWatch())) {
            throw new IllegalArgumentException("successful snapshot requires four sections");
        }
    }

    private static void requireProvenance(AnalysisProvenance provenance) {
        if (provenance == null || blank(provenance.provider()) || blank(provenance.model()) || blank(provenance.promptVersion())
                || provenance.latencyMillis() < 0) {
            throw new IllegalArgumentException("successful snapshot requires provenance");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record GenerationRow(long id, long userId, String yearMonth, String contextFingerprint, GenerationState state,
            long fencingToken, LocalDate permitDate, Instant leaseExpiresAt, boolean quotaReserved, Long snapshotId) {
        private GenerationStatus status() {
            return new GenerationStatus(id, userId, yearMonth, contextFingerprint, state, leaseExpiresAt, permitDate, snapshotId);
        }
    }
}
