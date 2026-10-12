package com.limingyuan.bookkeeping.analysis;

import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGeneration;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGenerator;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisModelInput;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisPrompt;
import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Real HTTP against the test database. The generator stays a test double and is not a production switch. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MonthlyAnalysisHttpAcceptanceTest {
    private static final String MODEL_SURPLUS = "模型写成净支出99999.00元，这不是账本事实。";
    private static final String MODEL_POINTS = "长".repeat(800);
    private static final String MODEL = "{\"surplusReasons\":\"" + MODEL_SURPLUS + "\",\"pointsToWatch\":\"" + MODEL_POINTS + "\"}";

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    @Autowired JdbcClient jdbc;
    @MockitoBean MonthlyAnalysisGenerator generator;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> content = new AtomicReference<>(MODEL);
    private final AtomicReference<CountDownLatch> entered = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> release = new AtomicReference<>();
    private final List<Long> fixtures = new ArrayList<>();
    private volatile MonthlyAnalysisModelInput lastInput;

    @BeforeEach void prepare() {
        calls.set(0);
        content.set(MODEL);
        entered.set(null);
        release.set(null);
        when(generator.generate(any(), any())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            lastInput = invocation.getArgument(0);
            assertThat((String) invocation.getArgument(1)).isEqualTo(MonthlyAnalysisPrompt.text());
            var gate = entered.get();
            if (gate != null) {
                gate.countDown();
                assertThat(release.get().await(15, TimeUnit.SECONDS)).isTrue();
            }
            return new MonthlyAnalysisGeneration("dashscope", "qwen3.7-flash", content.get(), 10L, 4L, 14L, 20L, "req-hidden");
        });
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        for (long id : fixtures) {
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
    }

    @Test void httpJourneyPersistsTheReportAndRestoresTheMatchingSnapshot() throws Exception {
        var user = newUser("analysis_http");
        long accountId = initialize(user.token(), "100.00");
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        long salary = categoryId(user.token(), "INCOME", "工资");
        long shopping = categoryId(user.token(), "EXPENSE", "购物");

        var empty = ok(get(user.token(), analysis(accountId, "2026-04")));
        assertThat(empty.path("viewState").asText()).isEqualTo("EMPTY_MONTH");
        assertThat(empty.path("facts").path("netIncome").isTextual()).isTrue();
        assertThat(empty.path("facts").path("incomeTransactionCount").asInt()).isZero();
        assertThat(empty.path("facts").path("expenseTransactionCount").asInt()).isZero();
        assertThat(empty.path("snapshot").isMissingNode()).isTrue();
        assertError(post(user.token(), analysis(accountId), Map.of("month", "2026-04", "expectedFingerprint", empty.path("fingerprint").asText())),
                409, "EMPTY_MONTH");
        assertThat(calls.get()).isZero();
        assertThat(quota(user.id())).isEqualTo(new Quota(0, 0));

        transact(user.token(), accountId, "INCOME", "1.00", salary, "工资", "2026-05-01T08:00:00+08:00");
        String invalidMonth = fingerprint(user.token(), accountId, "2026-05");
        content.set("not-json");
        assertError(post(user.token(), analysis(accountId), Map.of("month", "2026-05", "expectedFingerprint", invalidMonth)), 502, "GENERATION_FAILED");
        content.set("{\"surplusReasons\":\"只有结余\"}");
        assertError(post(user.token(), analysis(accountId), Map.of("month", "2026-05", "expectedFingerprint", invalidMonth)), 502, "GENERATION_FAILED");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(snapshotCount(user.id())).isZero();
        assertThat(quota(user.id())).isEqualTo(new Quota(0, 0));
        assertThat(ok(get(user.token(), analysis(accountId, "2026-05"))).path("viewState").asText()).isEqualTo("GENERATION_FAILED");

        transact(user.token(), accountId, "INCOME", "100.00", salary, "工资", "2026-06-01T08:00:00+08:00");
        transact(user.token(), accountId, "EXPENSE", "80.00", shopping, "日用品", "2026-06-02T08:00:00+08:00");
        transact(user.token(), accountId, "EXPENSE", "-20.00", shopping, "退款", "2026-06-03T08:00:00+08:00");
        transact(user.token(), accountId, "EXPENSE", "40.00", null, null, "2026-06-04T08:00:00+08:00");
        var seen = ok(get(user.token(), analysis(accountId, "2026-06")));
        assertThat(seen.path("viewState").asText()).isEqualTo("NO_ANALYSIS");
        assertThat(seen.path("facts").path("netIncome").asText()).isEqualTo("100.00");
        assertThat(seen.path("facts").path("netExpense").asText()).isEqualTo("100.00");
        assertThat(seen.path("facts").path("surplus").asText()).isEqualTo("0.00");
        assertThat(seen.path("facts").path("incomeTransactionCount").asInt()).isEqualTo(1);
        assertThat(seen.path("facts").path("expenseTransactionCount").asInt()).isEqualTo(3);
        assertThat(seen.path("facts").path("expenseCategories").get(0).path("name").asText()).isEqualTo("购物");
        assertThat(seen.path("facts").path("expenseCategories").get(0).path("netAmount").asText()).isEqualTo("60.00");
        JsonNode unnamed = keyExpense(seen.path("facts").path("keyExpenses"), "40.00");
        assertThat(unnamed.path("transactionType").asText()).isEqualTo("EXPENSE");
        assertThat(unnamed.path("categoryPath").isMissingNode()).isTrue();
        assertThat(unnamed.path("transactionName").isMissingNode()).isTrue();
        JsonNode refund = keyExpense(seen.path("facts").path("keyExpenses"), "-20.00");
        assertThat(refund.path("transactionType").asText()).isEqualTo("EXPENSE");
        assertThat(refund.path("transactionName").asText()).isEqualTo("退款");
        String fingerprint = seen.path("fingerprint").asText();
        content.set(MODEL);
        int beforeGenerate = calls.get();
        var created = ok(post(user.token(), analysis(accountId), Map.of("month", "2026-06", "expectedFingerprint", fingerprint)));
        assertThat(created.path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(calls.get()).isEqualTo(beforeGenerate + 1);
        assertThat(lastInput.month()).isEqualTo("2026-06");
        assertThat(lastInput.monthlySummary().netIncome()).isEqualTo("100.00");
        assertThat(lastInput.monthlySummary().netExpense()).isEqualTo("100.00");
        assertThat(lastInput.monthlySummary().surplus()).isEqualTo("0.00");
        assertThat(lastInput.json()).doesNotContain("99999");
        var report = created.path("analysis");
        long snapshotId = report.path("snapshot").path("id").asLong();
        assertThat(report.path("viewState").asText()).isEqualTo("CURRENT");
        assertThat(report.path("snapshot").path("stale").asBoolean()).isFalse();
        assertThat(report.path("snapshot").path("monthlyOverview").asText())
                .isEqualTo("2026年6月账本概况：净收入100.00元，1笔；净支出100.00元，3笔；结余0.00元。");
        assertThat(report.path("snapshot").path("expenseStructure").asText())
                .isEqualTo("支出净额100.00元。购物净额60.00元，2笔，占支出净额60.00%。未分类支出净额40.00元，1笔，占支出净额40.00%。");
        assertThat(report.path("snapshot").path("surplusReasons").asText())
                .isEqualTo("2026年6月结余为0.00元。净收入100.00元，净支出100.00元。\n" + MODEL_SURPLUS);
        assertThat(report.path("snapshot").path("pointsToWatch").asText()).isEqualTo(MODEL_POINTS);
        assertThat(report.path("snapshot").path("monthlyOverview").asText()).doesNotContain("99999");
        assertThat(report.path("snapshot").path("expenseStructure").asText()).doesNotContain("99999");
        assertThat(created.toString()).doesNotContain("req-hidden", "Bearer", user.token());
        var stored = snapshot(snapshotId);
        assertThat(stored.overview()).isEqualTo(report.path("snapshot").path("monthlyOverview").asText());
        assertThat(stored.structure()).isEqualTo(report.path("snapshot").path("expenseStructure").asText());
        assertThat(stored.surplus()).isEqualTo(report.path("snapshot").path("surplusReasons").asText());
        assertThat(stored.points()).isEqualTo(MODEL_POINTS);
        assertThat(stored.requestId()).isEqualTo("req-hidden");
        assertThat(quota(user.id())).isEqualTo(new Quota(0, 1));

        var restored = ok(get(user.token(), analysis(accountId, "2026-06")));
        assertThat(restored.path("viewState").asText()).isEqualTo("CURRENT");
        assertThat(restored.path("snapshot").path("id").asLong()).isEqualTo(snapshotId);
        assertThat(restored.path("snapshot").path("pointsToWatch").asText()).isEqualTo(MODEL_POINTS);
        int beforeReuse = calls.get();
        var reused = ok(post(user.token(), analysis(accountId), Map.of("month", "2026-06", "expectedFingerprint", fingerprint)));
        assertThat(reused.path("outcome").asText()).isEqualTo("REUSED");
        assertThat(reused.path("analysis").path("snapshot").path("id").asLong()).isEqualTo(snapshotId);
        assertThat(calls.get()).isEqualTo(beforeReuse);
        assertThat(quota(user.id())).isEqualTo(new Quota(0, 1));

        long added = transact(user.token(), accountId, "EXPENSE", "1.00", shopping, "追加", "2026-06-10T08:00:00+08:00");
        var stale = ok(get(user.token(), analysis(accountId, "2026-06")));
        assertThat(stale.path("viewState").asText()).isEqualTo("STALE");
        assertThat(stale.path("snapshot").path("id").asLong()).isEqualTo(snapshotId);
        assertThat(stale.path("snapshot").path("stale").asBoolean()).isTrue();
        assertThat(stale.path("fingerprint").asText()).isNotEqualTo(fingerprint);
        int beforeRefresh = calls.get();
        assertError(post(user.token(), analysis(accountId), Map.of("month", "2026-06", "expectedFingerprint", fingerprint)), 409, "REFRESH_REQUIRED");
        assertThat(calls.get()).isEqualTo(beforeRefresh);
        String refreshed = stale.path("fingerprint").asText();
        var regenerated = ok(post(user.token(), analysis(accountId), Map.of("month", "2026-06", "expectedFingerprint", refreshed)));
        assertThat(regenerated.path("outcome").asText()).isEqualTo("SUCCESS");
        long newerId = regenerated.path("analysis").path("snapshot").path("id").asLong();
        assertThat(newerId).isGreaterThan(snapshotId);
        assertThat(quota(user.id())).isEqualTo(new Quota(0, 2));

        assertThat(call(user.token(), "DELETE", "/api/v1/accounts/" + accountId + "/transactions/" + added, null).status()).isEqualTo(204);
        var matched = ok(get(user.token(), analysis(accountId, "2026-06")));
        assertThat(matched.path("viewState").asText()).isEqualTo("CURRENT");
        assertThat(matched.path("fingerprint").asText()).isEqualTo(fingerprint);
        assertThat(matched.path("snapshot").path("id").asLong()).isEqualTo(snapshotId);
        assertThat(matched.path("snapshot").path("stale").asBoolean()).isFalse();
        assertThat(matched.path("snapshot").path("monthlyOverview").asText())
                .isEqualTo("2026年6月账本概况：净收入100.00元，1笔；净支出100.00元，3笔；结余0.00元。");

        transact(user.token(), accountId, "INCOME", "12.00", salary, "工资", "2026-07-01T08:00:00+08:00");
        String incomeOnly = fingerprint(user.token(), accountId, "2026-07");
        var incomeReport = ok(post(user.token(), analysis(accountId), Map.of("month", "2026-07", "expectedFingerprint", incomeOnly)));
        assertThat(incomeReport.path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(incomeReport.path("analysis").path("snapshot").path("monthlyOverview").asText())
                .isEqualTo("2026年7月账本概况：净收入12.00元，1笔；净支出0.00元，0笔；结余12.00元。本月账本没有支出记录。");
        assertThat(incomeReport.path("analysis").path("snapshot").path("expenseStructure").asText()).isEqualTo("本月账本没有支出记录。");
        assertThat(quota(user.id())).isEqualTo(new Quota(0, 3));

        transact(user.token(), accountId, "EXPENSE", "3.00", shopping, "日用品", "2026-08-02T08:00:00+08:00");
        String fourth = fingerprint(user.token(), accountId, "2026-08");
        int beforeFourth = calls.get();
        assertError(post(user.token(), analysis(accountId), Map.of("month", "2026-08", "expectedFingerprint", fourth)), 409, "QUOTA_EXCEEDED");
        assertThat(calls.get()).isEqualTo(beforeFourth);
        assertThat(quota(user.id())).isEqualTo(new Quota(0, 3));

        var other = newUser("analysis_http_other");
        assertError(get(other.token(), analysis(accountId, "2026-06")), 404, "RESOURCE_NOT_FOUND");
        assertError(post(other.token(), analysis(accountId), Map.of("month", "2026-06", "expectedFingerprint", fingerprint)), 404, "RESOURCE_NOT_FOUND");
        assertThat(calls.get()).isEqualTo(beforeFourth);
    }

    @Test void httpExpenseOnlyWaitsForOneGeneratorAndAManualGet() throws Exception {
        var user = newUser("analysis_http_expense");
        long accountId = initialize(user.token(), "0.00");
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        long shopping = categoryId(user.token(), "EXPENSE", "购物");
        transact(user.token(), accountId, "EXPENSE", "-6.00", shopping, "退款", "2026-08-02T08:00:00+08:00");
        String fingerprint = fingerprint(user.token(), accountId, "2026-08");
        content.set(MODEL);
        var gate = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        entered.set(gate);
        release.set(resume);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> post(user.token(), analysis(accountId), Map.of("month", "2026-08", "expectedFingerprint", fingerprint)));
            assertThat(gate.await(15, TimeUnit.SECONDS)).isTrue();
            int duringCall = calls.get();
            assertError(post(user.token(), analysis(accountId), Map.of("month", "2026-08", "expectedFingerprint", fingerprint)), 409, "IN_PROGRESS");
            var waiting = ok(get(user.token(), analysis(accountId, "2026-08")));
            assertThat(waiting.path("viewState").asText()).isEqualTo("GENERATION_IN_PROGRESS");
            assertThat(waiting.path("facts").path("netExpense").asText()).isEqualTo("-6.00");
            assertThat(waiting.path("facts").path("surplus").asText()).isEqualTo("6.00");
            assertThat(calls.get()).isEqualTo(duringCall);
            resume.countDown();
            var created = ok(first.get(15, TimeUnit.SECONDS));
            assertThat(created.path("outcome").asText()).isEqualTo("SUCCESS");
            assertThat(created.path("analysis").path("snapshot").path("monthlyOverview").asText())
                    .isEqualTo("2026年8月账本概况：净收入0.00元，0笔；净支出-6.00元，1笔；结余6.00元。本月账本没有收入记录。");
            assertThat(created.path("analysis").path("snapshot").path("expenseStructure").asText())
                    .isEqualTo("支出净额-6.00元。购物净额-6.00元，1笔。本月没有未分类支出。")
                    .doesNotContain("%");
            assertThat(created.path("analysis").path("snapshot").path("surplusReasons").asText())
                    .startsWith("2026年8月结余为6.00元。净收入0.00元，净支出-6.00元。\n");
            var again = ok(get(user.token(), analysis(accountId, "2026-08")));
            assertThat(again.path("viewState").asText()).isEqualTo("CURRENT");
            assertThat(again.path("snapshot").path("id").asLong()).isEqualTo(created.path("analysis").path("snapshot").path("id").asLong());
            assertThat(quota(user.id())).isEqualTo(new Quota(0, 1));
            assertThat(snapshotCount(user.id())).isEqualTo(1);
        } finally {
            resume.countDown();
            pool.shutdownNow();
        }
    }

    @Test void httpBalanceAdjustmentAloneLeavesTheMonthEmpty() throws Exception {
        var user = newUser("analysis_http_adjustment");
        long accountId = initialize(user.token(), "100.00");
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        var before = ok(get(user.token(), analysis(accountId, "2026-03")));
        assertThat(before.path("viewState").asText()).isEqualTo("EMPTY_MONTH");
        String fingerprint = before.path("fingerprint").asText();
        var adjusted = call(user.token(), "POST", "/api/v1/accounts/" + accountId + "/balance-adjustments",
                json.writeValueAsString(Map.of("targetBalance", "80.00", "businessTime", "2026-03-15T08:00:00+08:00")));
        assertThat(adjusted.status()).isEqualTo(201);
        assertThat(adjusted.body().path("created").asBoolean()).isTrue();
        var after = ok(get(user.token(), analysis(accountId, "2026-03")));
        assertThat(after.path("viewState").asText()).isEqualTo("EMPTY_MONTH");
        assertThat(after.path("facts").path("incomeTransactionCount").asInt()).isZero();
        assertThat(after.path("facts").path("expenseTransactionCount").asInt()).isZero();
        assertThat(after.path("facts").path("netIncome").asText()).isEqualTo("0.00");
        assertThat(after.path("facts").path("netExpense").asText()).isEqualTo("0.00");
        assertThat(after.path("fingerprint").asText()).isEqualTo(fingerprint);
        assertThat(after.path("snapshot").isMissingNode()).isTrue();
        assertError(post(user.token(), analysis(accountId), Map.of("month", "2026-03", "expectedFingerprint", fingerprint)),
                409, "EMPTY_MONTH");
        assertThat(calls.get()).isZero();
        assertThat(snapshotCount(user.id())).isZero();
        assertThat(quota(user.id())).isEqualTo(new Quota(0, 0));
    }

    @Test void httpNegativeIncomeStaysOnTheIncomeSide() throws Exception {
        var user = newUser("analysis_http_income");
        long accountId = initialize(user.token(), "0.00");
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        long salary = categoryId(user.token(), "INCOME", "工资");
        long shopping = categoryId(user.token(), "EXPENSE", "购物");
        transact(user.token(), accountId, "INCOME", "150.00", salary, "工资", "2026-09-01T08:00:00+08:00");
        transact(user.token(), accountId, "INCOME", "-100.00", salary, "冲减", "2026-09-02T08:00:00+08:00");
        transact(user.token(), accountId, "EXPENSE", "20.00", shopping, "日用品", "2026-09-03T08:00:00+08:00");
        var seen = ok(get(user.token(), analysis(accountId, "2026-09")));
        assertThat(seen.path("facts").path("netIncome").asText()).isEqualTo("50.00");
        assertThat(seen.path("facts").path("netExpense").asText()).isEqualTo("20.00");
        assertThat(seen.path("facts").path("surplus").asText()).isEqualTo("30.00");
        assertThat(seen.path("facts").path("incomeTransactionCount").asInt()).isEqualTo(2);
        assertThat(seen.path("facts").path("expenseTransactionCount").asInt()).isEqualTo(1);
        assertThat(seen.path("facts").path("incomeCategories").get(0).path("name").asText()).isEqualTo("工资");
        assertThat(seen.path("facts").path("incomeCategories").get(0).path("netAmount").asText()).isEqualTo("50.00");
        assertThat(seen.path("facts").path("incomeCategories").get(0).path("transactionCount").asInt()).isEqualTo(2);
        assertThat(seen.path("facts").path("expenseCategories").get(0).path("netAmount").asText()).isEqualTo("20.00");
        content.set(MODEL);
        var created = ok(post(user.token(), analysis(accountId),
                Map.of("month", "2026-09", "expectedFingerprint", seen.path("fingerprint").asText())));
        assertThat(created.path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(lastInput.monthlySummary().netIncome()).isEqualTo("50.00");
        assertThat(lastInput.monthlySummary().netExpense()).isEqualTo("20.00");
        assertThat(lastInput.monthlySummary().surplus()).isEqualTo("30.00");
        assertThat(lastInput.monthlySummary().incomeTransactionCount()).isEqualTo(2);
        assertThat(lastInput.monthlySummary().expenseTransactionCount()).isEqualTo(1);
        assertThat(lastInput.json()).doesNotContain("99999");
        var snapshot = created.path("analysis").path("snapshot");
        assertThat(snapshot.path("monthlyOverview").asText())
                .isEqualTo("2026年9月账本概况：净收入50.00元，2笔；净支出20.00元，1笔；结余30.00元。");
        assertThat(snapshot.path("expenseStructure").asText())
                .isEqualTo("支出净额20.00元。购物净额20.00元，1笔，占支出净额100.00%。本月没有未分类支出。");
        assertThat(snapshot.path("surplusReasons").asText())
                .startsWith("2026年9月结余为30.00元。净收入50.00元，净支出20.00元。\n");
        assertThat(snapshot.path("monthlyOverview").asText()).doesNotContain("99999");
        assertThat(snapshot.path("expenseStructure").asText()).doesNotContain("99999");
        assertThat(snapshot.path("surplusReasons").asText()).contains(MODEL_SURPLUS);
    }

    private String fingerprint(String token, long accountId, String month) throws Exception {
        return ok(get(token, analysis(accountId, month))).path("fingerprint").asText();
    }

    private static JsonNode keyExpense(JsonNode items, String amount) {
        for (JsonNode item : items) if (item.path("signedAmount").asText().equals(amount)) return item;
        throw new AssertionError(amount);
    }

    private static String analysis(long accountId, String month) {
        return "/api/v1/accounts/" + accountId + "/monthly-analysis?month=" + month;
    }

    private static String analysis(long accountId) {
        return "/api/v1/accounts/" + accountId + "/monthly-analysis";
    }

    private JsonNode ok(Exchange exchange) {
        assertThat(exchange.status()).isEqualTo(200);
        return exchange.body();
    }

    private void assertError(Exchange exchange, int status, String code) {
        assertThat(exchange.status()).isEqualTo(status);
        assertThat(exchange.body().path("error").path("code").asText()).isEqualTo(code);
        assertThat(exchange.raw()).doesNotContain("req-hidden", "fencingToken", "Authorization");
    }

    private Exchange get(String token, String path) throws Exception {
        return call(token, "GET", path, null);
    }

    private Exchange post(String token, String path, Map<String, String> body) throws Exception {
        return call(token, "POST", path, json.writeValueAsString(body));
    }

    private Exchange call(String token, String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if ("POST".equals(method) && (path.contains("/transactions") || path.contains("/balance-adjustments"))) {
            builder.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String raw = response.body() == null ? "" : response.body();
        JsonNode parsed = raw.isBlank() ? json.nullNode() : json.readTree(raw);
        return new Exchange(response.statusCode(), parsed, raw);
    }

    private long transact(String token, long accountId, String type, String amount, Long categoryId, String name, String time) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("type", type);
        body.put("amount", amount);
        if (name != null) body.put("name", name);
        if (categoryId != null) body.put("categoryId", categoryId);
        body.put("tagIds", List.of());
        body.put("businessTime", time);
        var created = call(token, "POST", "/api/v1/accounts/" + accountId + "/transactions", json.writeValueAsString(body));
        assertThat(created.status()).isEqualTo(201);
        return created.body().path("id").asLong();
    }

    private long initialize(String token, String opening) throws Exception {
        var created = call(token, "POST", "/api/v1/accounts/initialize", "{\"openingBalance\":\"" + opening + "\"}");
        assertThat(created.status()).isEqualTo(201);
        return created.body().path("id").asLong();
    }

    private long categoryId(String token, String type, String name) throws Exception {
        var listed = ok(get(token, "/api/v1/categories?type=" + type));
        for (JsonNode root : listed.path("items")) {
            if (root.path("name").asText().equals(name)) return root.path("id").asLong();
            for (JsonNode child : root.path("children")) if (child.path("name").asText().equals(name)) return child.path("id").asLong();
        }
        throw new AssertionError(name);
    }

    private TestUser newUser(String prefix) throws Exception {
        String name = prefix + "_" + UUID.randomUUID();
        String password = tokens.generate();
        bootstrap.ensureUser(name, password);
        long id = users.findByUsername(name).orElseThrow().getId();
        fixtures.add(id);
        var logged = ok(call(null, "POST", "/api/v1/auth/login", json.writeValueAsString(Map.of("username", name, "password", password))));
        return new TestUser(id, logged.path("token").asText());
    }

    private Quota quota(long userId) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(reserved_count), 0), COALESCE(SUM(consumed_count), 0)
                FROM analysis_quota_days WHERE user_id = :id
                """).param("id", userId).query((rs, row) -> new Quota(rs.getInt(1), rs.getInt(2))).single();
    }

    private int snapshotCount(long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM analysis_snapshots WHERE user_id = :id").param("id", userId).query(Integer.class).single();
    }

    private Stored snapshot(long id) {
        return jdbc.sql("""
                SELECT monthly_overview, expense_structure, surplus_reasons, points_to_watch, provider_request_id
                FROM analysis_snapshots WHERE id = :id
                """).param("id", id).query((rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))).single();
    }

    private record Exchange(int status, JsonNode body, String raw) { }
    private record Quota(int reserved, int consumed) { }
    private record TestUser(long id, String token) { }
    private record Stored(String overview, String structure, String surplus, String points, String requestId) { }
}
