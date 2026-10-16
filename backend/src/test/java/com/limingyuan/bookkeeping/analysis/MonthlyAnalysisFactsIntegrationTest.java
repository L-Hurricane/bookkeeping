package com.limingyuan.bookkeeping.analysis;

import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisFactsService;
import com.limingyuan.bookkeeping.analysis.domain.AnalysisContextFingerprint;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext;
import com.limingyuan.bookkeeping.analysis.domain.MonthlyAnalysisContext.CategoryFact;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class MonthlyAnalysisFactsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    @Autowired JdbcClient jdbc;
    @Autowired MonthlyAnalysisFactsService facts;
    String token;
    long userId;
    long accountId;
    long salary;
    long bonus;
    long breakfast;
    long lunch;
    long shopping;
    List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() throws Exception {
        var user = newUser("analysis");
        token = user.token();
        userId = user.id();
        accountId = initialize(token, "100.00");
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        salary = categoryId("INCOME", "工资");
        bonus = categoryId("INCOME", "奖金");
        breakfast = categoryId("EXPENSE", "早餐");
        lunch = categoryId("EXPENSE", "午餐");
        shopping = categoryId("EXPENSE", "购物");
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        for (long id : fixtures) {
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

    @Test void signedIncomeAndExpenseStayOnTheirOwnSides() throws Exception {
        transact("INCOME", "10.00", salary, "工资", "2026-09-01T08:00:00+08:00");
        transact("INCOME", "-2.50", bonus, "冲减", "2026-09-02T08:00:00+08:00");
        transact("EXPENSE", "4.00", shopping, "snack", "2026-09-03T08:00:00+08:00");
        transact("EXPENSE", "-10.00", shopping, "refund", "2026-09-02T09:00:00+08:00");
        var context = build("2026-09");
        assertThat(context.timeZone()).isEqualTo("Asia/Shanghai");
        assertThat(context.netIncome()).isEqualTo("7.50");
        assertThat(context.netExpense()).isEqualTo("-6.00");
        assertThat(context.surplus()).isEqualTo("13.50");
        assertThat(context.incomeTransactionCount()).isEqualTo(2);
        assertThat(context.expenseTransactionCount()).isEqualTo(2);
        assertThat(context.incomeCategories()).extracting(CategoryFact::name).containsExactly("工资", "奖金");
        assertThat(context.incomeCategories()).extracting(CategoryFact::netAmount).containsExactly("10.00", "-2.50");
        assertThat(context.keyExpenses()).extracting(item -> item.transactionName()).containsExactly("refund", "snack");
        assertThat(context.keyExpenses()).extracting(item -> item.signedAmount()).containsExactly("-10.00", "4.00");
        assertThat(context.keyExpenses()).extracting(item -> item.transactionType()).containsOnly("EXPENSE");
        assertThat(context.keyExpenses()).extracting(item -> item.categoryPath()).containsOnly("购物");
    }

    @Test void parentIncludesChildrenAndChildlessParentKeepsDirectBookings() throws Exception {
        transact("EXPENSE", "5.00", breakfast, "豆浆", "2026-09-04T08:00:00+08:00");
        transact("EXPENSE", "-1.00", lunch, "退回", "2026-09-05T08:00:00+08:00");
        transact("EXPENSE", "20.00", shopping, "日用品", "2026-09-06T08:00:00+08:00");
        var context = build("2026-09");
        assertThat(context.expenseCategories()).extracting(CategoryFact::name).containsExactly("餐饮", "购物");
        var dining = context.expenseCategories().get(0);
        assertThat(dining.netAmount()).isEqualTo("4.00");
        assertThat(dining.transactionCount()).isEqualTo(2);
        assertThat(dining.children()).extracting(CategoryFact::name).containsExactly("早餐", "午餐");
        assertThat(dining.children()).extracting(CategoryFact::netAmount).containsExactly("5.00", "-1.00");
        var direct = context.expenseCategories().get(1);
        assertThat(direct.netAmount()).isEqualTo("20.00");
        assertThat(direct.children()).isEmpty();
        assertThat(context.netExpense()).isEqualTo("24.00");
        assertThat(context.keyExpenses()).extracting(item -> item.categoryPath()).contains("餐饮 > 早餐", "购物");
    }

    @Test void uncategorizedCanNetToZeroWhileKeepingCount() throws Exception {
        transact("EXPENSE", "8.00", null, "未记一", "2026-09-08T08:00:00+08:00");
        transact("EXPENSE", "-8.00", null, "未记二", "2026-09-09T08:00:00+08:00");
        var context = build("2026-09");
        assertThat(context.expenseCategories()).isEmpty();
        assertThat(context.uncategorizedExpense().netAmount()).isEqualTo("0.00");
        assertThat(context.uncategorizedExpense().transactionCount()).isEqualTo(2);
        assertThat(context.uncategorizedIncome().transactionCount()).isZero();
        assertThat(context.netExpense()).isEqualTo("0.00");
        assertThat(context.expenseTransactionCount()).isEqualTo(2);
        assertThat(context.keyExpenses()).extracting(item -> item.categoryPath()).containsExactly(null, null);
    }

    @Test void incomeOnlyAndExpenseOnlyMonths() throws Exception {
        transact("INCOME", "12.00", salary, "工资", "2026-09-01T08:00:00+08:00");
        var incomeOnly = build("2026-09");
        assertThat(incomeOnly.incomeTransactionCount()).isEqualTo(1);
        assertThat(incomeOnly.expenseTransactionCount()).isZero();
        assertThat(incomeOnly.netExpense()).isEqualTo("0.00");
        assertThat(incomeOnly.surplus()).isEqualTo("12.00");
        assertThat(incomeOnly.expenseCategories()).isEmpty();
        assertThat(incomeOnly.keyExpenses()).isEmpty();

        transact("EXPENSE", "3.00", shopping, "日用品", "2026-10-02T08:00:00+08:00");
        var expenseOnly = build("2026-10");
        assertThat(expenseOnly.incomeTransactionCount()).isZero();
        assertThat(expenseOnly.netIncome()).isEqualTo("0.00");
        assertThat(expenseOnly.expenseTransactionCount()).isEqualTo(1);
        assertThat(expenseOnly.surplus()).isEqualTo("-3.00");
        assertThat(expenseOnly.incomeCategories()).isEmpty();
        assertThat(expenseOnly.keyExpenses()).extracting(item -> item.transactionName()).containsExactly("日用品");
    }

    @Test void keyExpensesUseAbsoluteAmountThenBusinessTimeThenFirstSaveOrder() throws Exception {
        transact("INCOME", "500.00", salary, "工资", "2026-09-01T09:00:00+08:00");
        transact("EXPENSE", "10.00", shopping, "small-ten", "2026-09-01T08:00:00+08:00");
        transact("EXPENSE", "80.00", shopping, "day-four", "2026-09-04T08:00:00+08:00");
        transact("EXPENSE", "80.00", shopping, "later-time", "2026-09-05T12:00:00+08:00");
        transact("EXPENSE", "80.00", shopping, "earlier-time", "2026-09-05T08:00:00+08:00");
        transact("EXPENSE", "90.00", shopping, "early-save", "2026-09-07T08:00:00+08:00");
        transact("EXPENSE", "90.00", shopping, "late-save", "2026-09-07T08:00:00+08:00");
        transact("EXPENSE", "-200.00", shopping, "refund", "2026-09-06T08:00:00+08:00");
        var context = build("2026-09");
        assertThat(context.keyExpenses()).extracting(item -> item.transactionName())
                .containsExactly("refund", "late-save", "early-save", "later-time", "earlier-time");
        assertThat(context.keyExpenses()).extracting(item -> item.signedAmount())
                .containsExactly("-200.00", "90.00", "90.00", "80.00", "80.00");
        assertThat(context.keyExpenses().get(1).businessTime()).isEqualTo("2026-09-07T08:00:00+08:00");
        assertThat(context.keyExpenses().get(2).businessTime()).isEqualTo("2026-09-07T08:00:00+08:00");
        assertThat(context.keyExpenses().get(3).businessTime()).isEqualTo("2026-09-05T12:00:00+08:00");
        assertThat(context.keyExpenses().get(4).businessTime()).isEqualTo("2026-09-05T08:00:00+08:00");
        assertThat(context.expenseTransactionCount()).isEqualTo(7);
    }

    @Test void fingerprintIsStableUntilBookkeepingFactsChange() throws Exception {
        transact("INCOME", "10.00", salary, "工资", "2026-09-01T08:00:00+08:00");
        var first = build("2026-09");
        var second = build("2026-09");
        assertThat(first.fingerprint()).startsWith(AnalysisContextFingerprint.CONTRACT_VERSION + ":").hasSize(66);
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.fingerprint()).isEqualTo(AnalysisContextFingerprint.of(first));

        long expenseId = transact("EXPENSE", "1.00", shopping, "日用品", "2026-09-02T08:00:00+08:00");
        var changed = build("2026-09");
        assertThat(changed.fingerprint()).isNotEqualTo(first.fingerprint());

        long tagId = tag("EXPENSE", "超市");
        mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + expenseId).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount", "1.00", "name", "日用品", "categoryId", shopping,
                                "tagIds", List.of(tagId), "businessTime", "2026-09-02T08:00:00+08:00"))))
                .andExpect(status().isOk());
        assertThat(build("2026-09").fingerprint()).isEqualTo(changed.fingerprint());

        mvc.perform(post("/api/v1/accounts/" + accountId + "/balance-adjustments").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("targetBalance", "50.00", "businessTime", "2026-09-03T08:00:00+08:00"))))
                .andExpect(status().isCreated());
        assertThat(build("2026-09").fingerprint()).isEqualTo(changed.fingerprint());
    }

    @Test void anotherUsersAccountIsRejected() throws Exception {
        var other = newUser("analysis-other");
        var previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new TrustedUserContext.Identity(other.id(), 0L), null, List.of()));
        try {
            assertThatThrownBy(() -> facts.build(accountId, "2026-09"))
                    .isInstanceOf(ApiFailure.class)
                    .satisfies(error -> {
                        var failure = (ApiFailure) error;
                        assertThat(failure.status()).isEqualTo(404);
                        assertThat(failure.code()).isEqualTo("RESOURCE_NOT_FOUND");
                    });
        } finally {
            if (previous == null) SecurityContextHolder.clearContext();
            else SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    @Test void naturalMonthUsesBeijingBusinessTimeBounds() throws Exception {
        transact("EXPENSE", "1.00", shopping, "八月", "2026-08-31T23:59:59+08:00");
        transact("EXPENSE", "2.00", shopping, "月末", "2026-09-30T23:59:59+08:00");
        transact("EXPENSE", "4.00", shopping, "下月", "2026-10-01T00:00:00+08:00");
        var september = build("2026-09");
        assertThat(september.expenseTransactionCount()).isEqualTo(1);
        assertThat(september.netExpense()).isEqualTo("2.00");
        assertThat(september.keyExpenses()).extracting(item -> item.transactionName()).containsExactly("月末");
        assertThat(build("2026-08").keyExpenses()).extracting(item -> item.transactionName()).containsExactly("八月");
        assertThat(build("2026-10").keyExpenses()).extracting(item -> item.transactionName()).containsExactly("下月");
        assertThat(build("2026-11").expenseTransactionCount()).isZero();
        assertThat(build("2026-11").incomeTransactionCount()).isZero();
    }

    private MonthlyAnalysisContext build(String month) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new TrustedUserContext.Identity(userId, 0L), null, List.of()));
        try {
            return facts.build(accountId, month);
        } finally {
            if (previous == null) SecurityContextHolder.clearContext();
            else SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    private long transact(String type, String amount, Long categoryId, String name, String time) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("type", type);
        body.put("amount", amount);
        body.put("name", name);
        body.put("categoryId", categoryId);
        body.put("tagIds", List.of());
        body.put("businessTime", time);
        var result = mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private long tag(String type, String name) throws Exception {
        var result = mvc.perform(post("/api/v1/tags").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("type", type, "name", name))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private long initialize(String bearer, String opening) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"" + opening + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private long categoryId(String type, String name) throws Exception {
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/categories")
                        .header("Authorization", "Bearer " + token).param("type", type))
                .andExpect(status().isOk()).andReturn();
        for (var root : json.readTree(result.getResponse().getContentAsString()).path("items")) {
            if (root.path("name").asText().equals(name)) return root.path("id").asLong();
            for (JsonNode child : root.path("children")) if (child.path("name").asText().equals(name)) return child.path("id").asLong();
        }
        throw new AssertionError(name);
    }

    private record TestUser(long id, String token) { }

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
}
