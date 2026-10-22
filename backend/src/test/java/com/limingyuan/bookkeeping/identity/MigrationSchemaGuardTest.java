package com.limingyuan.bookkeeping.identity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationSchemaGuardTest {
    @Test void acceptsOnlyTheDisposableMigrationSchema() {
        assertThat(MigrationSchemaGuard.databaseName("jdbc:mysql://127.0.0.1:3306/bookkeeping_migration_test"))
                .isEqualTo("bookkeeping_migration_test");
        assertThat(MigrationSchemaGuard.databaseName("jdbc:mysql://localhost:3306/bookkeeping_migration_test?useSSL=false"))
                .isEqualTo("bookkeeping_migration_test");
        MigrationSchemaGuard.requireDisposableSchema("jdbc:mysql://localhost:3306/bookkeeping_migration_test");
    }

    @Test void acceptsTheS1T03DedicatedMigrationSchema() {
        assertThat(MigrationSchemaGuard.databaseName("jdbc:mysql://127.0.0.1:3306/bk_s1t03_6928fd20_migration"))
                .isEqualTo("bk_s1t03_6928fd20_migration");
        MigrationSchemaGuard.requireDisposableSchema("jdbc:mysql://localhost:3306/bk_s1t03_6928fd20_migration");
        MigrationSchemaGuard.requireDisposableSchema(
                "jdbc:mysql://localhost:3306/bk_s1t03_6928fd20_migration?useSSL=false");
    }

    @Test void refusesTheDevelopmentAndOrdinaryTestSchemasBeforeClean() {
        assertThatThrownBy(() -> MigrationSchemaGuard.requireDisposableSchema("jdbc:mysql://127.0.0.1:3306/bookkeeping"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bookkeeping")
                .hasMessageNotContaining("bookkeeping_test")
                .hasMessageNotContaining("password");
        assertThatThrownBy(() -> MigrationSchemaGuard.requireDisposableSchema("jdbc:mysql://127.0.0.1:3306/bookkeeping_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bookkeeping_test");
        assertThatThrownBy(() -> MigrationSchemaGuard.requireDisposableSchema("jdbc:mysql://127.0.0.1:3306/"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MigrationSchemaGuard.requireDisposableSchema("jdbc:postgresql://127.0.0.1:5432/bookkeeping_migration_test"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void refusesArbitraryNamesThatAreNotAllowlisted() {
        assertThatThrownBy(() -> MigrationSchemaGuard.requireDisposableSchema(
                        "jdbc:mysql://127.0.0.1:3306/bk_s1t03_other_migration"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bk_s1t03_other_migration");
        assertThatThrownBy(() -> MigrationSchemaGuard.requireDisposableSchema(
                        "jdbc:mysql://127.0.0.1:3306/bk_s1t03_6928fd20"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MigrationSchemaGuard.requireDisposableSchema(
                        "jdbc:mysql://127.0.0.1:3306/some_custom_migration_test"))
                .isInstanceOf(IllegalStateException.class);
    }
}
