package com.limingyuan.bookkeeping.analysis;

import com.limingyuan.bookkeeping.BookkeepingApplication;
import com.limingyuan.bookkeeping.support.TestSchemaGuard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

class MockAnalysisStartupTest {
    private static final String SECRET = "startup-test-key-value";

    @BeforeAll
    static void requireTestDatabase() {
        TestSchemaGuard.requireAllowedSchema(TestSchemaGuard.databaseName(System.getenv("TEST_DB_URL")));
    }

    @Test void configuredKeyFailsStartupWithoutRevealingIt() {
        String message = refused(new String[] {"local", "test"}, SECRET);
        assertThat(message).isEqualTo("monthly analysis mock refuses to start while a DashScope API key is configured");
        assertThat(message).doesNotContain(SECRET);
    }

    @Test void nonLocalProfileFailsStartup() {
        assertThat(refused(new String[] {"test"}, "")).isEqualTo("monthly analysis mock requires the local profile");
    }

    @Test void productionProfileFailsStartup() {
        assertThat(refused(new String[] {"local", "production"}, "")).isEqualTo("monthly analysis mock refuses a production profile");
    }

    private static String refused(String[] profiles, String apiKey) {
        ConfigurableApplicationContext context = null;
        Throwable thrown = null;
        try {
            context = new SpringApplicationBuilder(BookkeepingApplication.class)
                    .profiles(profiles)
                    .run(
                            "--bookkeeping.analysis.mock.enabled=true",
                            "--bookkeeping.analysis.mock.allowed-usernames=mock_accept_a",
                            "--bookkeeping.analysis.mock.mode=success",
                            "--bookkeeping.analysis.dashscope.api-key=" + apiKey,
                            "--spring.datasource.url=" + System.getenv("TEST_DB_URL"),
                            "--spring.datasource.username=" + System.getenv("TEST_DB_USERNAME"),
                            "--spring.datasource.password=" + System.getenv("TEST_DB_PASSWORD"),
                            "--server.port=0");
        } catch (Throwable ex) {
            thrown = ex;
        }
        if (context != null) {
            context.close();
            throw new AssertionError("mock context started");
        }
        String message = null;
        for (Throwable current = thrown; current != null; current = current.getCause()) {
            if (current instanceof IllegalStateException && current.getMessage() != null
                    && current.getMessage().startsWith("monthly analysis mock")) {
                message = current.getMessage();
            }
        }
        assertThat(message).isNotNull();
        return message;
    }
}
