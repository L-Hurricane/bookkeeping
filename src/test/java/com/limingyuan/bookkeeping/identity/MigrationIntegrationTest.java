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
            assertThat(connection.getCatalog()).isEqualTo("bookkeeping_migration_test");
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
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            flyway.validate();
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement()) {
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
                assertThat(names).contains("uk_transactions_account_order", "fk_transactions_account",
                        "fk_transactions_category", "fk_transaction_tags_transaction", "fk_transaction_tags_tag");
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
        } finally {
            flyway.clean();
        }
    }
}
