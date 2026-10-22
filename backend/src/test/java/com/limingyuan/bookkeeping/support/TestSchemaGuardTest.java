package com.limingyuan.bookkeeping.support;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSchemaGuardTest {
    @Test void acceptsTheOrdinaryTestSchema() {
        assertThat(TestSchemaGuard.databaseName("jdbc:mysql://127.0.0.1:3306/bookkeeping_test"))
                .isEqualTo("bookkeeping_test");
        assertThat(TestSchemaGuard.databaseName("jdbc:mysql://localhost:3306/bookkeeping_test?useSSL=false"))
                .isEqualTo("bookkeeping_test");
        TestSchemaGuard.requireAllowedSchema("bookkeeping_test");
    }

    @Test void acceptsTheS1T03DedicatedTestSchema() {
        assertThat(TestSchemaGuard.databaseName("jdbc:mysql://127.0.0.1:3306/bk_s1t03_6928fd20_test"))
                .isEqualTo("bk_s1t03_6928fd20_test");
        TestSchemaGuard.requireAllowedSchema("bk_s1t03_6928fd20_test");
        TestSchemaGuard.requireAllowedSchema(
                TestSchemaGuard.databaseName("jdbc:mysql://localhost:3306/bk_s1t03_6928fd20_test?useSSL=false"));
    }

    @Test void refusesDevelopmentMigrationAndUnknownSchemas() {
        assertThatThrownBy(() -> TestSchemaGuard.requireAllowedSchema("bookkeeping"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bookkeeping")
                .hasMessageNotContaining("bookkeeping_test")
                .hasMessageNotContaining("password");
        assertThatThrownBy(() -> TestSchemaGuard.requireAllowedSchema("bookkeeping_migration_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bookkeeping_migration_test");
        assertThatThrownBy(() -> TestSchemaGuard.requireAllowedSchema("bk_s1t03_6928fd20_migration"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestSchemaGuard.databaseName("jdbc:mysql://127.0.0.1:3306/"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestSchemaGuard.databaseName("jdbc:postgresql://127.0.0.1:5432/bookkeeping_test"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void refusesArbitraryNamesThatAreNotAllowlisted() {
        assertThatThrownBy(() -> TestSchemaGuard.requireAllowedSchema("bk_s1t03_other_test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bk_s1t03_other_test");
        assertThatThrownBy(() -> TestSchemaGuard.requireAllowedSchema("bk_s1t03_6928fd20"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestSchemaGuard.requireAllowedSchema("some_custom_test"))
                .isInstanceOf(IllegalStateException.class);
    }
}
