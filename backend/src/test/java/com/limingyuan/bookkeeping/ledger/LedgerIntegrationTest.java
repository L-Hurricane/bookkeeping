package com.limingyuan.bookkeeping.ledger;

import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class LedgerIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    @Autowired JdbcClient jdbc;
    long userId;
    String token;
    long accountId;
    long incomeCategory;
    long expenseCategory;
    long expenseTag;
    List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() throws Exception {
        var user = newUser("phase6");
        userId = user.id();
        token = user.token();
        accountId = initialize(token);
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        incomeCategory = categoryId("INCOME", "工资");
        expenseCategory = categoryId("EXPENSE", "早餐");
        expenseTag = tag("EXPENSE", "外卖");
    }

    @AfterEach void cleanup() {
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

    @Test void emptyMonthStillReturnsCurrentBalance() throws Exception {
        transact("INCOME", "10.00", null, "2099-01-01T00:00:00+08:00", List.of());
        var ledger = ledger("2026-09");
        assertThat(ledger.path("month").asText()).isEqualTo("2026-09");
        assertThat(ledger.path("items")).isEmpty();
        assertThat(ledger.path("summary").path("transactionCount").asInt()).isZero();
        assertThat(ledger.path("summary").path("netIncome").asText()).isEqualTo("0.00");
        assertThat(ledger.path("summary").path("netExpense").asText()).isEqualTo("0.00");
        assertThat(ledger.path("summary").path("surplus").asText()).isEqualTo("0.00");
        assertThat(ledger.path("currentBalance").asText()).isEqualTo("10.00");
        assertThat(ledger("2099-01").path("items")).hasSize(1);
    }

    @Test void mixedEntriesSortByBusinessTimeThenSharedSaveOrder() throws Exception {
        long earlier = transact("EXPENSE", "4.00", "  ", "2026-09-02T08:00:00+08:00", List.of(expenseTag));
        long later = transact("INCOME", "-1.00", "工资到账", "2026-09-03T08:00:00+08:00", List.of());
        adjust("20.00", "2026-09-03T08:00:00+08:00");
        var items = ledger("2026-09").path("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).path("kind").asText()).isEqualTo("BALANCE_ADJUSTMENT");
        assertThat(items.get(0).path("displayName").asText()).isEqualTo("余额调整");
        assertThat(items.get(0).path("amount").asText()).isEqualTo("25.00");
        assertThat(items.get(0).path("transactionType").isMissingNode()).isTrue();
        assertThat(items.get(0).path("category").isMissingNode()).isTrue();
        assertThat(items.get(0).path("tags").isMissingNode()).isTrue();
        assertThat(items.get(1).path("id").asLong()).isEqualTo(later);
        assertThat(items.get(1).path("kind").asText()).isEqualTo("TRANSACTION");
        assertThat(items.get(1).path("transactionType").asText()).isEqualTo("INCOME");
        assertThat(items.get(1).path("displayName").asText()).isEqualTo("工资到账");
        assertThat(items.get(2).path("id").asLong()).isEqualTo(earlier);
        assertThat(items.get(2).path("displayName").asText()).isEqualTo("早餐");
        assertThat(items.get(2).path("category").path("name").asText()).isEqualTo("早餐");
        assertThat(items.get(2).path("tags")).hasSize(1);
        assertThat(items.get(2).path("tags").get(0).path("name").asText()).isEqualTo("外卖");
        var summary = ledger("2026-09").path("summary");
        assertThat(summary.path("transactionCount").asInt()).isEqualTo(2);
        assertThat(summary.path("netIncome").asText()).isEqualTo("-1.00");
        assertThat(summary.path("netExpense").asText()).isEqualTo("4.00");
        assertThat(summary.path("surplus").asText()).isEqualTo("-5.00");
        assertThat(ledger("2026-09").path("currentBalance").asText()).isEqualTo("20.00");
        assertThat(ledger("2026-08").path("items")).isEmpty();
    }

    @Test void monthBoundaryAndEditedBusinessTimeKeepSaveOrder() throws Exception {
        long id = transact("EXPENSE", "2.00", "边界", "2026-09-30T23:59:59+08:00", List.of());
        transact("INCOME", "1.00", "下月", "2026-10-01T00:00:00+08:00", List.of());
        assertThat(ids(ledger("2026-09"))).containsExactly(id);
        assertThat(ids(ledger("2026-10"))).hasSize(1);
        long order = jdbc.sql("SELECT first_save_order FROM transactions WHERE id = :id").param("id", id).query(Long.class).single();
        mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount", "2.00", "name", "边界", "categoryId", expenseCategory,
                                "tagIds", List.of(), "businessTime", "2026-10-01T00:00:00+08:00"))))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT first_save_order FROM transactions WHERE id = :id").param("id", id).query(Long.class).single()).isEqualTo(order);
        assertThat(ledger("2026-09").path("items")).isEmpty();
        var october = ledger("2026-10").path("items");
        assertThat(october).hasSize(2);
        assertThat(october.get(0).path("displayName").asText()).isEqualTo("下月");
        assertThat(october.get(1).path("id").asLong()).isEqualTo(id);
    }

    @Test void onlyAdjustmentAndForeignAccountStayIsolated() throws Exception {
        adjust("5.00", "2026-11-01T08:00:00+08:00");
        var items = ledger("2026-11").path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("kind").asText()).isEqualTo("BALANCE_ADJUSTMENT");
        assertThat(ledger("2026-11").path("summary").path("transactionCount").asInt()).isZero();
        var other = newUser("other-ledger");
        long otherAccount = initialize(other.token());
        mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + other.token()).param("month", "2026-11"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/accounts/" + otherAccount + "/ledger").header("Authorization", "Bearer " + other.token()).param("month", "2026-11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token).param("month", "2026-13"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private JsonNode ledger(String month) throws Exception {
        var result = mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token).param("month", month))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private List<Long> ids(JsonNode ledger) {
        var ids = new ArrayList<Long>();
        ledger.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private long transact(String type, String amount, String name, String time, List<Long> tagIds) throws Exception {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", type);
        map.put("amount", amount);
        if (name != null) map.put("name", name);
        map.put("categoryId", "INCOME".equals(type) ? incomeCategory : expenseCategory);
        map.put("tagIds", tagIds);
        map.put("businessTime", time);
        var result = mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(map)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private void adjust(String target, String time) throws Exception {
        mvc.perform(post("/api/v1/accounts/" + accountId + "/balance-adjustments").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("targetBalance", target, "businessTime", time))))
                .andExpect(status().isCreated());
    }

    private long initialize(String bearer) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"0\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private long categoryId(String type, String name) throws Exception {
        var result = mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + token).param("type", type))
                .andExpect(status().isOk()).andReturn();
        for (var root : json.readTree(result.getResponse().getContentAsString()).path("items")) {
            if (root.path("name").asText().equals(name)) return root.path("id").asLong();
            for (var child : root.path("children")) if (child.path("name").asText().equals(name)) return child.path("id").asLong();
        }
        throw new AssertionError(name);
    }

    private long tag(String type, String name) throws Exception {
        var result = mvc.perform(post("/api/v1/tags").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("type", type, "name", name))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
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
