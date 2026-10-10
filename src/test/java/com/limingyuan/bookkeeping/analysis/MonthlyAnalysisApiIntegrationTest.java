package com.limingyuan.bookkeeping.analysis;

import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationFailed;
import com.limingyuan.bookkeeping.analysis.application.AnalysisGenerationStore;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGeneration;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisGenerator;
import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class MonthlyAnalysisApiIntegrationTest {
    private static final String MODEL = "{\"surplusReasons\":\"结余来自账本\",\"pointsToWatch\":\"留意本月支出\"}";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    @Autowired JdbcClient jdbc;
    @Autowired AnalysisGenerationStore store;
    @MockitoBean MonthlyAnalysisGenerator generator;
    String token;
    long userId;
    long accountId;
    long shopping;
    List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() throws Exception {
        var user = newUser("analysis_api");
        token = user.token();
        userId = user.id();
        accountId = initialize(token, "100.00");
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        shopping = categoryId("EXPENSE", "购物");
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

    @Test void getDoesNotCallTheModelAndAnEmptyMonthHasNoAnalysis() throws Exception {
        var empty = read(getAnalysis("2026-10"));
        assertThat(empty.path("viewState").asText()).isEqualTo("EMPTY_MONTH");
        assertThat(empty.path("emptyMonth").asBoolean()).isTrue();
        assertThat(empty.path("fingerprint").asText()).startsWith("1:");
        assertThat(empty.path("facts").path("netIncome").asText()).isEqualTo("0.00");
        assertThat(empty.path("facts").path("incomeTransactionCount").asInt()).isZero();
        assertThat(empty.path("snapshot").isMissingNode()).isTrue();
        transact("2026-10-02T08:00:00+08:00");
        var open = read(getAnalysis("2026-10"));
        assertThat(open.path("viewState").asText()).isEqualTo("NO_ANALYSIS");
        assertThat(open.path("emptyMonth").asBoolean()).isFalse();
        assertThat(open.path("facts").path("expenseTransactionCount").asInt()).isEqualTo(1);
        assertThat(open.path("fingerprint").asText()).isNotEqualTo(empty.path("fingerprint").asText());
        verify(generator, never()).generate(any(), any());
    }

    @Test void emptyMonthDoesNotReserveQuota() throws Exception {
        String fingerprint = read(getAnalysis("2026-10")).path("fingerprint").asText();
        assertError(postAnalysis("2026-10", fingerprint), 409, "EMPTY_MONTH");
        assertThat(quota()).isEqualTo(new Quota(0, 0));
        assertThat(snapshotCount()).isZero();
        verify(generator, never()).generate(any(), any());
    }

    @Test void missingExpectedFingerprintIsRejected() throws Exception {
        transact("2026-10-02T08:00:00+08:00");
        assertError(mvc.perform(post("/api/v1/accounts/" + accountId + "/monthly-analysis").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"month\":\"2026-10\"}")).andReturn(), 400, "VALIDATION_FAILED");
        assertError(postAnalysis("2026-10", " "), 400, "VALIDATION_FAILED");
        verify(generator, never()).generate(any(), any());
        assertThat(quota()).isEqualTo(new Quota(0, 0));
    }

    @Test void changedFactsRequireRefreshBeforeGeneration() throws Exception {
        transact("2026-10-02T08:00:00+08:00");
        String seen = read(getAnalysis("2026-10")).path("fingerprint").asText();
        transact("2026-10-03T08:00:00+08:00");
        assertError(postAnalysis("2026-10", seen), 409, "REFRESH_REQUIRED");
        assertThat(quota()).isEqualTo(new Quota(0, 0));
        assertThat(snapshotCount()).isZero();
        verify(generator, never()).generate(any(), any());
    }

    @Test void firstGenerationPersistsFourSectionsAndReuseDoesNotCallAgain() throws Exception {
        transact("2026-10-02T08:00:00+08:00");
        when(generator.generate(any(), any())).thenReturn(sample());
        String fingerprint = read(getAnalysis("2026-10")).path("fingerprint").asText();
        var created = read(postAnalysis("2026-10", fingerprint));
        assertThat(created.path("outcome").asText()).isEqualTo("SUCCESS");
        var analysis = created.path("analysis");
        assertThat(analysis.path("viewState").asText()).isEqualTo("CURRENT");
        assertThat(analysis.path("snapshot").path("stale").asBoolean()).isFalse();
        assertThat(analysis.path("snapshot").path("monthlyOverview").asText()).contains("净收入");
        assertThat(analysis.path("snapshot").path("expenseStructure").asText()).isNotBlank();
        assertThat(analysis.path("snapshot").path("surplusReasons").asText()).contains("结余来自账本");
        assertThat(analysis.path("snapshot").path("pointsToWatch").asText()).isEqualTo("留意本月支出");
        assertThat(analysis.path("snapshot").path("provider").asText()).isEqualTo("dashscope");
        assertThat(analysis.path("snapshot").path("model").asText()).isEqualTo("qwen3.7-flash");
        assertThat(created.toString()).doesNotContain("req-hidden", "fencingToken");
        assertThat(quota()).isEqualTo(new Quota(0, 1));
        var again = read(postAnalysis("2026-10", fingerprint));
        assertThat(again.path("outcome").asText()).isEqualTo("REUSED");
        assertThat(again.path("analysis").path("snapshot").path("id").asLong())
                .isEqualTo(analysis.path("snapshot").path("id").asLong());
        assertThat(quota()).isEqualTo(new Quota(0, 1));
        verify(generator).generate(any(), any());
    }

    @Test void oneScopeCallsTheModelOnce() throws Exception {
        transact("2026-10-02T08:00:00+08:00");
        String fingerprint = read(getAnalysis("2026-10")).path("fingerprint").asText();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(generator.generate(any(), any())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            return sample();
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> postAnalysis("2026-10", fingerprint));
            assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
            assertError(postAnalysis("2026-10", fingerprint), 409, "IN_PROGRESS");
            release.countDown();
            var created = read(first.get(15, TimeUnit.SECONDS));
            assertThat(created.path("outcome").asText()).isEqualTo("SUCCESS");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        verify(generator).generate(any(), any());
        assertThat(snapshotCount()).isEqualTo(1);
        assertThat(quota()).isEqualTo(new Quota(0, 1));
    }

    @Test void fourthNewAnalysisIsRejected() throws Exception {
        when(generator.generate(any(), any())).thenReturn(sample());
        for (int month = 1; month <= 3; month++) {
            String yearMonth = "2026-0" + month;
            transact(yearMonth + "-02T08:00:00+08:00");
            String fingerprint = read(getAnalysis(yearMonth)).path("fingerprint").asText();
            assertThat(read(postAnalysis(yearMonth, fingerprint)).path("outcome").asText()).isEqualTo("SUCCESS");
        }
        transact("2026-04-02T08:00:00+08:00");
        String fourth = read(getAnalysis("2026-04")).path("fingerprint").asText();
        assertError(postAnalysis("2026-04", fourth), 409, "QUOTA_EXCEEDED");
        assertThat(quota()).isEqualTo(new Quota(0, 3));
        assertThat(snapshotCount()).isEqualTo(3);
        verify(generator, org.mockito.Mockito.times(3)).generate(any(), any());
    }

    @Test void providerFailureTimeoutAndInvalidOutputReleaseTheReservation() throws Exception {
        transact("2026-10-02T08:00:00+08:00");
        String fingerprint = read(getAnalysis("2026-10")).path("fingerprint").asText();
        when(generator.generate(any(), any()))
                .thenThrow(new AnalysisGenerationFailed(AnalysisGenerationFailed.Kind.TIMEOUT, null))
                .thenReturn(new MonthlyAnalysisGeneration("dashscope", "qwen3.7-flash", "not-json", null, null, null, 4L, null))
                .thenThrow(new AnalysisGenerationFailed(AnalysisGenerationFailed.Kind.NETWORK, null));
        assertError(postAnalysis("2026-10", fingerprint), 504, "GENERATION_FAILED");
        assertError(postAnalysis("2026-10", fingerprint), 502, "GENERATION_FAILED");
        assertError(postAnalysis("2026-10", fingerprint), 502, "GENERATION_FAILED");
        assertThat(snapshotCount()).isZero();
        assertThat(quota()).isEqualTo(new Quota(0, 0));
        var body = read(getAnalysis("2026-10"));
        assertThat(body.path("viewState").asText()).isEqualTo("GENERATION_FAILED");
        assertThat(body.path("snapshot").isMissingNode()).isTrue();
    }

    @Test void failureKeepsThePreviousSnapshot() throws Exception {
        transact("2026-10-02T08:00:00+08:00");
        when(generator.generate(any(), any())).thenReturn(sample());
        String first = read(getAnalysis("2026-10")).path("fingerprint").asText();
        long id = read(postAnalysis("2026-10", first)).path("analysis").path("snapshot").path("id").asLong();
        String overview = read(getAnalysis("2026-10")).path("snapshot").path("monthlyOverview").asText();
        transact("2026-10-04T08:00:00+08:00");
        String changed = read(getAnalysis("2026-10")).path("fingerprint").asText();
        when(generator.generate(any(), any())).thenThrow(new AnalysisGenerationFailed(AnalysisGenerationFailed.Kind.PROVIDER_ERROR, 500));
        assertError(postAnalysis("2026-10", changed), 502, "GENERATION_FAILED");
        var body = read(getAnalysis("2026-10"));
        assertThat(body.path("viewState").asText()).isEqualTo("STALE");
        assertThat(body.path("snapshot").path("id").asLong()).isEqualTo(id);
        assertThat(body.path("snapshot").path("stale").asBoolean()).isTrue();
        assertThat(body.path("snapshot").path("monthlyOverview").asText()).isEqualTo(overview);
        assertThat(quota()).isEqualTo(new Quota(0, 1));
    }

    @Test void expiredPermitCannotSettleSuccess() throws Exception {
        transact("2026-10-02T08:00:00+08:00");
        String fingerprint = read(getAnalysis("2026-10")).path("fingerprint").asText();
        var calls = new AtomicInteger();
        when(generator.generate(any(), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                jdbc.sql("UPDATE analysis_generations SET lease_expires_at_utc = '2000-01-01 00:00:00.000000' WHERE user_id = :id")
                        .param("id", userId).update();
            }
            return sample();
        });
        assertError(postAnalysis("2026-10", fingerprint), 502, "GENERATION_FAILED");
        assertThat(snapshotCount()).isZero();
        var recovered = read(postAnalysis("2026-10", fingerprint));
        assertThat(recovered.path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(snapshotCount()).isEqualTo(1);
        assertThat(quota()).isEqualTo(new Quota(0, 1));
    }

    @Test void ledgerChangeLeavesTheSnapshotAndMarksItStale() throws Exception {
        transact("2026-10-02T08:00:00+08:00");
        when(generator.generate(any(), any())).thenReturn(sample());
        String fingerprint = read(getAnalysis("2026-10")).path("fingerprint").asText();
        String overview = read(postAnalysis("2026-10", fingerprint)).path("analysis").path("snapshot").path("monthlyOverview").asText();
        transact("2026-10-05T08:00:00+08:00");
        var body = read(getAnalysis("2026-10"));
        assertThat(body.path("viewState").asText()).isEqualTo("STALE");
        assertThat(body.path("snapshot").path("stale").asBoolean()).isTrue();
        assertThat(body.path("snapshot").path("monthlyOverview").asText()).isEqualTo(overview);
        assertThat(body.path("fingerprint").asText()).isNotEqualTo(body.path("snapshot").path("fingerprint").asText());
        assertThat(body.path("facts").path("expenseTransactionCount").asInt()).isEqualTo(2);
    }

    @Test void expiredLeaseIsNotActiveInProgress() throws Exception {
        transact("2026-10-02T08:00:00+08:00");
        String fingerprint = read(getAnalysis("2026-10")).path("fingerprint").asText();
        assertThat(store.acquire(userId, "2026-10", fingerprint)).isInstanceOf(
                com.limingyuan.bookkeeping.analysis.application.PermitAcquisition.Granted.class);
        jdbc.sql("UPDATE analysis_generations SET lease_expires_at_utc = '2000-01-01 00:00:00.000000' WHERE user_id = :id")
                .param("id", userId).update();
        var body = read(getAnalysis("2026-10"));
        assertThat(body.path("viewState").asText()).isEqualTo("NO_ANALYSIS");
        assertThat(body.path("generation").path("state").asText()).isEqualTo("IN_PROGRESS");
        assertThat(body.path("generation").path("active").asBoolean()).isFalse();
        verify(generator, never()).generate(any(), any());
    }

    @Test void anotherUserCannotReadOrGenerate() throws Exception {
        transact("2026-10-02T08:00:00+08:00");
        var other = newUser("analysis_other");
        assertError(mvc.perform(get("/api/v1/accounts/" + accountId + "/monthly-analysis").header("Authorization", "Bearer " + other.token())
                .param("month", "2026-10")).andReturn(), 404, "RESOURCE_NOT_FOUND");
        assertError(mvc.perform(post("/api/v1/accounts/" + accountId + "/monthly-analysis").header("Authorization", "Bearer " + other.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("month", "2026-10", "expectedFingerprint", "1:other")))).andReturn(),
                404, "RESOURCE_NOT_FOUND");
        assertError(mvc.perform(get("/api/v1/accounts/" + accountId + "/monthly-analysis").param("month", "2026-10")).andReturn(),
                401, "SESSION_INVALID");
        verify(generator, never()).generate(any(), any());
    }

    private MvcResult getAnalysis(String month) throws Exception {
        return mvc.perform(get("/api/v1/accounts/" + accountId + "/monthly-analysis").header("Authorization", "Bearer " + token)
                .param("month", month)).andExpect(status().isOk()).andReturn();
    }

    private MvcResult postAnalysis(String month, String fingerprint) throws Exception {
        return mvc.perform(post("/api/v1/accounts/" + accountId + "/monthly-analysis").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("month", month, "expectedFingerprint", fingerprint)))).andReturn();
    }

    private JsonNode read(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        var error = json.readTree(result.getResponse().getContentAsString()).path("error");
        assertThat(error.path("code").asText()).isEqualTo(code);
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("fencingToken", "req-hidden", "Authorization");
    }

    private Quota quota() {
        return jdbc.sql("""
                SELECT COALESCE(SUM(reserved_count), 0), COALESCE(SUM(consumed_count), 0)
                FROM analysis_quota_days WHERE user_id = :id
                """).param("id", userId).query((rs, row) -> new Quota(rs.getInt(1), rs.getInt(2))).single();
    }

    private int snapshotCount() {
        return jdbc.sql("SELECT COUNT(*) FROM analysis_snapshots WHERE user_id = :id").param("id", userId).query(Integer.class).single();
    }

    private static MonthlyAnalysisGeneration sample() {
        return new MonthlyAnalysisGeneration("dashscope", "qwen3.7-flash", MODEL, 10L, 4L, 14L, 20L, "req-hidden");
    }

    private long transact(String time) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("type", "EXPENSE");
        body.put("amount", "12.00");
        body.put("name", "日用品");
        body.put("categoryId", shopping);
        body.put("tagIds", List.of());
        body.put("businessTime", time);
        mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated());
        return 0L;
    }

    private long initialize(String bearer, String opening) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"" + opening + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private long categoryId(String type, String name) throws Exception {
        var result = mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + token).param("type", type))
                .andExpect(status().isOk()).andReturn();
        for (var root : json.readTree(result.getResponse().getContentAsString()).path("items")) {
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
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", name, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return new TestUser(id, json.readTree(result.getResponse().getContentAsString()).path("token").asText());
    }

    private record Quota(int reserved, int consumed) { }
    private record TestUser(long id, String token) { }
}
