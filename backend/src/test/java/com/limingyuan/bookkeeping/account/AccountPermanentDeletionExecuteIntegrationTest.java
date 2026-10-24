package com.limingyuan.bookkeeping.account;

import com.limingyuan.bookkeeping.account.application.AccountPermanentDeletionService;
import com.limingyuan.bookkeeping.account.application.AccountPermanentDeletionService.AffectedAccountView;
import com.limingyuan.bookkeeping.account.application.AccountPermanentDeletionService.PreviewView;
import com.limingyuan.bookkeeping.account.application.CurrentBalance;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calls {@link AccountPermanentDeletionService#execute} on {@code bookkeeping_migration_test}.
 * The class does not start unless {@code S2_T10_JDBC_URL} names that schema, and it does not
 * run Flyway clean. Fixture users are {@code s2t10r-} only.
 *
 * <p>The pause and the simulated delete fault are test-side JDBC wrappers. They do not replace
 * {@code TransactionTemplate}. A second connection commits while the service transaction is open.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(
        named = "S2_T10_JDBC_URL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:3306/bookkeeping_migration_test(\\?.*)?")
class AccountPermanentDeletionExecuteIntegrationTest {
    private static final String DRILL_USER = "646230315F6472696C6C";
    private static final String DRILL_ACCOUNT = "44423031E6BC94E7BB83";

    @Autowired AccountPermanentDeletionService service;
    @Autowired CurrentBalance balances;
    @Autowired JdbcClient jdbc;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;

    private final List<Long> fixtures = new ArrayList<>();

    @DynamicPropertySource
    static void migrationDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("S2_T10_JDBC_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("S2_T10_JDBC_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("S2_T10_JDBC_PASSWORD"));
    }

    @BeforeEach
    void requireMigrationDrill() {
        ExecuteGate.clear();
        assertThat(jdbc.sql("SELECT DATABASE()").query(String.class).single())
                .isEqualTo("bookkeeping_migration_test");
        assertThat(jdbc.sql("""
                SELECT version FROM flyway_schema_history
                ORDER BY installed_rank DESC LIMIT 1
                """).query(String.class).single()).isEqualTo("16");
        assertDrillUntouched();
    }

    @AfterEach
    void cleanupFixtures() {
        ExecuteGate.clear();
        SecurityContextHolder.clearContext();
        for (long id : fixtures) {
            String username = jdbc.sql("SELECT username FROM users WHERE id = :id")
                    .param("id", id).query(String.class).optional().orElse("");
            if (!username.startsWith("s2t10r-")) {
                throw new IllegalStateException("refusing to delete user " + id);
            }
            jdbc.sql("DELETE FROM analysis_generations WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM analysis_snapshots WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM analysis_quota_days WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM analysis_user_locks WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM idempotency_records WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM transfers WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM balance_adjustments WHERE user_id = :id").param("id", id).update();
            jdbc.sql("""
                    DELETE FROM transaction_tags
                    WHERE transaction_id IN (SELECT id FROM transactions WHERE user_id = :id)
                    """).param("id", id).update();
            jdbc.sql("DELETE FROM transactions WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM accounts WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
            users.deleteById(id);
        }
        fixtures.clear();
        assertDrillUntouched();
    }

    @Test
    void executeDeletesArchivedAccountAndKeepsCounterpartyAndOtherUsers() {
        Books books = books();
        authenticate(books.userId());
        PreviewView preview = service.preview(books.archivedId());
        assertThat(preview.transactionCount()).isEqualTo(1);
        assertThat(preview.adjustmentCount()).isEqualTo(1);
        assertThat(preview.transferCount()).isEqualTo(1);
        assertThat(preview.analysisSnapshotCount()).isEqualTo(1);
        assertThat(preview.analysisGenerationCount()).isEqualTo(1);
        assertThat(preview.affectedAccounts()).extracting(AffectedAccountView::accountId)
                .containsExactly(books.counterpartyId());
        AffectedAccountView impact = preview.affectedAccounts().get(0);
        assertThat(new BigDecimal(impact.balanceBefore())).isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(impact.balanceAfter())).isEqualByComparingTo("80.00");
        assertThat(balances.of(books.counterpartyId())).isEqualByComparingTo("100.00");
        assertThat(balances.of(books.keptId())).isEqualByComparingTo("6.00");

        service.execute(books.archivedId(), preview.previewToken());

        assertAbsent("accounts", books.archivedId());
        assertAbsent("transactions", books.transactionId());
        assertAbsent("balance_adjustments", books.adjustmentId());
        assertAbsent("transfers", books.transferId());
        assertAbsent("analysis_snapshots", books.snapshotId());
        assertAbsent("analysis_generations", books.generationId());
        assertExists("accounts", books.counterpartyId());
        assertExists("accounts", books.keptId());
        assertExists("transactions", books.keptTransactionId());
        assertThat(balances.of(books.counterpartyId())).isEqualByComparingTo("80.00");
        assertThat(balances.of(books.keptId())).isEqualByComparingTo("6.00");
        assertThat(count("analysis_quota_days", books.userId())).isEqualTo(1);
        assertThat(count("analysis_user_locks", books.userId())).isEqualTo(1);
        authenticate(books.otherUserId());
        assertExists("accounts", books.otherAccountId());
        assertExists("transactions", books.otherTransactionId());
        assertExists("analysis_snapshots", books.otherSnapshotId());
        assertThat(balances.of(books.otherAccountId())).isEqualByComparingTo("2.00");
        assertThat(count("analysis_quota_days", books.otherUserId())).isEqualTo(1);
    }

    @Test
    void executeAfterNewTransactionReturnsPreviewStaleAndDeletesNothing() throws Exception {
        Books books = books();
        authenticate(books.userId());
        PreviewView preview = service.preview(books.archivedId());
        long extraId = insertExpense(books.userId(), books.archivedId(), 9, new BigDecimal("3.00"));

        ApiFailure failure = executeFailure(books.userId(), books.archivedId(), preview.previewToken());

        assertThat(failure.status()).isEqualTo(409);
        assertThat(failure.code()).isEqualTo("PREVIEW_STALE");
        assertBookIntact(books);
        assertExists("transactions", extraId);
        assertThat(balances.of(books.counterpartyId())).isEqualByComparingTo("100.00");
    }

    @Test
    void transactionCommittedDuringExecuteReturnsPreviewStaleAndKeepsTheNewRow() throws Exception {
        Books books = books();
        authenticate(books.userId());
        String token = service.preview(books.archivedId()).previewToken();
        var trigger = new AtomicReference<String>();
        var inserted = new AtomicLong();
        ApiFailure failure = executeOnOtherThread(books.userId(), () -> {
            ExecuteGate.afterPlainAccountRead(sql -> {
                trigger.set(sql);
                inserted.set(insertExpense(books.userId(), books.archivedId(), 9, new BigDecimal("3.00")));
            });
            service.execute(books.archivedId(), token);
        });

        assertThat(trigger.get()).containsIgnoringCase("from accounts");
        assertThat(trigger.get()).doesNotContainIgnoringCase("for update");
        assertThat(inserted.get()).isPositive();
        assertThat(failure.code()).isEqualTo("PREVIEW_STALE");
        assertBookIntact(books);
        assertExists("transactions", inserted.get());
        authenticate(books.userId());
        assertThat(balances.of(books.counterpartyId())).isEqualByComparingTo("100.00");
    }

    @Test
    void nullAccountGenerationCommittedDuringExecuteReturnsConflictAndDeletesNothing() throws Exception {
        Books books = books();
        authenticate(books.userId());
        String token = service.preview(books.archivedId()).previewToken();
        var trigger = new AtomicReference<String>();
        var inserted = new AtomicLong();
        ApiFailure failure = executeOnOtherThread(books.userId(), () -> {
            ExecuteGate.afterPlainAccountRead(sql -> {
                trigger.set(sql);
                inserted.set(insertNullAccountGeneration(books.userId(), books.snapshotId()));
            });
            service.execute(books.archivedId(), token);
        });

        assertThat(trigger.get()).containsIgnoringCase("from accounts");
        assertThat(trigger.get()).doesNotContainIgnoringCase("for update");
        assertThat(inserted.get()).isPositive();
        assertThat(failure.status()).isEqualTo(409);
        assertThat(failure.code()).isEqualTo("ANALYSIS_REFERENCE_CONFLICT");
        assertBookIntact(books);
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM analysis_generations
                WHERE id = :id AND account_id IS NULL AND snapshot_id = :snapshotId
                """).param("id", inserted.get()).param("snapshotId", books.snapshotId())
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void simulatedDeleteFaultRollsBackTheRealTransaction() {
        int seen = ExecuteGate.statements.get();
        jdbc.sql("SELECT 1").query(Integer.class).single();
        assertThat(ExecuteGate.statements.get()).isGreaterThan(seen);
        Books books = books();
        authenticate(books.userId());
        String token = service.preview(books.archivedId()).previewToken();
        ExecuteGate.failOnTransactionDelete();
        ApiFailure failure;
        try {
            service.execute(books.archivedId(), token);
            throw new AssertionError("execute returned without the simulated fault");
        } catch (ApiFailure thrown) {
            failure = thrown;
        } finally {
            ExecuteGate.clear();
        }

        assertThat(ExecuteGate.faultSql.get()).containsIgnoringCase("transactions");
        assertThat(failure.status()).isEqualTo(409);
        assertThat(failure.code()).isEqualTo("PREVIEW_STALE");
        assertBookIntact(books);
        authenticate(books.userId());
        assertThat(balances.of(books.counterpartyId())).isEqualByComparingTo("100.00");
        assertThat(balances.of(books.archivedId())).isEqualByComparingTo("24.00");
    }

    private ApiFailure executeFailure(long userId, long accountId, String token) {
        authenticate(userId);
        try {
            service.execute(accountId, token);
            throw new AssertionError("execute returned without an error");
        } catch (ApiFailure failure) {
            return failure;
        }
    }

    private ApiFailure executeOnOtherThread(long userId, Runnable execute) throws Exception {
        var error = new AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try {
                authenticate(userId);
                execute.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                ExecuteGate.clear();
                SecurityContextHolder.clearContext();
            }
        }, "s2t10r-execute");
        thread.start();
        thread.join(20_000);
        if (thread.isAlive()) {
            thread.interrupt();
            throw new IllegalStateException("execute did not finish");
        }
        assertThat(error.get()).isInstanceOf(ApiFailure.class);
        return (ApiFailure) error.get();
    }

    private void assertBookIntact(Books books) {
        assertExists("accounts", books.archivedId());
        assertExists("accounts", books.counterpartyId());
        assertExists("accounts", books.keptId());
        assertExists("transactions", books.transactionId());
        assertExists("transactions", books.keptTransactionId());
        assertExists("balance_adjustments", books.adjustmentId());
        assertExists("transfers", books.transferId());
        assertExists("analysis_snapshots", books.snapshotId());
        assertExists("analysis_generations", books.generationId());
        assertExists("accounts", books.otherAccountId());
        assertExists("transactions", books.otherTransactionId());
        assertExists("analysis_snapshots", books.otherSnapshotId());
        assertThat(count("analysis_quota_days", books.userId())).isEqualTo(1);
        assertThat(count("analysis_user_locks", books.userId())).isEqualTo(1);
        assertThat(count("analysis_quota_days", books.otherUserId())).isEqualTo(1);
    }

    private Books books() {
        long userId = newUser();
        long archivedId = insertAccount(userId, "归档", "50.00", "ARCHIVED");
        long counterpartyId = insertAccount(userId, "对手", "80.00", "ACTIVE");
        long keptId = insertAccount(userId, "保留", "7.00", "ACTIVE");
        long transactionId = insertExpense(userId, archivedId, 1, new BigDecimal("10.00"));
        long adjustmentId = insertAdjustment(userId, archivedId);
        long transferId = insertTransfer(userId, archivedId, counterpartyId);
        long snapshotId = insertSnapshot(userId, archivedId);
        long generationId = insertGeneration(userId, archivedId, snapshotId);
        jdbc.sql("""
                INSERT INTO analysis_quota_days (user_id, permit_date, reserved_count, consumed_count)
                VALUES (:userId, '2026-01-01', 0, 1)
                """).param("userId", userId).update();
        jdbc.sql("INSERT INTO analysis_user_locks (user_id) VALUES (:userId)").param("userId", userId).update();
        long keptTransactionId = insertExpense(userId, keptId, 1, new BigDecimal("1.00"));

        long otherUserId = newUser();
        long otherAccountId = insertAccount(otherUserId, "他人", "3.00", "ACTIVE");
        long otherTransactionId = insertExpense(otherUserId, otherAccountId, 1, new BigDecimal("1.00"));
        long otherSnapshotId = insertSnapshot(otherUserId, otherAccountId);
        jdbc.sql("""
                INSERT INTO analysis_quota_days (user_id, permit_date, reserved_count, consumed_count)
                VALUES (:userId, '2026-01-01', 0, 1)
                """).param("userId", otherUserId).update();
        return new Books(userId, archivedId, counterpartyId, keptId, transactionId, adjustmentId, transferId,
                snapshotId, generationId, keptTransactionId, otherUserId, otherAccountId, otherTransactionId,
                otherSnapshotId);
    }

    private long newUser() {
        String username = "s2t10r-" + UUID.randomUUID();
        bootstrap.ensureUser(username, tokens.generate());
        long id = users.findByUsername(username).orElseThrow().getId();
        assertThat(id).isNotEqualTo(1L);
        assertThat(username).startsWith("s2t10r-");
        fixtures.add(id);
        return id;
    }

    private long insertAccount(long userId, String name, String opening, String lifecycle) {
        return insert("""
                INSERT INTO accounts (
                    user_id, name, kind, currency, opening_balance, created_time,
                    last_first_save_order, updated_at_utc, lifecycle)
                VALUES (:userId, :name, 'NORMAL', 'CNY', :opening, '2026-01-01 00:00:00',
                    9, UTC_TIMESTAMP(6), :lifecycle)
                """, Map.of("userId", userId, "name", name, "opening", new BigDecimal(opening),
                "lifecycle", lifecycle));
    }

    private long insertExpense(long userId, long accountId, long order, BigDecimal amount) {
        try (Connection connection = openMigration()) {
            refuseForeignUser(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO transactions (
                        user_id, account_id, type, amount, category_id, business_time,
                        first_saved_at_utc, first_save_order, updated_at_utc)
                    VALUES (?, ?, 'EXPENSE', ?, NULL, '2026-01-15 10:00:00',
                        UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6))
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, userId);
                statement.setLong(2, accountId);
                statement.setBigDecimal(3, amount);
                statement.setLong(4, order);
                statement.executeUpdate();
                return generatedKey(statement);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("fixture expense insert failed", ex);
        }
    }

    private long insertAdjustment(long userId, long accountId) {
        return insert("""
                INSERT INTO balance_adjustments (
                    user_id, account_id, adjustment_amount, business_time,
                    first_saved_at_utc, first_save_order, remark)
                VALUES (:userId, :accountId, 4.00, '2026-01-15 11:00:00',
                    UTC_TIMESTAMP(6), 2, 'fixture')
                """, Map.of("userId", userId, "accountId", accountId));
    }

    private long insertTransfer(long userId, long fromAccountId, long toAccountId) {
        return insert("""
                INSERT INTO transfers (
                    user_id, from_account_id, to_account_id, amount,
                    from_business_time, to_business_time, first_saved_at_utc,
                    first_save_order, updated_at_utc)
                VALUES (:userId, :fromAccountId, :toAccountId, 20.00,
                    '2026-01-16 10:00:00', '2026-01-16 10:00:00', UTC_TIMESTAMP(6),
                    3, UTC_TIMESTAMP(6))
                """, Map.of("userId", userId, "fromAccountId", fromAccountId, "toAccountId", toAccountId));
    }

    private long insertSnapshot(long userId, long accountId) {
        return insert("""
                INSERT INTO analysis_snapshots (
                    user_id, account_id, analysis_month, context_fingerprint,
                    monthly_overview, expense_structure, surplus_reasons, points_to_watch,
                    provider, model, prompt_version, latency_millis, permit_date, generated_at_utc)
                VALUES (:userId, :accountId, '2026-01', :fingerprint, 'm', 'e', 's', 'p',
                    'fixture', 'fixture', 'v', 1, '2026-01-01', UTC_TIMESTAMP(6))
                """, Map.of("userId", userId, "accountId", accountId, "fingerprint", UUID.randomUUID().toString()));
    }

    private long insertGeneration(long userId, long accountId, long snapshotId) {
        return insert("""
                INSERT INTO analysis_generations (
                    user_id, account_id, analysis_month, context_fingerprint, state, fencing_token,
                    permit_date, lease_expires_at_utc, quota_reserved, snapshot_id,
                    created_at_utc, updated_at_utc)
                VALUES (:userId, :accountId, '2026-01', :fingerprint, 'SUCCEEDED', 1,
                    '2026-01-01', UTC_TIMESTAMP(6), 0, :snapshotId, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, Map.of("userId", userId, "accountId", accountId, "fingerprint", UUID.randomUUID().toString(),
                "snapshotId", snapshotId));
    }

    private long insertNullAccountGeneration(long userId, long snapshotId) {
        try (Connection connection = openMigration()) {
            refuseForeignUser(connection, userId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO analysis_generations (
                        user_id, account_id, analysis_month, context_fingerprint, state, fencing_token,
                        permit_date, lease_expires_at_utc, quota_reserved, snapshot_id,
                        created_at_utc, updated_at_utc)
                    VALUES (?, NULL, '2026-01', ?, 'SUCCEEDED', 1, '2026-01-01',
                        UTC_TIMESTAMP(6), 0, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, userId);
                statement.setString(2, UUID.randomUUID().toString());
                statement.setLong(3, snapshotId);
                statement.executeUpdate();
                return generatedKey(statement);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("fixture generation insert failed", ex);
        }
    }

    private long insert(String sql, Map<String, ?> params) {
        var keys = new GeneratedKeyHolder();
        var statement = jdbc.sql(sql);
        for (var entry : params.entrySet()) {
            statement = statement.param(entry.getKey(), entry.getValue());
        }
        statement.update(keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("insert did not return a key");
        }
        return key.longValue();
    }

    private void assertExists(String table, long id) {
        assertThat(countById(table, id)).isEqualTo(1);
    }

    private void assertAbsent(String table, long id) {
        assertThat(countById(table, id)).isZero();
    }

    private long countById(String table, long id) {
        String sql = switch (table) {
            case "accounts" -> "SELECT COUNT(*) FROM accounts WHERE id = :id";
            case "transactions" -> "SELECT COUNT(*) FROM transactions WHERE id = :id";
            case "balance_adjustments" -> "SELECT COUNT(*) FROM balance_adjustments WHERE id = :id";
            case "transfers" -> "SELECT COUNT(*) FROM transfers WHERE id = :id";
            case "analysis_snapshots" -> "SELECT COUNT(*) FROM analysis_snapshots WHERE id = :id";
            case "analysis_generations" -> "SELECT COUNT(*) FROM analysis_generations WHERE id = :id";
            default -> throw new IllegalArgumentException(table);
        };
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    private long count(String table, long userId) {
        String sql = switch (table) {
            case "analysis_quota_days" -> "SELECT COUNT(*) FROM analysis_quota_days WHERE user_id = :id";
            case "analysis_user_locks" -> "SELECT COUNT(*) FROM analysis_user_locks WHERE user_id = :id";
            default -> throw new IllegalArgumentException(table);
        };
        return jdbc.sql(sql).param("id", userId).query(Long.class).single();
    }

    private void assertDrillUntouched() {
        assertThat(jdbc.sql("SELECT HEX(username) FROM users WHERE username = 'db01_drill'")
                .query(String.class).single()).isEqualTo(DRILL_USER);
        assertThat(jdbc.sql("""
                SELECT HEX(name) FROM accounts WHERE user_id = (
                    SELECT id FROM users WHERE username = 'db01_drill')
                """).query(String.class).single()).isEqualTo(DRILL_ACCOUNT);
        assertThat(jdbc.sql("""
                SELECT lifecycle FROM accounts WHERE user_id = (
                    SELECT id FROM users WHERE username = 'db01_drill')
                """).query(String.class).single()).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("""
                SELECT opening_balance FROM accounts WHERE user_id = (
                    SELECT id FROM users WHERE username = 'db01_drill')
                """).query(BigDecimal.class).single()).isEqualByComparingTo("12.50");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM transfers WHERE user_id = (
                    SELECT id FROM users WHERE username = 'db01_drill')
                """).query(Long.class).single()).isZero();
    }

    private void authenticate(long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new TrustedUserContext.Identity(userId, 0L), null, List.of()));
    }

    private static Connection openMigration() throws SQLException {
        String url = System.getenv("S2_T10_JDBC_URL");
        if (url == null || !(url.endsWith("/bookkeeping_migration_test")
                || url.contains("/bookkeeping_migration_test?"))) {
            throw new IllegalStateException("S2_T10_JDBC_URL must name bookkeeping_migration_test");
        }
        Connection connection = DriverManager.getConnection(url, System.getenv("S2_T10_JDBC_USER"),
                System.getenv("S2_T10_JDBC_PASSWORD"));
        try (PreparedStatement statement = connection.prepareStatement("SELECT DATABASE()");
                var rows = statement.executeQuery()) {
            rows.next();
            if (!"bookkeeping_migration_test".equals(rows.getString(1))) {
                connection.close();
                throw new IllegalStateException("second connection is not the migration schema");
            }
        }
        connection.setAutoCommit(true);
        return connection;
    }

    private static void refuseForeignUser(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT username FROM users WHERE id = ?")) {
            statement.setLong(1, userId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !rows.getString(1).startsWith("s2t10r-")) {
                    throw new IllegalStateException("refusing to write fixture user " + userId);
                }
            }
        }
    }

    private static long generatedKey(PreparedStatement statement) throws SQLException {
        try (var keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("insert did not return a key");
            }
            return keys.getLong(1);
        }
    }

    private record Books(long userId, long archivedId, long counterpartyId, long keptId, long transactionId,
            long adjustmentId, long transferId, long snapshotId, long generationId, long keptTransactionId,
            long otherUserId, long otherAccountId, long otherTransactionId, long otherSnapshotId) { }

    @TestConfiguration
    static class FaultConfiguration {
        @Bean
        static BeanPostProcessor pausingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource source && !(bean instanceof PausingDataSource)) {
                        return new PausingDataSource(source);
                    }
                    return bean;
                }
            };
        }
    }

    static final class PausingDataSource extends DelegatingDataSource {
        PausingDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return ExecuteGate.wrap(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return ExecuteGate.wrap(super.getConnection(username, password));
        }
    }

    static final class ExecuteGate {
        static final AtomicBoolean failTransactionDelete = new AtomicBoolean();
        static final AtomicReference<String> faultSql = new AtomicReference<>();
        static final java.util.concurrent.atomic.AtomicInteger statements =
                new java.util.concurrent.atomic.AtomicInteger();
        private static final ThreadLocal<Consumer<String>> AFTER_ACCOUNT_READ = new ThreadLocal<>();

        static void afterPlainAccountRead(Consumer<String> action) {
            AFTER_ACCOUNT_READ.set(action);
        }

        static void failOnTransactionDelete() {
            faultSql.set(null);
            failTransactionDelete.set(true);
        }

        static void clear() {
            AFTER_ACCOUNT_READ.remove();
            failTransactionDelete.set(false);
        }

        static Connection wrap(Connection delegate) {
            var self = new Connection[1];
            self[0] = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("equals")) {
                            return proxy == args[0];
                        }
                        if (method.getName().equals("hashCode")) {
                            return System.identityHashCode(proxy);
                        }
                        if (method.getName().equals("unwrap")) {
                            Class<?> type = (Class<?>) args[0];
                            return type.isInstance(proxy) ? proxy : call(method, delegate, args);
                        }
                        if (method.getName().equals("isWrapperFor")) {
                            Class<?> type = (Class<?>) args[0];
                            return type.isInstance(proxy) || (Boolean) call(method, delegate, args);
                        }
                        if (method.getName().equals("prepareStatement") || method.getName().equals("prepareCall")
                                || method.getName().equals("createStatement")) {
                            Object statement = call(method, delegate, args);
                            if (statement == null) {
                                return null;
                            }
                            statements.incrementAndGet();
                            return wrapStatement(statement, firstString(args), self[0]);
                        }
                        return call(method, delegate, args);
                    });
            return self[0];
        }

        private static Object wrapStatement(Object statement, String sql, Connection connection) {
            Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
                    : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                    new StatementHandler(statement, sql, connection));
        }

        private static final class StatementHandler implements InvocationHandler {
            private final Object delegate;
            private final String preparedSql;
            private final Connection connection;

            private StatementHandler(Object delegate, String preparedSql, Connection connection) {
                this.delegate = delegate;
                this.preparedSql = preparedSql;
                this.connection = connection;
            }

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if (name.equals("equals")) {
                    return proxy == args[0];
                }
                if (name.equals("hashCode")) {
                    return System.identityHashCode(proxy);
                }
                if (name.equals("getConnection")) {
                    return connection;
                }
                if (name.equals("unwrap")) {
                    Class<?> type = (Class<?>) args[0];
                    return type.isInstance(proxy) ? proxy : call(method, delegate, args);
                }
                String sql = firstString(args) != null ? firstString(args) : preparedSql;
                if (name.startsWith("execute") && failTransactionDelete.get() && isTransactionDelete(sql)) {
                    failTransactionDelete.set(false);
                    faultSql.set(sql);
                    throw new DataIntegrityViolationException("simulated transaction delete failure");
                }
                Object result = call(method, delegate, args);
                if (isPlainAccountRead(name, sql)) {
                    Consumer<String> action = AFTER_ACCOUNT_READ.get();
                    if (action != null) {
                        AFTER_ACCOUNT_READ.remove();
                        action.accept(sql);
                    }
                }
                return result;
            }
        }

        private static boolean isPlainAccountRead(String method, String sql) {
            if (sql == null || !(method.equals("executeQuery") || method.equals("execute"))) {
                return false;
            }
            String normalized = sql.toLowerCase().replaceAll("\\s+", " ");
            return normalized.contains("from accounts") && !normalized.contains("for update");
        }

        private static boolean isTransactionDelete(String sql) {
            if (sql == null) {
                return false;
            }
            String normalized = sql.toLowerCase().replaceAll("\\s+", " ");
            return normalized.contains("delete") && normalized.contains("from transactions")
                    && !normalized.contains("transaction_tags");
        }

        private static String firstString(Object[] args) {
            if (args == null) {
                return null;
            }
            for (Object arg : args) {
                if (arg instanceof String sql) {
                    return sql;
                }
            }
            return null;
        }

        private static Object call(Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }
    }
}
