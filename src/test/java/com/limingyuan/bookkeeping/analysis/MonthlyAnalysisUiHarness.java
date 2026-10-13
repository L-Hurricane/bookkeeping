package com.limingyuan.bookkeeping.analysis;

import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationFailed;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGeneration;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGenerator;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisModelInput;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisPrompt;
import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Manual UI harness. It listens only when {@code bookkeeping.analysis.ui-harness=true}.
 * The generator double lives in this test and is not a production switch.
 */
@EnabledIfSystemProperty(named = "bookkeeping.analysis.ui-harness", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=8080",
        "spring.datasource.hikari.maximum-pool-size=8"
})
@ActiveProfiles("test")
class MonthlyAnalysisUiHarness {
    static final Path EVIDENCE = Path.of(System.getProperty("user.home"), "Documents/code/personal/bookkeeping-t6b1");
    static final Path MODE = EVIDENCE.resolve("generator-mode");
    static final Path STOP = EVIDENCE.resolve("stop");
    static final Path SESSION = EVIDENCE.resolve("session.json");
    private static final String MODEL_SURPLUS = "模型写成净支出99999.00元，这不是账本事实。";
    private static final String MODEL_POINTS = "第一行关注点。\n" + "长".repeat(400);

    static {
        String name = schemaName(System.getenv("TEST_DB_URL"));
        if (!"bookkeeping_test".equals(name)) {
            throw new ExceptionInInitializerError("UI harness refused schema " + name);
        }
    }

    @Autowired ObjectMapper json;
    @Autowired JdbcClient jdbc;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    @MockitoBean MonthlyAnalysisGenerator generator;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicBoolean discarded = new AtomicBoolean();

