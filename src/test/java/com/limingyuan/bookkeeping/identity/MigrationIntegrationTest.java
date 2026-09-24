package com.limingyuan.bookkeeping.identity;

import java.sql.DriverManager;
import java.util.ArrayList;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MigrationIntegrationTest {
    @Test void migratesCleanMySqlSchemaAndValidatesOnSecondRun() throws Exception {
        String url = System.getenv().getOrDefault("MIGRATION_TEST_DB_URL", "jdbc:mysql://localhost:3306/bookkeeping_migration_test");
        String username = System.getenv("TEST_DB_USERNAME");
        String password = System.getenv("TEST_DB_PASSWORD");
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
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement();
                 var rows = statement.executeQuery("SHOW TABLES")) {
                var names = new ArrayList<String>();
                while (rows.next()) names.add(rows.getString(1));
                assertThat(names).containsExactlyInAnyOrder("users", "sessions", "accounts", "categories", "tags", "flyway_schema_history");
            }
        } finally {
            flyway.clean();
        }
    }
}
