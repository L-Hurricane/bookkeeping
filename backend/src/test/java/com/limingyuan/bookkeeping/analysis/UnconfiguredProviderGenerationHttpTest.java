package com.limingyuan.bookkeeping.analysis;

import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import com.limingyuan.bookkeeping.support.ErrorDispatchProbeConfiguration;
import com.limingyuan.bookkeeping.support.TestSchemaGuard;
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
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Real Qwen generator with blank provider settings. No DashScope call is possible before the failure. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bookkeeping.analysis.dashscope.base-url= ",
        "bookkeeping.analysis.dashscope.api-key= ",
        "bookkeeping.analysis.dashscope.model= "
})
@ActiveProfiles("test")
@Import(ErrorDispatchProbeConfiguration.class)
class UnconfiguredProviderGenerationHttpTest {
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
            TestSchemaGuard.requireAllowedSchema(connection.getCatalog());
        }
    }

    @AfterEach void cleanup() {
        for (long id : fixtures) deleteUser(id);
    }

    @Test void missingProviderReturnsGenerationFailedWithoutASnapshotOrQuotaUse() throws Exception {
        String token = newUser("analysis_unconfigured");
        long userId = fixtures.getLast();
        long accountId = initialize(token);
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        long shopping = categoryId(token, "EXPENSE", "购物");
        transact(token, accountId, shopping, "2026-03-02T10:00:00+08:00");
        String fingerprint = get(token, "/api/v1/accounts/" + accountId + "/monthly-analysis?month=2026-03").body().path("fingerprint").asText();

        Exchange failed = post(token, "/api/v1/accounts/" + accountId + "/monthly-analysis",
                json.writeValueAsString(Map.of("month", "2026-03", "expectedFingerprint", fingerprint)));
        assertThat(failed.status()).isEqualTo(502);
        assertThat(failed.body().path("error").path("code").asText()).isEqualTo("GENERATION_FAILED");
        assertThat(failed.body().path("error").path("message").asText()).isEqualTo("分析生成失败，请稍后重试");
        assertThat(failed.header()).isEmpty();
        assertThat(failed.raw()).doesNotContain("DashScope", "not configured", "IllegalStateException", "\tat ", "SESSION_INVALID");

        var row = jdbc.sql("SELECT state, snapshot_id FROM analysis_generations WHERE user_id = :id")
                .param("id", userId).query((rs, ignored) -> new GenerationRow(rs.getString(1), rs.getObject(2))).list();
        assertThat(row).containsExactly(new GenerationRow("FAILED", null));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM analysis_snapshots WHERE user_id = :id").param("id", userId).query(Integer.class).single()).isZero();
        var quota = jdbc.sql("""
                SELECT COALESCE(SUM(reserved_count), 0), COALESCE(SUM(consumed_count), 0)
                FROM analysis_quota_days WHERE user_id = :id
                """).param("id", userId).query((rs, ignored) -> List.of(rs.getInt(1), rs.getInt(2))).single();
        assertThat(quota).containsExactly(0, 0);

        Exchange again = get(token, "/api/v1/accounts/" + accountId + "/monthly-analysis?month=2026-03");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().path("viewState").asText()).isEqualTo("GENERATION_FAILED");
        assertThat(again.body().path("snapshot").isMissingNode()).isTrue();
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

    private long initialize(String token) throws Exception {
        Exchange created = call(token, "POST", "/api/v1/accounts/initialize", "{\"openingBalance\":\"100.00\"}");
        assertThat(created.status()).isEqualTo(201);
        return created.body().path("id").asLong();
    }

    private long categoryId(String token, String type, String name) throws Exception {
        for (JsonNode root : get(token, "/api/v1/categories?type=" + type).body().path("items")) {
            if (root.path("name").asText().equals(name)) return root.path("id").asLong();
            for (JsonNode child : root.path("children")) if (child.path("name").asText().equals(name)) return child.path("id").asLong();
        }
        throw new AssertionError(name);
    }

    private void transact(String token, long accountId, long categoryId, String time) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "EXPENSE");
        body.put("amount", "20.00");
        body.put("name", "购物");
        body.put("categoryId", categoryId);
        body.put("tagIds", List.of());
        body.put("businessTime", time);
        Exchange created = call(token, "POST", "/api/v1/accounts/" + accountId + "/transactions", json.writeValueAsString(body));
        assertThat(created.status()).isEqualTo(201);
    }

    private Exchange get(String token, String path) throws Exception { return call(token, "GET", path, null); }

    private Exchange post(String token, String path, String body) throws Exception { return call(token, "POST", path, body); }

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

    private void deleteUser(long id) {
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

    private record Exchange(int status, JsonNode body, String raw, java.util.Optional<String> header) { }
    private record GenerationRow(String state, Object snapshotId) { }
}