    @Test void serveBookkeepingTestWithMockGenerator() throws Exception {
        String catalog = jdbc.sql("SELECT DATABASE()").query(String.class).single();
        assertThat(catalog).isEqualTo("bookkeeping_test");
        assertThat(generator.getClass().getName()).doesNotContain("Qwen");
        when(generator.generate(any(), any())).thenAnswer(invocation -> answer(invocation.getArgument(0), invocation.getArgument(1)));
        Files.createDirectories(EVIDENCE);
        // A stop file left by the previous process is stale. This start consumes it, then waits for a new one.
        Files.deleteIfExists(STOP);
        Files.deleteIfExists(SESSION);
        Files.writeString(MODE, "success\n");
        String username = "t6b1_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var created = new AtomicLong(-1L);
        Throwable failure = null;
        Thread cleanupHook = new Thread(() -> discardRun(username, created), "monthly-analysis-ui-harness-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanupHook);
        try {
            String password = tokens.generate();
            bootstrap.ensureUser(username, password);
            created.set(users.findByUsername(username).orElseThrow().getId());
            String token = login(username, password);
            long accountId = initialize(token);
            jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
            long salary = categoryId(token, "INCOME", "工资");
            long shopping = categoryId(token, "EXPENSE", "购物");
            transact(token, accountId, "INCOME", "100.00", salary, "工资", "2026-10-01T08:00:00+08:00");
            transact(token, accountId, "EXPENSE", "40.00", shopping, "日用品", "2026-10-02T08:00:00+08:00");
            Files.writeString(SESSION, json.writeValueAsString(Map.of(
                    "username", username, "password", password, "accountId", accountId, "userId", created.get())));
            Files.writeString(EVIDENCE.resolve("harness.txt"), """
                    profile=test
                    port=8080
                    catalog=bookkeeping_test
                    generator=mockito
                    provider=not-dashscope
                    """);
            System.out.println("HARNESS_READY catalog=bookkeeping_test port=8080 generator=mockito accountId=" + accountId);
            while (!Files.exists(STOP)) Thread.sleep(300);
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            try {
                discardRun(username, created);
            } catch (Exception cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
            try {
                Runtime.getRuntime().removeShutdownHook(cleanupHook);
            } catch (IllegalStateException ignored) {
                // Shutdown is already in progress, so the hook owns the same cleanup.
            }
        }
    }

    private void discardRun(String username, AtomicLong created) {
        if (!discarded.compareAndSet(false, true)) return;
        IOException credentialFailure = null;
        try {
            Files.deleteIfExists(SESSION);
        } catch (IOException ex) {
            credentialFailure = ex;
        }
        long userId = created.get();
        if (userId < 0) {
            var found = users.findByUsername(username);
            if (found.isPresent()) userId = found.get().getId();
        }
        if (userId >= 0) removeCreatedUser(userId);
        if (credentialFailure != null) {
            throw new IllegalStateException("could not delete the harness credential", credentialFailure);
        }
    }

    /** Deletes one harness user from the current test catalog. Screenshots and results.json stay. */
    private void removeCreatedUser(long userId) {
        String catalog = jdbc.sql("SELECT DATABASE()").query(String.class).single();
        if (!"bookkeeping_test".equals(catalog)) {
            throw new IllegalStateException("UI harness refused cleanup schema " + catalog);
        }
        jdbc.sql("DELETE FROM analysis_generations WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM analysis_snapshots WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM analysis_quota_days WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM analysis_user_locks WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM idempotency_records WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM balance_adjustments WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM transaction_tags WHERE transaction_id IN (SELECT id FROM transactions WHERE user_id = :id)").param("id", userId).update();
        jdbc.sql("DELETE FROM transactions WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM tags WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM accounts WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", userId).update();
        jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", userId).update();
        users.deleteById(userId);
    }

    private MonthlyAnalysisGeneration answer(MonthlyAnalysisModelInput input, String prompt) throws Exception {
        assertThat(prompt).isEqualTo(MonthlyAnalysisPrompt.text());
        assertThat(input.json()).doesNotContain("99999");
        String mode = Files.readString(MODE).trim();
        long started = System.nanoTime();
        while ("delay".equals(mode) && Duration.ofNanos(System.nanoTime() - started).toSeconds() < 45) {
            Thread.sleep(200);
            mode = Files.readString(MODE).trim();
        }
        if ("timeout".equals(mode)) throw new AnalysisGenerationFailed(AnalysisGenerationFailed.Kind.TIMEOUT, null);
        if ("invalid".equals(mode)) {
            return new MonthlyAnalysisGeneration("dashscope", "qwen3.7-flash", "not-json", 1L, 1L, 2L, 5L, "harness-hidden");
        }
        String body = json.writeValueAsString(Map.of("surplusReasons", MODEL_SURPLUS, "pointsToWatch", MODEL_POINTS));
        return new MonthlyAnalysisGeneration("dashscope", "qwen3.7-flash", body, 1L, 1L, 2L, 5L, "harness-hidden");
    }

    private String login(String username, String password) throws Exception {
        var response = send("POST", "/api/v1/auth/login", null, json.writeValueAsString(Map.of("username", username, "password", password)));
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("token").asText();
    }

    private long initialize(String token) throws Exception {
        var response = send("POST", "/api/v1/accounts/initialize", token, "{\"openingBalance\":\"0.00\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body()).path("id").asLong();
    }

    private long categoryId(String token, String type, String name) throws Exception {
        var response = send("GET", "/api/v1/categories?type=" + type, token, null);
        assertThat(response.statusCode()).isEqualTo(200);
        for (var root : json.readTree(response.body()).path("items")) {
            if (root.path("name").asText().equals(name)) return root.path("id").asLong();
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
        var response = send("POST", "/api/v1/accounts/" + accountId + "/transactions", token, json.writeValueAsString(body));
        assertThat(response.statusCode()).isEqualTo(201);
    }

    private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080" + path)).timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if ("POST".equals(method) && path.contains("/transactions")) builder.header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String schemaName(String url) {
        if (url == null || !url.startsWith("jdbc:mysql://")) return "missing";
        int slash = url.indexOf('/', "jdbc:mysql://".length());
        if (slash < 0 || slash == url.length() - 1) return "missing";
        int query = url.indexOf('?', slash + 1);
        return query < 0 ? url.substring(slash + 1) : url.substring(slash + 1, query);
    }
}
