package com.limingyuan.bookkeeping.analysis;

import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGenerator;
import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
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
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** Real generation flow on an allowlisted ordinary test schema. The mock has no provider client. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bookkeeping.analysis.mock.enabled=true",
        "bookkeeping.analysis.mock.allowed-usernames=mock_accept_a,mock_accept_b",
        "bookkeeping.analysis.mock.mode=success",
        "bookkeeping.analysis.dashscope.api-key=",
        "bookkeeping.analysis.dashscope.base-url=",
        "spring.datasource.url=${TEST_DB_URL}",
        "spring.datasource.username=${TEST_DB_USERNAME}",
        "spring.datasource.password=${TEST_DB_PASSWORD}"
})
@ActiveProfiles({"local", "test"})
class MockGenerationHttpTest {
    private static final String POINTS = "【本地模拟】本月分析基于当前账本数据生成，请结合实际交易明细进行核对。";
    private static final String JAVA_SURPLUS = "收入记录的有符号金额合计为净收入50.00元。净收入覆盖净支出20.00元后，结余为30.00元。";

    @LocalServerPort int port;
    @Autowired ApplicationContext context;
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
            TestSchemaGuard.requireAllowedSchema(connection.getCatalog());
        }
        assertThat(context.getBeansOfType(MonthlyAnalysisGenerator.class)).hasSize(1);
        assertThat(context.getBean(MonthlyAnalysisGenerator.class).getClass().getName()).endsWith("LocalMockMonthlyAnalysisGenerator");
    }

    @AfterEach
    void cleanup() {
        for (long id : fixtures) deleteUser(id);
    }

    @Test void allowedUserGetsCurrentReportAndReusesItOnce() throws Exception {
        String token = login("mock_accept_a");
        long userId = fixtures.getLast();
        long accountId = account(token);
        transact(token, accountId, "INCOME", "50.00", categoryId(token, "INCOME", "工资"), "工资", "2026-03-01T10:00:00+08:00");
        transact(token, accountId, "EXPENSE", "20.00", categoryId(token, "EXPENSE", "购物"), "购物", "2026-03-02T10:00:00+08:00");

        JsonNode before = ok(get(token, "/api/v1/accounts/" + accountId + "/monthly-analysis?month=2026-03"));
        assertThat(before.path("viewState").asText()).isEqualTo("NO_ANALYSIS");
        assertThat(before.path("facts").path("netIncome").asText()).isEqualTo("50.00");
        assertThat(before.path("facts").path("netExpense").asText()).isEqualTo("20.00");
        assertThat(before.path("facts").path("surplus").asText()).isEqualTo("30.00");

        Exchange created = post(token, "/api/v1/accounts/" + accountId + "/monthly-analysis",
                Map.of("month", "2026-03", "expectedFingerprint", before.path("fingerprint").asText()));
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.body().path("outcome").asText()).isEqualTo("SUCCESS");
        JsonNode analysis = created.body().path("analysis");
        assertThat(analysis.path("viewState").asText()).isEqualTo("CURRENT");
        JsonNode snapshot = analysis.path("snapshot");
        assertThat(snapshot.path("provider").asText()).isEqualTo("local-mock");
        assertThat(snapshot.path("model").asText()).isEqualTo("deterministic-acceptance");
        assertThat(snapshot.path("promptVersion").asText()).isEqualTo("v2");
        assertThat(snapshot.path("surplusReasons").asText()).isEqualTo(JAVA_SURPLUS);
        assertThat(snapshot.path("pointsToWatch").asText()).isEqualTo(POINTS);
        assertThat(snapshot.path("surplusReasons").asText()).doesNotContain("本地测试专用");
        assertThat(created.raw()).doesNotContain("dashscope", "qwen3.7-flash");

        Exchange reused = post(token, "/api/v1/accounts/" + accountId + "/monthly-analysis",
                Map.of("month", "2026-03", "expectedFingerprint", before.path("fingerprint").asText()));
        assertThat(reused.status()).isEqualTo(200);
        assertThat(reused.body().path("outcome").asText()).isEqualTo("REUSED");
        assertThat(reused.body().path("analysis").path("snapshot").path("id").asLong()).isEqualTo(snapshot.path("id").asLong());
        assertThat(count("analysis_snapshots", userId)).isEqualTo(1);
        assertThat(quota(userId)).containsExactly(0, 1);
        var stored = jdbc.sql("SELECT provider, model FROM analysis_snapshots WHERE user_id = :id")
                .param("id", userId).query((rs, ignored) -> rs.getString(1) + "/" + rs.getString(2)).single();
        assertThat(stored).isEqualTo("local-mock/deterministic-acceptance");
    }

    @Test void otherUserIsRejectedBeforeAnyGenerationWrite() throws Exception {
        String token = login("mock_denied");
        long userId = fixtures.getLast();
        long accountId = account(token);
        transact(token, accountId, "EXPENSE", "20.00", categoryId(token, "EXPENSE", "购物"), "购物", "2026-03-02T10:00:00+08:00");
        JsonNode before = ok(get(token, "/api/v1/accounts/" + accountId + "/monthly-analysis?month=2026-03"));
        Exchange rejected = post(token, "/api/v1/accounts/" + accountId + "/monthly-analysis",
                Map.of("month", "2026-03", "expectedFingerprint", before.path("fingerprint").asText()));
        assertThat(rejected.status()).isEqualTo(403);
        assertThat(rejected.body().path("error").path("code").asText()).isEqualTo("OPERATION_FORBIDDEN");
        assertThat(rejected.body().path("error").path("message").asText()).isEqualTo("当前本地模拟不接受此用户");
        assertThat(count("analysis_generations", userId)).isZero();
        assertThat(count("analysis_snapshots", userId)).isZero();
        assertThat(quota(userId)).containsExactly(0, 0);
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

    private long categoryId(String token, String type, String name) throws Exception {
        for (JsonNode root : get(token, "/api/v1/categories?type=" + type).body().path("items")) {
            if (root.path("name").asText().equals(name)) return root.path("id").asLong();
            for (JsonNode child : root.path("children")) if (child.path("name").asText().equals(name)) return child.path("id").asLong();
        }
        throw new AssertionError(name);
    }

    private void transact(String token, long accountId, String type, String amount, long categoryId, String name, String time) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", type);
        body.put("amount", amount);
        body.put("name", name);
        body.put("categoryId", categoryId);
        body.put("tagIds", List.of());
        body.put("businessTime", time);
        Exchange created = call(token, "POST", "/api/v1/accounts/" + accountId + "/transactions", json.writeValueAsString(body));
        assertThat(created.status()).isEqualTo(201);
    }

    private JsonNode ok(Exchange exchange) {
        assertThat(exchange.status()).isEqualTo(200);
        return exchange.body();
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

    private int count(String table, long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE user_id = :id").param("id", userId).query(Integer.class).single();
    }

    private List<Integer> quota(long userId) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(reserved_count), 0), COALESCE(SUM(consumed_count), 0)
                FROM analysis_quota_days WHERE user_id = :id
                """).param("id", userId).query((rs, ignored) -> List.of(rs.getInt(1), rs.getInt(2))).single();
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
