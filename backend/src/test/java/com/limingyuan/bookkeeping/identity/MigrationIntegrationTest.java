package com.limingyuan.bookkeeping.identity;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MigrationIntegrationTest {
    @Test void migratesCleanMySqlSchemaAndValidatesOnSecondRun() throws Exception {
        String url = System.getenv().getOrDefault("MIGRATION_TEST_DB_URL", "jdbc:mysql://localhost:3306/bookkeeping_migration_test");
        String username = System.getenv("TEST_DB_USERNAME");
        String password = System.getenv("TEST_DB_PASSWORD");
        MigrationSchemaGuard.requireDisposableSchema(url);
        // This deliberately disposable schema is separate from both development and ordinary API fixtures.
        try (var connection = DriverManager.getConnection(url, username, password)) {
            assertThat(connection.getCatalog()).isEqualTo(MigrationSchemaGuard.databaseName(url));
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(8);
            assertThat(connection.getMetaData().getDatabaseMinorVersion()).isEqualTo(4);
        }
        var flyway = Flyway.configure().dataSource(url, username, password)
                .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(false).load();
        try {
            flyway.clean();
            var throughV9 = Flyway.configure().dataSource(url, username, password)
                    .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(false).target("9").load();
            assertThat(throughV9.migrate().migrationsExecuted).isEqualTo(9);
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO users (username, password_hash, created_at_utc, updated_at_utc)
                        VALUES ('v10-upgrade', 'hash', '2026-09-01 00:00:00.000000', '2026-09-01 00:00:00.000000')
                        """);
                statement.executeUpdate("""
                        INSERT INTO accounts (user_id, name, currency, opening_balance, created_time, updated_at_utc)
                        SELECT id, '微信', 'CNY', 100.00, '2020-01-01 00:00:00', '2026-09-01 00:00:00.000000' FROM users WHERE username = 'v10-upgrade'
                        """);
                statement.executeUpdate("""
                        INSERT INTO categories (user_id, type, name, created_at_utc)
                        SELECT id, 'EXPENSE', '购物', '2026-09-01 00:00:00.000000' FROM users WHERE username = 'v10-upgrade'
                        """);
                statement.executeUpdate("""
                        INSERT INTO transactions (user_id, account_id, type, amount, name, category_id, business_time,
                            first_saved_at_utc, first_save_order, updated_at_utc)
                        SELECT u.id, a.id, 'EXPENSE', 12.00, '升级前', c.id, '2026-09-02 08:00:00',
                            '2026-09-02 00:00:00.000000', 1, '2026-09-02 00:00:00.000000'
                        FROM users u
                        JOIN accounts a ON a.user_id = u.id
                        JOIN categories c ON c.user_id = u.id AND c.name = '购物'
                        WHERE u.username = 'v10-upgrade'
                        """);
            }
            long legacyAccountId;
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         SELECT a.id FROM accounts a JOIN users u ON u.id = a.user_id
                         WHERE u.username = 'v10-upgrade'
                         """)) {
                assertThat(rows.next()).isTrue();
                legacyAccountId = rows.getLong(1);
            }
            // V10 nullable category, V11 analysis, V12 multi-account, V13 cross-account order collision,
            // V14 analysis account scope, V15 account lifecycle.
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);
            flyway.validate();
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("""
                        SELECT a.id, a.kind, a.name, a.opening_balance, t.id
                        FROM accounts a
                        JOIN users u ON u.id = a.user_id
                        JOIN transactions t ON t.account_id = a.id AND t.user_id = a.user_id
                        WHERE u.username = 'v10-upgrade'
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(legacyAccountId);
                    assertThat(rows.getString(2)).isEqualTo("NORMAL");
                    assertThat(rows.getString(3)).isEqualTo("微信");
                    assertThat(rows.getBigDecimal(4)).isEqualByComparingTo("100.00");
                    assertThat(rows.getLong(5)).isPositive();
                }
                try (var rows = statement.executeQuery("""
                        SELECT a.lifecycle FROM accounts a
                        JOIN users u ON u.id = a.user_id
                        WHERE u.username = 'v10-upgrade'
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("ACTIVE");
                }
                try (var rows = statement.executeQuery("""
                        SELECT constraint_name FROM information_schema.table_constraints
                        WHERE table_schema = DATABASE() AND table_name = 'accounts'
                          AND constraint_name = 'ck_accounts_lifecycle'
                        """)) {
                    assertThat(rows.next()).isTrue();
                }
                try (var rows = statement.executeQuery("""
                        SELECT index_name FROM information_schema.statistics
                        WHERE table_schema = DATABASE() AND table_name = 'accounts'
                          AND column_name = 'lifecycle'
                        """)) {
                    assertThat(rows.next()).isFalse();
                }
                try (var rows = statement.executeQuery("""
                        SELECT constraint_name FROM information_schema.table_constraints
                        WHERE table_schema = DATABASE() AND table_name = 'accounts' AND constraint_type = 'UNIQUE'
                        """)) {
                    var names = new ArrayList<String>();
                    while (rows.next()) names.add(rows.getString(1));
                    assertThat(names).contains("uk_accounts_id_user", "uk_accounts_user_name");
                    assertThat(names).doesNotContain("uk_accounts_user");
                }
                // V12 must keep fk_accounts_user; its supporting index is now uk_accounts_user_name.
                try (var rows = statement.executeQuery("""
                        SELECT constraint_name FROM information_schema.table_constraints
                        WHERE table_schema = DATABASE() AND table_name = 'accounts'
                          AND constraint_type = 'FOREIGN KEY'
                        """)) {
                    var names = new ArrayList<String>();
                    while (rows.next()) names.add(rows.getString(1));
                    assertThat(names).contains("fk_accounts_user");
                }
                try (var rows = statement.executeQuery("""
                        SELECT index_name, seq_in_index, column_name
                        FROM information_schema.statistics
                        WHERE table_schema = DATABASE() AND table_name = 'accounts'
                          AND index_name = 'uk_accounts_user_name'
                        ORDER BY seq_in_index
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(2)).isEqualTo(1);
                    assertThat(rows.getString(3)).isEqualTo("user_id");
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(2)).isEqualTo(2);
                    assertThat(rows.getString(3)).isEqualTo("name");
                    assertThat(rows.next()).isFalse();
                }
                statement.executeUpdate("""
                        INSERT INTO accounts (user_id, name, kind, currency, opening_balance, created_time, updated_at_utc)
                        SELECT id, '现金', 'CREDIT', 'CNY', 0.00, '2020-02-01 00:00:00', '2026-09-01 00:00:00.000000'
                        FROM users WHERE username = 'v10-upgrade'
                        """);
                assertThatThrownBy(() -> statement.executeUpdate("""
                        INSERT INTO accounts (user_id, name, kind, currency, opening_balance, created_time, updated_at_utc)
                        SELECT id, '微信', 'NORMAL', 'CNY', 1.00, '2020-03-01 00:00:00', '2026-09-01 00:00:00.000000'
                        FROM users WHERE username = 'v10-upgrade'
                        """)).isInstanceOf(SQLException.class);
                try (var rows = statement.executeQuery("""
                        SELECT is_nullable FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name = 'transactions' AND column_name = 'category_id'
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("YES");
                }
                long categoryId;
                try (var rows = statement.executeQuery("""
                        SELECT t.category_id, c.name FROM transactions t JOIN categories c ON c.id = t.category_id
                        JOIN users u ON u.id = t.user_id WHERE u.username = 'v10-upgrade'
                        """)) {
                    assertThat(rows.next()).isTrue();
                    categoryId = rows.getLong(1);
                    assertThat(rows.getString(2)).isEqualTo("购物");
                }
                statement.executeUpdate("""
                        INSERT INTO transactions (user_id, account_id, type, amount, category_id, business_time,
                            first_saved_at_utc, first_save_order, updated_at_utc)
                        SELECT user_id, account_id, type, 3.00, NULL, business_time,
                            first_saved_at_utc, 2, updated_at_utc
                        FROM transactions WHERE category_id = %d
                        """.formatted(categoryId));
                assertThatThrownBy(() -> statement.executeUpdate("""
                        INSERT INTO transactions (user_id, account_id, type, amount, category_id, business_time,
                            first_saved_at_utc, first_save_order, updated_at_utc)
                        SELECT user_id, account_id, type, 4.00, 999999, business_time,
                            first_saved_at_utc, 3, updated_at_utc
                        FROM transactions WHERE category_id = %d
                        """.formatted(categoryId))).isInstanceOf(SQLException.class);
            }
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SHOW TABLES")) {
                var names = new ArrayList<String>();
                while (rows.next()) names.add(rows.getString(1));
                assertThat(names).containsExactlyInAnyOrder("users", "sessions", "accounts", "categories", "tags", "transactions", "transaction_tags", "idempotency_records", "balance_adjustments", "analysis_snapshots", "analysis_quota_days", "analysis_generations", "analysis_user_locks", "flyway_schema_history");
            }
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         SELECT constraint_name FROM information_schema.table_constraints
                         WHERE table_schema = DATABASE() AND table_name IN ('transactions', 'transaction_tags')
                         """)) {
                var names = new ArrayList<String>();
                while (rows.next()) names.add(rows.getString(1));
                assertThat(names).contains("fk_transactions_account", "fk_transactions_category",
                        "fk_transaction_tags_transaction", "fk_transaction_tags_tag");
                assertThat(names).doesNotContain("uk_transactions_account_order");
            }
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement()) {
                // V13: same account may hold two transactions that kept colliding historical orders.
                statement.executeUpdate("""
                        INSERT INTO transactions (user_id, account_id, type, amount, category_id, business_time,
                            first_saved_at_utc, first_save_order, updated_at_utc)
                        SELECT user_id, account_id, type, 5.00, category_id, business_time,
                            DATE_ADD(first_saved_at_utc, INTERVAL 1 SECOND), 1, updated_at_utc
                        FROM transactions
                        WHERE user_id = (SELECT id FROM users WHERE username = 'v10-upgrade')
                        ORDER BY id
                        LIMIT 1
                        """);
                try (var rows = statement.executeQuery("""
                        SELECT COUNT(*) FROM transactions
                        WHERE user_id = (SELECT id FROM users WHERE username = 'v10-upgrade')
                          AND first_save_order = 1
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(2);
                }
                try (var rows = statement.executeQuery("""
                        SELECT index_name FROM information_schema.statistics
                        WHERE table_schema = DATABASE() AND table_name = 'transactions'
                          AND index_name = 'idx_transactions_ledger'
                        """)) {
                    assertThat(rows.next()).isTrue();
                }
            }
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         SELECT delete_rule FROM information_schema.referential_constraints
                         WHERE constraint_schema = DATABASE() AND constraint_name = 'fk_transaction_tags_transaction'
                         """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("CASCADE");
            }
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO analysis_snapshots
                            (user_id, account_id, analysis_month, context_fingerprint, monthly_overview, expense_structure,
                             surplus_reasons, points_to_watch, provider, model, prompt_version, latency_millis, permit_date,
                             generated_at_utc)
                        SELECT id, NULL, '2026-09', '1:legacy-v14', '遗留', '支出', '结余', '关注',
                               'dashscope', 'qwen3.7-flash', 'v1', 1, '2026-09-01', '2026-09-01 00:00:00.000000'
                        FROM users WHERE username = 'v10-upgrade'
                        """);
                try (var rows = statement.executeQuery("""
                        SELECT is_nullable FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name = 'analysis_snapshots'
                          AND column_name = 'account_id'
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("YES");
                }
                try (var rows = statement.executeQuery("""
                        SELECT constraint_name FROM information_schema.table_constraints
                        WHERE table_schema = DATABASE() AND table_name = 'analysis_generations'
                          AND constraint_type = 'UNIQUE'
                        """)) {
                    var names = new ArrayList<String>();
                    while (rows.next()) names.add(rows.getString(1));
                    assertThat(names).contains("uk_analysis_generations_account_scope");
                    assertThat(names).doesNotContain("uk_analysis_generations_scope");
                }
                try (var rows = statement.executeQuery("""
                        SELECT COUNT(*) FROM analysis_snapshots
                        WHERE account_id IS NULL AND context_fingerprint = '1:legacy-v14'
                        """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isEqualTo(1);
                }
            }
        } finally {
            flyway.clean();
        }
    }
}
