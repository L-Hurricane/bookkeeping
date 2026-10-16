package com.limingyuan.bookkeeping.analysis;

import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Failure mode still uses the real permit path and does not call a provider. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bookkeeping.analysis.mock.enabled=true",
        "bookkeeping.analysis.mock.allowed-usernames=mock_accept_a",
        "bookkeeping.analysis.mock.mode=failure",
        "bookkeeping.analysis.dashscope.api-key=",
        "bookkeeping.analysis.dashscope.base-url=",
        "spring.datasource.url=${TEST_DB_URL}",
        "spring.datasource.username=${TEST_DB_USERNAME}",
        "spring.datasource.password=${TEST_DB_PASSWORD}"
})
@ActiveProfiles({"local", "test"})
class MockGenerationFailureHttpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @Autowired JdbcClient jdbc;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Long> fixtures = new ArrayList<>();

    @BeforeEach
    void requireTestDatabase() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("bookkeeping_test");
        }
    }

    @AfterEach
    void cleanup() {
        for (long id : fixtures) deleteUser(id);
    }

    @Test void failedMockReleasesTheReservationAndWritesNoSnapshot() throws Exception {
        String token = login("mock_accept_a");
        long userId = fixtures.getLast();
        long accountId = account(token);
        transact(token, accountId);
        String fingerprint = get(token, "/api/v1/accounts/" + accountId + "/monthly-analysis?month=2026-03").body().path("fingerprint").asText();
        Exchange failed = post(token, "/api/v1/accounts/" + accountId + "/monthly-analysis",
                Map.of("month", "2026-03", "expectedFingerprint", fingerprint));
        assertThat(failed.status()).isEqualTo(502);
        assertThat(failed.body().path("error").path("code").asText()).isEqualTo("GENERATION_FAILED");
        assertThat(failed.raw()).doesNotContain("SESSION_INVALID", "dashscope", "\tat ");
        var row = jdbc.sql("SELECT state, snapshot_id FROM analysis_generations WHERE user_id = :id")
                .param("id", userId).query((rs, ignored) -> rs.getString(1) + "/" + rs.getObject(2)).list();
        assertThat(row).containsExactly("FAILED/null");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM analysis_snapshots WHERE user_id = :id").param("id", userId).query(Integer.class).single()).isZero();
        var quota = jdbc.sql("""
                SELECT COALESCE(SUM(reserved_count), 0), COALESCE(SUM(consumed_count), 0)
                FROM analysis_quota_days WHERE user_id = :id
                """).param("id", userId).query((rs, ignored) -> List.of(rs.getInt(1), rs.getInt(2))).single();
        assertThat(quota).containsExactly(0, 0);
        Exchange again = get(token, "/api/v1/accounts/" + accountId + "/monthly-analysis?month=2026-03");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().path("viewState").asText()).isEqualTo("GENERATION_FAILED");
    }

    private String login(String username) throws Exception {
        users.findByUsername(username).ifPresent(user -> deleteUser(user.getId()));
        String password = tokens.generate();
        bootstrap.ensureUser(username, password);
        long id = users.findByUsername(username).orElseThrow().getId();
        fixtures.add(id);
        Exchange logged = call(null, "POST", "/api/v1/auth/login", json.writeValueAsString(Map.of("username", username, "password", password)));
        assertThat(logged.status()).isEqualTo(200);
        return logged.body().path("token").asText();
    }

    private long account(String token) throws Exception {
        Exchange created = call(token, "POST", "/api/v1/accounts/initialize", "{\"openingBalance\":\"100.00\"}");
        assertThat(created.status()).isEqualTo(201);
        long accountId = created.body().path("id").asLong();
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        return accountId;
    }

    private void transact(String token, long accountId) throws Exception {
        long categoryId = 0;
        for (JsonNode root : get(token, "/api/v1/categories?type=EXPENSE").body().path("items")) {
            if (root.path("name").asText().equals("购物")) categoryId = root.path("id").asLong();
        }
        assertThat(categoryId).isPositive();
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "EXPENSE");
        body.put("amount", "20.00");
        body.put("name", "购物");
        body.put("categoryId", categoryId);
        body.put("tagIds", List.of());
        body.put("businessTime", "2026-03-02T10:00:00+08:00");
        Exchange created = call(token, "POST", "/api/v1/accounts/" + accountId + "/transactions", json.writeValueAsString(body));
        assertThat(created.status()).isEqualTo(201);
    }

    private Exchange get(String token, String path) throws Exception { return call(token, "GET", path, null); }

    private Exchange post(String token, String path, Map<String, String> body) throws Exception {
        return call(token, "POST", path, json.writeValueAsString(body));
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
        return new Exchange(response.statusCode(), parsed, raw);
    }

    private void deleteUser(long id) {
        jdbc.sql("DELETE FROM analysis_generations WHERE user_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM analysis_snapshots WHERE user_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM analysis_quota_days WHERE user_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM analysis_user_locks WHERE user_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM idempotency_records WHERE user_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM balance_adjustments WHERE user_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM transaction_tags WHERE transaction_id IN (SELECT id FROM transactions WHERE user_id = :id)").param("id", id).update();
        jdbc.sql("DELETE FROM transactions WHERE user_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM tags WHERE user_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM accounts WHERE user_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", id).update();
        jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
        users.deleteById(id);
    }

    private record Exchange(int status, JsonNode body, String raw) { }
}
