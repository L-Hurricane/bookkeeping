package com.limingyuan.bookkeeping.analysis;

import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGenerator;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** An internal generator fault must stay an internal error. It must not become GENERATION_FAILED or SESSION_INVALID. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ErrorDispatchProbeConfiguration.class)
class InternalGenerationFaultHttpTest {
    private static final String MARKER = "internal-generation-fault";

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    @MockitoBean MonthlyAnalysisGenerator generator;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Long> fixtures = new ArrayList<>();

    @BeforeEach void requireTestDatabase() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("bookkeeping_test");
        }
        when(generator.generate(any(), any())).thenThrow(new IllegalStateException(MARKER));
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

    @Test void internalGeneratorExceptionStays500AndReleasesThePermit() throws Exception {
        String name = "analysis_internal_" + UUID.randomUUID();
        String password = tokens.generate();
        bootstrap.ensureUser(name, password);
        long userId = users.findByUsername(name).orElseThrow().getId();
        fixtures.add(userId);
        Exchange logged = call(null, "POST", "/api/v1/auth/login", json.writeValueAsString(Map.of("username", name, "password", password)));
        assertThat(logged.status()).isEqualTo(200);
        String token = logged.body().path("token").asText();
        Exchange created = call(token, "POST", "/api/v1/accounts/initialize", "{\"openingBalance\":\"100.00\"}");
        assertThat(created.status()).isEqualTo(201);
        long accountId = created.body().path("id").asLong();
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        long shopping = categoryId(token);
        var transaction = new LinkedHashMap<String, Object>();
        transaction.put("type", "EXPENSE");
        transaction.put("amount", "20.00");
        transaction.put("name", "购物");
        transaction.put("categoryId", shopping);
        transaction.put("tagIds", List.of());
        transaction.put("businessTime", "2026-03-02T10:00:00+08:00");
        assertThat(call(token, "POST", "/api/v1/accounts/" + accountId + "/transactions", json.writeValueAsString(transaction)).status()).isEqualTo(201);
        String fingerprint = call(token, "GET", "/api/v1/accounts/" + accountId + "/monthly-analysis?month=2026-03", null)
                .body().path("fingerprint").asText();

        Exchange failed = call(token, "POST", "/api/v1/accounts/" + accountId + "/monthly-analysis",
                json.writeValueAsString(Map.of("month", "2026-03", "expectedFingerprint", fingerprint)));
        assertThat(failed.status()).isEqualTo(500);
        assertThat(failed.header()).contains("ERROR");
        assertThat(failed.raw()).doesNotContain("SESSION_INVALID", "GENERATION_FAILED", MARKER, "IllegalStateException", "\tat ");
        assertThat(failed.body().path("error").path("code").asText()).isNotEqualTo("GENERATION_FAILED");

        var row = jdbc.sql("SELECT state, snapshot_id FROM analysis_generations WHERE user_id = :id")
                .param("id", userId).query((rs, ignored) -> List.of(rs.getString(1), String.valueOf(rs.getObject(2)))).list();
        assertThat(row).containsExactly(List.of("FAILED", "null"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM analysis_snapshots WHERE user_id = :id").param("id", userId).query(Integer.class).single()).isZero();
        var quota = jdbc.sql("""
                SELECT COALESCE(SUM(reserved_count), 0), COALESCE(SUM(consumed_count), 0)
                FROM analysis_quota_days WHERE user_id = :id
                """).param("id", userId).query((rs, ignored) -> List.of(rs.getInt(1), rs.getInt(2))).single();
        assertThat(quota).containsExactly(0, 0);

        Exchange followUp = call(token, "GET", "/api/v1/accounts/" + accountId + "/monthly-analysis?month=2026-03", null);
        assertThat(followUp.status()).isEqualTo(200);
        assertThat(followUp.body().path("viewState").asText()).isEqualTo("GENERATION_FAILED");
    }

    private long categoryId(String token) throws Exception {
        for (JsonNode root : call(token, "GET", "/api/v1/categories?type=EXPENSE", null).body().path("items")) {
            if (root.path("name").asText().equals("购物")) return root.path("id").asLong();
        }
        throw new AssertionError("购物");
    }

    private Exchange call(String token, String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.header("Accept", "application/json");
        if ("POST".equals(method) && path.contains("/transactions")) builder.header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String raw = response.body() == null ? "" : response.body();
        JsonNode parsed = raw.isBlank() ? json.nullNode() : json.readTree(raw);
        return new Exchange(response.statusCode(), parsed, raw, response.headers().firstValue(ErrorDispatchProbeConfiguration.HEADER));
    }

    private record Exchange(int status, JsonNode body, String raw, java.util.Optional<String> header) { }
}
