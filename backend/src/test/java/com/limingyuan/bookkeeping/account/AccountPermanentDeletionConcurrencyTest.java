package com.limingyuan.bookkeeping.account;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two-connection interleaving for the next T10 retest. Not a T09R result.
 *
 * <p>The class runs only when {@code S2_T10_JDBC_URL} names {@code bookkeeping_migration_test}.
 * It does not run Flyway. {@code open()} refuses every other database name.
 *
 * <p>Both connections use {@code REPEATABLE READ}. A plain {@code SELECT} fixes A's snapshot.
 * A later plain {@code SELECT} stays on that snapshot. {@code SELECT ... FOR UPDATE} is the
 * current read the service uses after the account lock.
 */
@EnabledIfEnvironmentVariable(
        named = "S2_T10_JDBC_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:3306/bookkeeping_migration_test(\\?.*)?")
class AccountPermanentDeletionConcurrencyTest {
    @Test
    void transactionCommittedBeforeAccountLockIsSeenByForUpdateAndLeftInPlace() throws Exception {
        long userId = seedUser();
        try {
            long accountId = seedAccount(userId);
            long originalId = seedTransaction(userId, accountId, 1, new BigDecimal("1.00"));
            try (Connection a = open(); Connection b = open()) {
                repeatableRead(a);
                repeatableRead(b);
                // A opens its snapshot before B commits.
                queryLongs(a, """
                        SELECT id FROM transactions
                        WHERE account_id = ? AND user_id = ?
                        ORDER BY id
                        """, accountId, userId);
                lockAccount(b, accountId, userId);
                long insertedId = seedTransactionOn(b, userId, accountId, 2, new BigDecimal("2.00"));
                b.commit();
                List<Long> plainReread = queryLongs(a, """
                        SELECT id FROM transactions
                        WHERE account_id = ? AND user_id = ?
                        ORDER BY id
                        """, accountId, userId);
                assertThat(plainReread).containsExactly(originalId);
                // A locks the account, then current-reads. The new id must be visible.
                lockAccount(a, accountId, userId);
                List<Long> lockedIds = queryLongs(a, """
                        SELECT id FROM transactions
                        WHERE account_id = ? AND user_id = ?
                        ORDER BY id
                        FOR UPDATE
                        """, accountId, userId);
                BigDecimal lockedSum = queryDecimal(a, """
                        SELECT COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount ELSE -amount END), 0)
                        FROM transactions
                        WHERE account_id = ? AND user_id = ?
                        FOR UPDATE
                        """, accountId, userId);
                assertThat(lockedIds).containsExactly(originalId, insertedId);
                assertThat(lockedSum).isEqualByComparingTo("-3.00");
                // A must not delete. Rollback releases the gap locks.
                a.rollback();
            }
            assertThat(count("SELECT COUNT(*) FROM accounts WHERE id = " + accountId)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM transactions WHERE user_id = " + userId)).isEqualTo(2);
        } finally {
            deleteUser(userId);
        }
    }

    @Test
    void analysisRowCommittedAfterPlainReadIsSeenByForUpdateAndLeftInPlace() throws Exception {
        long userId = seedUser();
        try {
            long accountId = seedAccount(userId);
            try (Connection a = open(); Connection b = open()) {
                repeatableRead(a);
                repeatableRead(b);
                queryLongs(a, """
                        SELECT id FROM analysis_generations
                        WHERE user_id = ? AND account_id = ?
                        ORDER BY id
                        """, userId, accountId);
                // B commits before A locks the account. A generation with this account_id
                // would wait on the account row FK if A already held FOR UPDATE, so that
                // overlap is not a committed-insert window. The window is this one.
                long generationId = seedGeneration(b, userId, accountId);
                b.commit();
                List<Long> plainReread = queryLongs(a, """
                        SELECT id FROM analysis_generations
                        WHERE user_id = ? AND account_id = ?
                        ORDER BY id
                        """, userId, accountId);
                assertThat(plainReread).isEmpty();
                lockAccount(a, accountId, userId);
                List<Long> lockedIds = queryLongs(a, """
                        SELECT id FROM analysis_generations
                        WHERE user_id = ? AND account_id = ?
                        ORDER BY id
                        FOR UPDATE
                        """, userId, accountId);
                assertThat(lockedIds).containsExactly(generationId);
                a.rollback();
            }
            assertThat(count("SELECT COUNT(*) FROM analysis_generations WHERE user_id = " + userId)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM accounts WHERE user_id = " + userId)).isEqualTo(1);
        } finally {
            deleteUser(userId);
        }
    }

    @Test
    void nullAccountGenerationCommittedAfterPlainReadIsLockedAndNotDeleted() throws Exception {
        long userId = seedUser();
        try {
            long accountId = seedAccount(userId);
            long snapshotId = seedSnapshot(userId, accountId);
            try (Connection a = open(); Connection b = open()) {
                repeatableRead(a);
                repeatableRead(b);
                queryLongs(a, """
                        SELECT g.id
                        FROM analysis_generations g
                        WHERE g.snapshot_id = ?
                          AND (
                            g.user_id <> ?
                            OR g.account_id IS NULL
                            OR g.account_id <> ?
                          )
                        """, snapshotId, userId, accountId);
                lockAccount(a, accountId, userId);
                long generationId = seedNullAccountGeneration(b, userId, snapshotId);
                b.commit();
                List<Long> plainReread = queryLongs(a, """
                        SELECT g.id
                        FROM analysis_generations g
                        WHERE g.snapshot_id = ?
                          AND (
                            g.user_id <> ?
                            OR g.account_id IS NULL
                            OR g.account_id <> ?
                          )
                        """, snapshotId, userId, accountId);
                assertThat(plainReread).isEmpty();
                List<Long> lockedIds = queryLongs(a, """
                        SELECT g.id
                        FROM analysis_generations g
                        WHERE g.snapshot_id = ?
                          AND (
                            g.user_id <> ?
                            OR g.account_id IS NULL
                            OR g.account_id <> ?
                          )
                        FOR UPDATE
                        """, snapshotId, userId, accountId);
                assertThat(lockedIds).containsExactly(generationId);
                a.rollback();
            }
            assertThat(count("SELECT COUNT(*) FROM analysis_generations WHERE user_id = " + userId)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM analysis_snapshots WHERE user_id = " + userId)).isEqualTo(1);
        } finally {
            deleteUser(userId);
        }
    }

    private static void repeatableRead(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    }

    private static void lockAccount(Connection connection, long accountId, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM accounts WHERE id = ? AND user_id = ? FOR UPDATE")) {
            statement.setLong(1, accountId);
            statement.setLong(2, userId);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }
        }
    }

    private static long seedUser() throws SQLException {
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO users (username, password_hash, created_at_utc, updated_at_utc)
                        VALUES (?, 'not-a-secret', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                        """, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "s2-t09r-" + UUID.randomUUID());
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static long seedAccount(long userId) throws SQLException {
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO accounts (
                            user_id, name, kind, currency, opening_balance, created_time,
                            last_first_save_order, updated_at_utc, lifecycle)
                        VALUES (?, '归档', 'NORMAL', 'CNY', 10.00, '2026-01-01 00:00:00',
                            2, UTC_TIMESTAMP(6), 'ARCHIVED')
                        """, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static long seedTransaction(long userId, long accountId, long order, BigDecimal amount) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(true);
            return seedTransactionOn(connection, userId, accountId, order, amount);
        }
    }

    private static long seedTransactionOn(Connection connection, long userId, long accountId, long order,
            BigDecimal amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transactions (
                    user_id, account_id, type, amount, category_id, business_time,
                    first_saved_at_utc, first_save_order, updated_at_utc)
                VALUES (?, ?, 'EXPENSE', ?, NULL, '2026-01-02 00:00:00',
                    UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6))
                """, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.setLong(2, accountId);
            statement.setBigDecimal(3, amount);
            statement.setLong(4, order);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static long seedSnapshot(long userId, long accountId) throws SQLException {
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO analysis_snapshots (
                            user_id, account_id, analysis_month, context_fingerprint,
                            monthly_overview, expense_structure, surplus_reasons, points_to_watch,
                            provider, model, prompt_version, latency_millis, permit_date, generated_at_utc)
                        VALUES (?, ?, '2026-01', ?, 'm', 'e', 's', 'p', 'p', 'm', 'v', 1,
                            '2026-01-01', UTC_TIMESTAMP(6))
                        """, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.setLong(2, accountId);
            statement.setString(3, UUID.randomUUID().toString());
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static long seedGeneration(Connection connection, long userId, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO analysis_generations (
                    user_id, account_id, analysis_month, context_fingerprint, state, fencing_token,
                    permit_date, lease_expires_at_utc, quota_reserved, snapshot_id,
                    created_at_utc, updated_at_utc)
                VALUES (?, ?, '2026-01', ?, 'SUCCEEDED', 1, '2026-01-01',
                    UTC_TIMESTAMP(6), 0, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.setLong(2, accountId);
            statement.setString(3, UUID.randomUUID().toString());
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static long seedNullAccountGeneration(Connection connection, long userId, long snapshotId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO analysis_generations (
                    user_id, account_id, analysis_month, context_fingerprint, state, fencing_token,
                    permit_date, lease_expires_at_utc, quota_reserved, snapshot_id,
                    created_at_utc, updated_at_utc)
                VALUES (?, NULL, '2026-01', ?, 'SUCCEEDED', 1, '2026-01-01',
                    UTC_TIMESTAMP(6), 0, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, userId);
            statement.setString(2, UUID.randomUUID().toString());
            statement.setLong(3, snapshotId);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static void deleteUser(long userId) throws SQLException {
        try (Connection connection = open()) {
            execute(connection, "DELETE FROM analysis_generations WHERE user_id = " + userId);
            execute(connection, "DELETE FROM analysis_snapshots WHERE user_id = " + userId);
            execute(connection, "DELETE FROM transactions WHERE user_id = " + userId);
            execute(connection, "DELETE FROM accounts WHERE user_id = " + userId);
            execute(connection, "DELETE FROM users WHERE id = " + userId);
        }
    }

    private static int count(String sql) throws SQLException {
        try (Connection connection = open();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static List<Long> queryLongs(Connection connection, String sql, long first, long second) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, first);
            statement.setLong(2, second);
            try (ResultSet rows = statement.executeQuery()) {
                var ids = new ArrayList<Long>();
                while (rows.next()) {
                    ids.add(rows.getLong(1));
                }
                return ids;
            }
        }
    }

    private static List<Long> queryLongs(Connection connection, String sql, long first, long second, long third)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, first);
            statement.setLong(2, second);
            statement.setLong(3, third);
            try (ResultSet rows = statement.executeQuery()) {
                var ids = new ArrayList<Long>();
                while (rows.next()) {
                    ids.add(rows.getLong(1));
                }
                return ids;
            }
        }
    }

    private static BigDecimal queryDecimal(Connection connection, String sql, long first, long second)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, first);
            statement.setLong(2, second);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBigDecimal(1);
            }
        }
    }

    private static long generatedKey(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static Connection open() throws SQLException {
        String url = System.getenv("S2_T10_JDBC_URL");
        if (url == null || !(url.endsWith("/bookkeeping_migration_test")
                || url.contains("/bookkeeping_migration_test?"))) {
            throw new IllegalStateException("S2_T10_JDBC_URL must name bookkeeping_migration_test");
        }
        return DriverManager.getConnection(url, System.getenv("S2_T10_JDBC_USER"),
                System.getenv("S2_T10_JDBC_PASSWORD"));
    }
}
