package com.limingyuan.bookkeeping;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ActiveProfiles("test")
class BootstrapIntegrationTest {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Flyway flyway;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextStartsWithTestProfileAndIdentityEntities() {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(entityManagerFactory.getMetamodel().getEntities()).extracting(jakarta.persistence.metamodel.EntityType::getName)
                .containsExactlyInAnyOrder("UserEntity", "SessionEntity", "AccountEntity", "CategoryEntity", "TagEntity", "TransactionEntity", "TransactionTagEntity", "IdempotencyRecord");
    }

    @Test
    void connectsToRealMySql84TestDatabase() throws Exception {
        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            assertThat(metadata.getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(metadata.getDatabaseMajorVersion()).isEqualTo(8);
            assertThat(metadata.getDatabaseMinorVersion()).isEqualTo(4);
            assertThat(connection.getCatalog()).isEqualTo("bookkeeping_test");
        }
        assertThat(jdbcClient.sql("SELECT 1").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbcClient.sql("SELECT @@character_set_database").query(String.class).single())
                .isEqualTo("utf8mb4");
        assertThat(jdbcClient.sql("SELECT @@collation_database").query(String.class).single())
                .isEqualTo("utf8mb4_0900_ai_ci");
    }

    @Test
    void flywayLoadsOnlyIdentityMigrations() {
        assertThat(flyway.getConfiguration().getDataSource()).isSameAs(dataSource);
        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
        flyway.validate();
        assertThat(flyway.info().applied()).hasSize(8);
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbcClient.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE()
                """).query(String.class).list()).containsExactlyInAnyOrder("flyway_schema_history", "users", "sessions", "accounts", "categories", "tags", "transactions", "transaction_tags", "idempotency_records");
    }

    @Test
    void healthIsPublicAndDoesNotExposeDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}"))
                .andExpect(content().string("{\"status\":\"UP\"}"));
    }

    @Test
    void otherEndpointsRequireAuthentication() throws Exception {
        for (var path : new String[] {"/actuator/env", "/api/v1/accounts", "/login"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }
}
