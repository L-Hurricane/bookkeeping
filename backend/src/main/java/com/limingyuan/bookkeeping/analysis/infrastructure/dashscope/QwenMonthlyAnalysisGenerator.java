package com.limingyuan.bookkeeping.analysis.infrastructure.dashscope;

import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationFailed;
import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationFailed.Kind;
import com.limingyuan.bookkeeping.analysis.application.InvalidModelOutput;
import com.limingyuan.bookkeeping.analysis.application.ModelOutputValidator;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGeneration;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGenerator;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisModelInput;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** OpenAI-compatible DashScope chat completion for the two-field monthly analysis contract. One attempt, no fallback. */
final class QwenMonthlyAnalysisGenerator implements MonthlyAnalysisGenerator {
    static final String PROVIDER = "dashscope";
    private static final Logger log = LoggerFactory.getLogger(QwenMonthlyAnalysisGenerator.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient client;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int maxCompletionTokens;
    private final boolean permitLoopbackHttp;
    private final ObjectNode outputSchema;

    QwenMonthlyAnalysisGenerator(HttpClient client, String baseUrl, String apiKey, String model, Duration timeout,
            int maxCompletionTokens) {
        this(client, baseUrl, apiKey, model, timeout, maxCompletionTokens, false);
    }

    /** Local mock servers only. Production configuration must not use this. */
    static QwenMonthlyAnalysisGenerator forLoopbackTests(HttpClient client, String baseUrl, String apiKey, String model,
            Duration timeout, int maxCompletionTokens) {
        return new QwenMonthlyAnalysisGenerator(client, baseUrl, apiKey, model, timeout, maxCompletionTokens, true);
    }

    static HttpClient httpClient(Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private QwenMonthlyAnalysisGenerator(HttpClient client, String baseUrl, String apiKey, String model, Duration timeout,
            int maxCompletionTokens, boolean permitLoopbackHttp) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        if (maxCompletionTokens < 1) throw new IllegalArgumentException("max completion tokens must be positive");
        this.client = client;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.timeout = timeout;
        this.maxCompletionTokens = maxCompletionTokens;
        this.permitLoopbackHttp = permitLoopbackHttp;
        this.outputSchema = outputSchema();
    }

    @Override
    public MonthlyAnalysisGeneration generate(MonthlyAnalysisModelInput input, String systemPrompt) {
        if (input == null) throw new IllegalArgumentException("model input is required");
        return generateJson(input.json(), systemPrompt);
    }

    @Override
    public MonthlyAnalysisGeneration generateJson(String userJson, String systemPrompt) {
        if (userJson == null || userJson.isBlank()) throw new IllegalArgumentException("model input is required");
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            log.warn("monthly analysis provider is not configured");
            throw new AnalysisGenerationFailed(Kind.PROVIDER_ERROR, null);
        }
        if (systemPrompt == null || systemPrompt.isBlank()) throw new IllegalArgumentException("system prompt is required");
        URI uri = endpoint(baseUrl, permitLoopbackHttp);
        var request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(userJson, systemPrompt), StandardCharsets.UTF_8))
                .build();
        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failed(Kind.NETWORK, null, elapsed(started));
        } catch (IOException ex) {
            throw failed(transport(ex), null, elapsed(started));
        }
        int status = response.statusCode();
        if (status != 200) throw failed(httpKind(status), status, elapsed(started));
        return readSuccess(response, elapsed(started));
    }

    private String requestBody(String userJson, String systemPrompt) {
        ObjectNode request = JSON.createObjectNode();
        request.put("model", model);
        request.put("enable_thinking", false);
        request.put("stream", false);
        request.put("max_completion_tokens", maxCompletionTokens);
        var messages = request.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userJson);
        ObjectNode format = request.putObject("response_format");
        format.put("type", "json_schema");
        ObjectNode jsonSchema = format.putObject("json_schema");
        jsonSchema.put("name", "monthly_analysis_output_v1");
        jsonSchema.put("strict", true);
        ObjectNode schema = (ObjectNode) outputSchema.deepCopy();
        schema.remove("$schema");
        schema.remove("$id");
        jsonSchema.set("schema", schema);
        return JSON.writeValueAsString(request);
    }

    private MonthlyAnalysisGeneration readSuccess(HttpResponse<String> response, long latency) {
        String body = response.body();
        if (body == null || body.isBlank()) throw failed(Kind.EMPTY_RESPONSE, 200, latency);
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (RuntimeException ex) {
            throw failed(Kind.INVALID_RESPONSE, 200, latency);
        }
        if (root == null || !root.isObject() || root.hasNonNull("error")) throw failed(Kind.INVALID_RESPONSE, 200, latency);
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode() || choice.isNull() || !choice.isObject()) throw failed(Kind.EMPTY_RESPONSE, 200, latency);
        JsonNode finish = choice.get("finish_reason");
        if (finish == null || !finish.isString()) throw failed(Kind.INVALID_RESPONSE, 200, latency);
        String reason = finish.asString();
        if ("length".equals(reason)) throw failed(Kind.TRUNCATED, 200, latency);
        if (!"stop".equals(reason)) throw failed(Kind.INVALID_RESPONSE, 200, latency);
        JsonNode contentNode = choice.path("message").path("content");
        if (!contentNode.isString() || contentNode.asString().isBlank()) throw failed(Kind.EMPTY_RESPONSE, 200, latency);
        String content = contentNode.asString();
        try {
            ModelOutputValidator.parse(content);
        } catch (InvalidModelOutput ex) {
            throw failed(Kind.INVALID_OUTPUT, 200, latency);
        }
        JsonNode usage = root.get("usage");
        String returnedModel = text(root.get("model"));
        var generation = new MonthlyAnalysisGeneration(PROVIDER, returnedModel == null ? model : returnedModel, content,
                number(usage, "prompt_tokens"), number(usage, "completion_tokens"), number(usage, "total_tokens"), latency,
                requestId(response, root));
        log.info("monthly analysis provider call completed model={} latencyMs={} promptTokens={} completionTokens={} requestId={}",
                generation.model(), generation.latencyMillis(), generation.promptTokens(), generation.completionTokens(),
                generation.providerRequestId());
        return generation;
    }

    private static String requestId(HttpResponse<String> response, JsonNode root) {
        var header = response.headers().firstValue("x-request-id");
        if (header.isPresent() && !header.get().isBlank()) return header.get();
        return text(root.get("id"));
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isString() || node.asString().isBlank()) return null;
        return node.asString();
    }

    private static Long number(JsonNode usage, String name) {
        if (usage == null || !usage.isObject()) return null;
        JsonNode value = usage.get(name);
        if (value == null || !value.isNumber()) return null;
        return value.longValue();
    }

    private AnalysisGenerationFailed failed(Kind kind, Integer status, long latency) {
        log.warn("monthly analysis provider call failed kind={} status={} latencyMs={}", kind, status, latency);
        return new AnalysisGenerationFailed(kind, status);
    }

    private static Kind transport(IOException ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof HttpTimeoutException) return Kind.TIMEOUT;
        }
        return Kind.NETWORK;
    }

    private static Kind httpKind(int status) {
        return switch (status) {
            case 401 -> Kind.AUTHENTICATION;
            case 403 -> Kind.ACCESS_DENIED;
            case 429 -> Kind.RATE_LIMIT;
            default -> Kind.PROVIDER_ERROR;
        };
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    static URI endpoint(String baseUrl, boolean permitLoopbackHttp) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (!trimmed.endsWith("/chat/completions")) trimmed = trimmed + "/chat/completions";
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException ex) {
            throw denied();
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || host.isBlank() || uri.getUserInfo() != null) throw denied();
        if ("https".equalsIgnoreCase(scheme)) return uri;
        if (permitLoopbackHttp && "http".equalsIgnoreCase(scheme) && loopback(host)) return uri;
        throw denied();
    }

    private static boolean loopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static IllegalArgumentException denied() {
        return new IllegalArgumentException("monthly analysis provider endpoint is not allowed");
    }

    private static ObjectNode outputSchema() {
        try (var input = QwenMonthlyAnalysisGenerator.class.getResourceAsStream("/analysis/monthly-analysis-output-schema-v1.json")) {
            if (input == null) throw new IllegalStateException("monthly analysis output schema v1 is missing");
            JsonNode schema = JSON.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            if (!schema.isObject()) throw new IllegalStateException("monthly analysis output schema v1 is not an object");
            return (ObjectNode) schema;
        } catch (IOException ex) {
            throw new IllegalStateException("monthly analysis output schema v1 is unreadable", ex);
        }
    }
}
