package com.limingyuan.bookkeeping.analysis.infrastructure.dashscope;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationFailed;
import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationFailed.Kind;
import com.limingyuan.bookkeeping.analysis.application.DeterministicMonthlyReport;
import com.limingyuan.bookkeeping.analysis.application.InvalidModelOutput;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisFactsService;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGeneration;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGenerator;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisModelInput;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisPrompt;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisReportGeneration;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.CategoryFact;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.KeyExpenseFact;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.UncategorizedFact;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFacts;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFacts.CategorySlice;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFacts.KeyExpenseSlice;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFacts.UncategorizedSlice;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFactsReader;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QwenMonthlyAnalysisGeneratorTest {
    private static final String SECRET = "sk-test-secret-value";
    private static final String VALID = "{\"surplusReasons\":\"收入高于支出。\",\"pointsToWatch\":\"餐饮有两笔。\"}";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicInteger hits = new AtomicInteger();
    private static final AtomicInteger redirectHits = new AtomicInteger();
    private static final AtomicReference<String> redirectAuthorization = new AtomicReference<>();
    private static final AtomicReference<String> authorization = new AtomicReference<>();
    private static final AtomicReference<String> body = new AtomicReference<>();
    private static final AtomicReference<Supplier<Reply>> handler = new AtomicReference<>();

    @BeforeAll static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(task -> {
            var thread = new Thread(task, "analysis-test-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/compatible-mode/v1/chat/completions", exchange -> {
            try {
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                hits.incrementAndGet();
                Reply reply = handler.get().get();
                byte[] payload = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (reply.requestId() != null) exchange.getResponseHeaders().set("x-request-id", reply.requestId());
                if (reply.location() != null) exchange.getResponseHeaders().set("Location", reply.location());
                exchange.sendResponseHeaders(reply.status(), payload.length);
                exchange.getResponseBody().write(payload);
            } finally {
                exchange.close();
            }
        });
        server.createContext("/redirect-sink", exchange -> {
            try {
                redirectHits.incrementAndGet();
                redirectAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] payload = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                exchange.getResponseBody().write(payload);
            } finally {
                exchange.close();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1";
    }

    @AfterAll static void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test void requestUsesTheTwoFieldSchemaAndOmitsIdentity() {
        int before = hits.get();
        handler.set(() -> new Reply(200, chat(VALID, "stop", true), "req-schema"));
        var generation = generator(Duration.ofSeconds(2)).generate(MonthlyAnalysisModelInput.from(context()), MonthlyAnalysisPrompt.text());
        JsonNode request = JSON.readTree(body.get());
        assertThat(request.path("model").asString()).isEqualTo("qwen3.7-flash");
        assertThat(request.path("enable_thinking").asBoolean()).isFalse();
        assertThat(request.path("stream").asBoolean()).isFalse();
        assertThat(request.path("max_completion_tokens").asInt()).isEqualTo(2048);
        assertThat(request.path("messages")).hasSize(2);
        assertThat(request.path("messages").path(0).path("role").asString()).isEqualTo("system");
        assertThat(request.path("messages").path(0).path("content").asString()).isEqualTo(MonthlyAnalysisPrompt.text());
        assertThat(request.path("messages").path(1).path("role").asString()).isEqualTo("user");
        assertThat(request.path("messages").path(1).path("content").asString())
                .isEqualTo(MonthlyAnalysisModelInput.from(context()).json());
        JsonNode schema = request.path("response_format");
        assertThat(schema.path("type").asString()).isEqualTo("json_schema");
        assertThat(schema.path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(schema.path("json_schema").path("schema").path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("json_schema").path("schema").path("required")).extracting(JsonNode::asString)
                .containsExactly("surplusReasons", "pointsToWatch");
        assertThat(schema.path("json_schema").path("schema").path("properties").path("surplusReasons").path("maxLength").asInt())
                .isEqualTo(2000);
        assertThat(schema.toString()).doesNotContain("monthlyOverview", "expenseStructure", "$schema");
        assertThat(body.get()).doesNotContain("accountId", "userId", "fingerprint", "424242", SECRET);
        assertThat(generation.content()).isEqualTo(VALID);
        assertThat(generation.provider()).isEqualTo("dashscope");
        assertThat(hits.get()).isEqualTo(before + 1);
    }

    @Test void authorizationIsSentAndIsAbsentFromLogs() {
        var logs = capture();
        handler.set(() -> new Reply(401, "{\"error\":{\"message\":\"" + SECRET + "\"}}", null));
        assertThatThrownBy(() -> generator(Duration.ofSeconds(2)).generate(MonthlyAnalysisModelInput.from(context()), MonthlyAnalysisPrompt.text()))
                .isInstanceOfSatisfying(AnalysisGenerationFailed.class, failure -> {
                    assertThat(failure.kind()).isEqualTo(Kind.AUTHENTICATION);
                    assertThat(failure.httpStatus()).isEqualTo(401);
                    assertThat(failure.getMessage()).doesNotContain(SECRET, "Authorization");
                });
        assertThat(authorization.get()).isEqualTo("Bearer " + SECRET);
        assertThat(logs.list).isNotEmpty().allSatisfy(event ->
                assertThat(event.getFormattedMessage()).doesNotContain(SECRET, "Authorization", "Bearer"));
        logs.stop();
    }

    @Test void successParsesContentModelUsageAndRequestId() {
        var logs = capture();
        handler.set(() -> new Reply(200, chat(VALID, "stop", true), "req-200"));
        var generation = generator(Duration.ofSeconds(2)).generate(MonthlyAnalysisModelInput.from(context()), MonthlyAnalysisPrompt.text());
        assertThat(generation.model()).isEqualTo("qwen3.7-flash");
        assertThat(generation.promptTokens()).isEqualTo(11L);
        assertThat(generation.completionTokens()).isEqualTo(22L);
        assertThat(generation.totalTokens()).isEqualTo(33L);
        assertThat(generation.providerRequestId()).isEqualTo("req-200");
        assertThat(generation.latencyMillis()).isGreaterThanOrEqualTo(0L);
        assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain(SECRET, "收入高于支出"));
        logs.stop();
    }

    @Test void missingUsageStillSucceeds() {
        handler.set(() -> new Reply(200, chat(VALID, "stop", false), null));
        var generation = generator(Duration.ofSeconds(2)).generate(MonthlyAnalysisModelInput.from(context()), MonthlyAnalysisPrompt.text());
        assertThat(generation.promptTokens()).isNull();
        assertThat(generation.completionTokens()).isNull();
        assertThat(generation.totalTokens()).isNull();
        assertThat(generation.providerRequestId()).isEqualTo("chatcmpl-body");
        assertThat(generation.content()).isEqualTo(VALID);
    }

    @Test void httpFailuresBecomeRecognizableAndAreNotRetried() {
        record Expected(int status, Kind kind) { }
        for (var expected : List.of(new Expected(401, Kind.AUTHENTICATION), new Expected(403, Kind.ACCESS_DENIED),
                new Expected(429, Kind.RATE_LIMIT), new Expected(500, Kind.PROVIDER_ERROR), new Expected(503, Kind.PROVIDER_ERROR))) {
            int before = hits.get();
            handler.set(() -> new Reply(expected.status(), "{\"error\":{\"message\":\"no\"}}", null));
            assertThatThrownBy(() -> generator(Duration.ofSeconds(2)).generate(MonthlyAnalysisModelInput.from(context()), MonthlyAnalysisPrompt.text()))
                    .isInstanceOfSatisfying(AnalysisGenerationFailed.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(expected.kind());
                        assertThat(failure.httpStatus()).isEqualTo(expected.status());
                    });
            assertThat(hits.get()).isEqualTo(before + 1);
        }
    }

    @Test void httpsEndpointIsAccepted() {
        var uri = QwenMonthlyAnalysisGenerator.endpoint("https://dashscope.example/compatible-mode/v1/", false);
        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("dashscope.example");
        assertThat(uri.getPath()).isEqualTo("/compatible-mode/v1/chat/completions");
    }

    @Test void plaintextHttpIsRejectedBeforeARequest() {
        int before = hits.get();
        var production = new QwenMonthlyAnalysisGenerator(QwenMonthlyAnalysisGenerator.httpClient(Duration.ofSeconds(1)),
                baseUrl, SECRET, "qwen3.7-flash", Duration.ofSeconds(2), 2048);
        assertThatThrownBy(() -> production.generate(MonthlyAnalysisModelInput.from(context()), MonthlyAnalysisPrompt.text()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("monthly analysis provider endpoint is not allowed");
        assertThat(hits.get()).isEqualTo(before);
    }

    @Test void loopbackHttpRemainsAvailableToTheTestFactory() {
        handler.set(() -> new Reply(200, chat(VALID, "stop", true), "req-loop"));
        var generation = generator(Duration.ofSeconds(2)).generate(MonthlyAnalysisModelInput.from(context()), MonthlyAnalysisPrompt.text());
        assertThat(generation.content()).isEqualTo(VALID);
        assertThat(authorization.get()).isEqualTo("Bearer " + SECRET);
    }

    @Test void illegalSchemeAndMissingHostAreRejected() {
        int before = hits.get();
        assertThatThrownBy(() -> QwenMonthlyAnalysisGenerator.endpoint("ftp://127.0.0.1/compatible-mode/v1", true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(SECRET);
        assertThatThrownBy(() -> QwenMonthlyAnalysisGenerator.endpoint("file:///tmp/ledger", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QwenMonthlyAnalysisGenerator.endpoint("https://", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QwenMonthlyAnalysisGenerator.endpoint("https:///compatible-mode/v1", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QwenMonthlyAnalysisGenerator.endpoint("http://example.com/compatible-mode/v1", true))
                .isInstanceOf(IllegalArgumentException.class);
        var production = new QwenMonthlyAnalysisGenerator(QwenMonthlyAnalysisGenerator.httpClient(Duration.ofSeconds(1)),
                "ftp://127.0.0.1:" + server.getAddress().getPort() + "/compatible-mode/v1", SECRET, "qwen3.7-flash",
                Duration.ofSeconds(2), 2048);
        assertThatThrownBy(() -> production.generate(MonthlyAnalysisModelInput.from(context()), MonthlyAnalysisPrompt.text()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(SECRET).hasMessageNotContaining("Authorization");
        assertThat(hits.get()).isEqualTo(before);
    }

    @Test void redirectIsNotFollowedWithCredentials() {
        redirectAuthorization.set(null);
        int sinkBefore = redirectHits.get();
        String location = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect-sink";
        handler.set(() -> new Reply(302, "", null, location));
        var logs = capture();
        try {
            assertThatThrownBy(() -> generator(Duration.ofSeconds(2)).generate(MonthlyAnalysisModelInput.from(context()), MonthlyAnalysisPrompt.text()))
                    .isInstanceOfSatisfying(AnalysisGenerationFailed.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(Kind.PROVIDER_ERROR);
                        assertThat(failure.httpStatus()).isEqualTo(302);
                        assertThat(failure.getMessage()).doesNotContain(SECRET, "Authorization");
                    });
            assertThat(authorization.get()).isEqualTo("Bearer " + SECRET);
            assertThat(redirectHits.get()).isEqualTo(sinkBefore);
            assertThat(redirectAuthorization.get()).isNull();
            assertThat(logs.list).isNotEmpty().allSatisfy(event ->
                    assertThat(event.getFormattedMessage()).doesNotContain(SECRET, "Authorization", "Bearer"));
        } finally {
            ((Logger) LoggerFactory.getLogger(QwenMonthlyAnalysisGenerator.class)).detachAppender(logs);
            logs.stop();
        }
    }

    @Test void abnormalFinishReasonDoesNotBecomeAReport() {
        var service = new MonthlyAnalysisReportGeneration(new MonthlyAnalysisFactsService(unread()), generator(Duration.ofSeconds(2)));
        for (String payload : List.of(chat(VALID, "content_filter", true), chat(VALID, "tool_calls", true),
                chat(VALID, "unexpected", true), chatWithoutFinish(VALID), chatWithNullFinish(VALID), chatWithNumericFinish(VALID))) {
            handler.set(() -> new Reply(200, payload, "req-finish"));
            assertThatThrownBy(() -> service.generate(context())).isInstanceOfSatisfying(AnalysisGenerationFailed.class,
                    failure -> assertThat(failure.kind()).isEqualTo(Kind.INVALID_RESPONSE));
        }
    }

    @Test void connectionFailureIsANetworkError() throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        var closed = generator(Duration.ofSeconds(2), "http://127.0.0.1:" + port, Duration.ofMillis(500));
        assertThatThrownBy(() -> closed.generate(MonthlyAnalysisModelInput.from(context()), MonthlyAnalysisPrompt.text()))
                .isInstanceOfSatisfying(AnalysisGenerationFailed.class, failure -> assertThat(failure.kind()).isEqualTo(Kind.NETWORK));
    }

    @Test @Timeout(5) void timeoutDoesNotBecomeAReport() {
        handler.set(() -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return new Reply(200, chat(VALID, "stop", true), "req-late");
        });
        var service = new MonthlyAnalysisReportGeneration(new MonthlyAnalysisFactsService(unread()), generator(Duration.ofMillis(300)));
        assertThatThrownBy(() -> service.generate(context()))
                .isInstanceOfSatisfying(AnalysisGenerationFailed.class, failure -> assertThat(failure.kind()).isEqualTo(Kind.TIMEOUT));
    }

    @Test void emptyAndIllegalResponsesFail() {
        handler.set(() -> new Reply(200, "", null));
        assertKind(Kind.EMPTY_RESPONSE);
        handler.set(() -> new Reply(200, "{\"choices\":[]}", null));
        assertKind(Kind.EMPTY_RESPONSE);
        handler.set(() -> new Reply(200, "not-json", null));
        assertKind(Kind.INVALID_RESPONSE);
        handler.set(() -> new Reply(200, chat("不是 JSON", "stop", true), null));
        assertKind(Kind.INVALID_OUTPUT);
    }

    @Test void truncatedOutputDoesNotBecomeAReport() {
        handler.set(() -> new Reply(200, chat(VALID, "length", true), "req-cut"));
        var service = new MonthlyAnalysisReportGeneration(new MonthlyAnalysisFactsService(unread()), generator(Duration.ofSeconds(2)));
        assertThatThrownBy(() -> service.generate(context()))
                .isInstanceOfSatisfying(AnalysisGenerationFailed.class, failure -> assertThat(failure.kind()).isEqualTo(Kind.TRUNCATED));
    }

    @Test void invalidModelOutputAndProviderTextCannotReplaceJavaSections() {
        var capturing = new CapturingGenerator();
        var service = new MonthlyAnalysisReportGeneration(new MonthlyAnalysisFactsService(new MonthlyBookkeepingFactsReader() {
            @Override public MonthlyBookkeepingFacts read(long accountId, String yearMonth) {
                assertThat(accountId).isEqualTo(424242L);
                assertThat(yearMonth).isEqualTo("2026-09");
                return facts();
            }
        }), capturing);
        capturing.content = "{\"surplusReasons\":\"模型认为净支出是999.00元。\",\"pointsToWatch\":\"模型认为结余是999.00元。\"}";
        assertThatThrownBy(() -> service.generate(424242L, "2026-09")).isInstanceOf(InvalidModelOutput.class);
        assertThat(capturing.calls).isEqualTo(1);
        capturing.content = "{\"surplusReasons\":\"收入低于支出，所以结余为负。\",\"pointsToWatch\":\"留意餐饮和购物两类支出。\"}";
        var generated = service.generate(424242L, "2026-09");
        var context = context();
        assertThat(capturing.calls).isEqualTo(2);
        assertThat(capturing.prompt).isEqualTo(MonthlyAnalysisPrompt.text());
        assertThat(capturing.input.json()).doesNotContain("accountId", "userId", "fingerprint", "424242");
        assertThat(generated.report().monthlyOverview()).isEqualTo(DeterministicMonthlyReport.monthlyOverview(context))
                .doesNotContain("999.00");
        assertThat(generated.report().expenseStructure()).isEqualTo(DeterministicMonthlyReport.expenseStructure(context))
                .contains("餐饮净额30.00元", "早餐10.00元", "购物净额10.00元");
        assertThat(generated.report().surplusReasons()).isEqualTo(DeterministicMonthlyReport.surplusExplanation(context))
                .doesNotContain("收入低于支出");
        assertThat(generated.report().pointsToWatch()).isEqualTo("留意餐饮和购物两类支出。");
        capturing.content = "{\"surplusReasons\":\"只有一句\"}";
        assertThatThrownBy(() -> service.generate(context)).isInstanceOf(InvalidModelOutput.class);
        capturing.failure = new AnalysisGenerationFailed(Kind.INVALID_OUTPUT, 200);
        assertThatThrownBy(() -> service.generate(context)).isInstanceOf(AnalysisGenerationFailed.class);
    }

    private static void assertKind(Kind kind) {
        assertThatThrownBy(() -> generator(Duration.ofSeconds(2)).generate(MonthlyAnalysisModelInput.from(context()), MonthlyAnalysisPrompt.text()))
                .isInstanceOfSatisfying(AnalysisGenerationFailed.class, failure -> assertThat(failure.kind()).isEqualTo(kind));
    }

    private static QwenMonthlyAnalysisGenerator generator(Duration timeout) {
        return generator(timeout, baseUrl, Duration.ofSeconds(1));
    }

    private static QwenMonthlyAnalysisGenerator generator(Duration timeout, String url, Duration connect) {
        return QwenMonthlyAnalysisGenerator.forLoopbackTests(QwenMonthlyAnalysisGenerator.httpClient(connect), url, SECRET,
                "qwen3.7-flash", timeout, 2048);
    }

    private static ListAppender<ILoggingEvent> capture() {
        var logger = (Logger) LoggerFactory.getLogger(QwenMonthlyAnalysisGenerator.class);
        logger.setLevel(Level.INFO);
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static String chat(String content, String finish, boolean usage) {
        ObjectNode root = JSON.createObjectNode();
        root.put("id", "chatcmpl-body");
        root.put("model", "qwen3.7-flash");
        var choice = root.putArray("choices").addObject();
        choice.put("finish_reason", finish);
        choice.putObject("message").put("role", "assistant").put("content", content);
        if (usage) {
            var tokens = root.putObject("usage");
            tokens.put("prompt_tokens", 11);
            tokens.put("completion_tokens", 22);
            tokens.put("total_tokens", 33);
        }
        return JSON.writeValueAsString(root);
    }

    private static String chatWithoutFinish(String content) {
        ObjectNode root = (ObjectNode) JSON.readTree(chat(content, "stop", true));
        ((ObjectNode) root.path("choices").path(0)).remove("finish_reason");
        return JSON.writeValueAsString(root);
    }

    private static String chatWithNullFinish(String content) {
        ObjectNode root = (ObjectNode) JSON.readTree(chat(content, "stop", true));
        ((ObjectNode) root.path("choices").path(0)).putNull("finish_reason");
        return JSON.writeValueAsString(root);
    }

    private static String chatWithNumericFinish(String content) {
        ObjectNode root = (ObjectNode) JSON.readTree(chat(content, "stop", true));
        ((ObjectNode) root.path("choices").path(0)).put("finish_reason", 1);
        return JSON.writeValueAsString(root);
    }

    private static MonthlyBookkeepingFactsReader unread() {
        return (accountId, yearMonth) -> {
            throw new IllegalStateException("this test must not read a ledger");
        };
    }

    private static MonthlyAnalysisContext context() {
        return new MonthlyAnalysisContext(424242L, "2026-09", MonthlyAnalysisContext.TIME_ZONE, MonthlyAnalysisContext.CURRENCY,
                "10.00", "40.00", "-30.00", 1, 3, List.of(new CategoryFact(3L, "工资", 1, "10.00", List.of())),
                List.of(new CategoryFact(9L, "餐饮", 2, "30.00", List.of(new CategoryFact(11L, "早餐", 1, "10.00", List.of()),
                        new CategoryFact(12L, "午餐", 1, "20.00", List.of()))), new CategoryFact(8L, "购物", 1, "10.00", List.of())),
                new UncategorizedFact(0, "0.00"), new UncategorizedFact(0, "0.00"),
                List.of(new KeyExpenseFact("EXPENSE", "餐饮 > 午餐", "午餐", "20.00", "2026-09-03T12:00:00+08:00")));
    }

    private static MonthlyBookkeepingFacts facts() {
        return new MonthlyBookkeepingFacts(424242L, "2026-09", "10.00", "40.00", "-30.00", 1, 3,
                List.of(new CategorySlice(3L, "工资", 1, "10.00", List.of())),
                List.of(new CategorySlice(9L, "餐饮", 2, "30.00", List.of(new CategorySlice(11L, "早餐", 1, "10.00", List.of()),
                        new CategorySlice(12L, "午餐", 1, "20.00", List.of()))), new CategorySlice(8L, "购物", 1, "10.00", List.of())),
                new UncategorizedSlice(0, "0.00"), new UncategorizedSlice(0, "0.00"),
                List.of(new KeyExpenseSlice("EXPENSE", "餐饮 > 午餐", "午餐", "20.00", "2026-09-03T12:00:00+08:00")));
    }

    private record Reply(int status, String body, String requestId, String location) {
        private Reply(int status, String body, String requestId) {
            this(status, body, requestId, null);
        }
    }

    private static final class CapturingGenerator implements MonthlyAnalysisGenerator {
        private MonthlyAnalysisModelInput input;
        private String prompt;
        private String content;
        private AnalysisGenerationFailed failure;
        private int calls;

        @Override public MonthlyAnalysisGeneration generate(MonthlyAnalysisModelInput input, String systemPrompt) {
            calls++;
            this.input = input;
            this.prompt = systemPrompt;
            if (failure != null) throw failure;
            return new MonthlyAnalysisGeneration("dashscope", "qwen3.7-flash", content, null, null, null, 5, null);
        }
    }
}
