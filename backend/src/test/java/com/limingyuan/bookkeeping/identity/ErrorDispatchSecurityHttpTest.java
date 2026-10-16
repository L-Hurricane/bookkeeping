package com.limingyuan.bookkeeping.identity;

import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import com.limingyuan.bookkeeping.support.ErrorDispatchProbeConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Real container HTTP. MockMvc does not run the servlet ERROR dispatcher. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({ErrorDispatchSecurityHttpTest.ProbeConfiguration.class, ErrorDispatchProbeConfiguration.class})
class ErrorDispatchSecurityHttpTest {
    private static final String MARKER = "error-dispatch-secret-marker";

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Long> fixtures = new ArrayList<>();

    @BeforeEach void requireTestDatabase() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("bookkeeping_test");
        }
    }

    @AfterEach void cleanup() {
        for (long id : fixtures) {
            jdbc.sql("DELETE FROM analysis_generations WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM analysis_snapshots WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM analysis_quota_days WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM analysis_user_locks WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM idempotency_records WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM balance_adjustments WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM transaction_tags WHERE transaction_id IN (SELECT id FROM transactions WHERE user_id = :id)").param("id", id).update();
            jdbc.sql("DELETE FROM transactions WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM accounts WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
            users.deleteById(id);
        }
    }

    @Test void authenticatedRequestSucceedsAndInvalidTokenStaysUnauthorized() throws Exception {
        String token = newUser("error_dispatch_ok");
        Exchange ok = call(token, "GET", "/api/v1/test/error-dispatch/ok", null);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().path("status").asText()).isEqualTo("ok");
        assertThat(ok.header()).isEmpty();

        Exchange missing = call(null, "GET", "/api/v1/test/error-dispatch/ok", null);
        assertThat(missing.status()).isEqualTo(401);
        assertThat(missing.body().path("error").path("code").asText()).isEqualTo("SESSION_INVALID");

        Exchange invalid = call(tokens.generate(), "GET", "/api/v1/test/error-dispatch/ok", null);
        assertThat(invalid.status()).isEqualTo(401);
        assertThat(invalid.body().path("error").path("code").asText()).isEqualTo("SESSION_INVALID");
        assertThat(invalid.header()).isEmpty();
    }

    @Test void uncaughtExceptionUsesTheErrorDispatcherAndStays500() throws Exception {
        String token = newUser("error_dispatch_boom");
        Exchange boom = call(token, "GET", "/api/v1/test/error-dispatch/boom", null);
        assertThat(boom.status()).isEqualTo(500);
        assertThat(boom.header()).contains("ERROR");
        assertThat(boom.raw()).doesNotContain("SESSION_INVALID", MARKER, "IllegalStateException", "\tat ");
        assertThat(boom.body().path("error").path("code").asText()).isNotEqualTo("SESSION_INVALID");

        Exchange stillValid = call(token, "GET", "/api/v1/test/error-dispatch/ok", null);
        assertThat(stillValid.status()).isEqualTo(200);
        assertThat(stillValid.body().path("status").asText()).isEqualTo("ok");
    }

    @Test void directErrorRequestRemainsDenied() throws Exception {
        Exchange anonymous = call(null, "GET", "/error", null);
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.body().path("error").path("code").asText()).isEqualTo("SESSION_INVALID");
        assertThat(anonymous.header()).isEmpty();

        String token = newUser("error_dispatch_direct");
        Exchange authenticated = call(token, "GET", "/error", null);
        assertThat(authenticated.status()).isEqualTo(403);
        assertThat(authenticated.body().path("error").path("code").asText()).isEqualTo("OPERATION_FORBIDDEN");
        assertThat(authenticated.header()).isEmpty();
        assertThat(authenticated.raw()).doesNotContain(MARKER);
    }

    @Test void publicLoginAndForeignAccountKeepTheirExistingResults() throws Exception {
        Exchange login = call(null, "POST", "/api/v1/auth/login", "{}");
        assertThat(login.status()).isEqualTo(400);
        assertThat(login.body().path("error").path("code").asText()).isEqualTo("VALIDATION_FAILED");

        String owner = newUser("error_dispatch_owner");
        Exchange created = call(owner, "POST", "/api/v1/accounts/initialize", "{\"openingBalance\":\"100.00\"}");
        assertThat(created.status()).isEqualTo(201);
        long accountId = created.body().path("id").asLong();

        String other = newUser("error_dispatch_other");
        Exchange foreign = call(other, "GET", "/api/v1/accounts/" + accountId + "/monthly-analysis?month=2026-03", null);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(foreign.body().path("error").path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(foreign.header()).isEmpty();
    }

    private String newUser(String prefix) throws Exception {
        String name = prefix + "_" + UUID.randomUUID();
        String password = tokens.generate();
        bootstrap.ensureUser(name, password);
        long id = users.findByUsername(name).orElseThrow().getId();
        fixtures.add(id);
        Exchange logged = call(null, "POST", "/api/v1/auth/login", json.writeValueAsString(Map.of("username", name, "password", password)));
        assertThat(logged.status()).isEqualTo(200);
        return logged.body().path("token").asText();
    }

    private Exchange call(String token, String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.header("Accept", "application/json");
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String raw = response.body() == null ? "" : response.body();
        JsonNode parsed = raw.isBlank() ? json.nullNode() : json.readTree(raw);
        return new Exchange(response.statusCode(), parsed, raw, response.headers().firstValue(ErrorDispatchProbeConfiguration.HEADER));
    }

    private record Exchange(int status, JsonNode body, String raw, java.util.Optional<String> header) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean ErrorDispatchController errorDispatchController() { return new ErrorDispatchController(); }
    }

    @RestController
    static class ErrorDispatchController {
        @GetMapping("/api/v1/test/error-dispatch/ok")
        Map<String, String> ok() { return Map.of("status", "ok"); }

        @GetMapping("/api/v1/test/error-dispatch/boom")
        String boom() { throw new IllegalStateException(MARKER); }
    }
}
