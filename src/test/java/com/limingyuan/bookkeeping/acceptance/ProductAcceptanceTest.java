package com.limingyuan.bookkeeping.acceptance;

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

/** Product freeze §5: the seven acceptance scenarios and the published five-scenario totals. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class ProductAcceptanceTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    @Autowired JdbcClient jdbc;
    String token;
    long accountId;
    long breakfast;
    long lunch;
    long dinner;
    long interest;
    List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() throws Exception {
        var user = newUser("accept");
        token = user.token();
        accountId = initialize("100.00");
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        breakfast = categoryId("EXPENSE", "早餐");
        lunch = categoryId("EXPENSE", "午餐");
        dinner = categoryId("EXPENSE", "晚餐");
        interest = categoryId("INCOME", "利息");
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

    @Test void sevenScenariosMatchThePublishedSeptemberResult() throws Exception {
        long breakfastId = transact("EXPENSE", "5.00", breakfast, "2026-09-01T08:00:00+08:00", List.of());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE id = :id").param("id", breakfastId).query(Long.class).single()).isEqualTo(1);
        long takeout = tag("EXPENSE", "外卖");
        long lunchId = transact("EXPENSE", "30.00", lunch, "2026-09-12T12:30:00+08:00", List.of(takeout));
        transact("INCOME", "0.50", interest, "2026-09-30T23:59:59+08:00", List.of());
        var created = adjust("60.50", "2026-09-30T23:59:59+08:00");
        assertThat(created.path("created").asBoolean()).isTrue();
        assertThat(created.path("adjustment").path("adjustmentAmount").asText()).isEqualTo("-5.00");
        assertThat(created.path("currentBalanceAfter").asText()).isEqualTo("60.50");

        assertThat(balance()).isEqualTo("60.50");
        var summary = monthly();
        assertThat(summary.path("transactionCount").asInt()).isEqualTo(3);
        assertThat(summary.path("netIncome").asText()).isEqualTo("0.50");
        assertThat(summary.path("netExpense").asText()).isEqualTo("35.00");
        assertThat(summary.path("surplus").asText()).isEqualTo("-34.50");
        var ledger = ledger();
        assertThat(ledger.path("items")).hasSize(4);
        assertThat(ledger.path("summary").path("netIncome").asText()).isEqualTo("0.50");
        assertThat(ledger.path("summary").path("netExpense").asText()).isEqualTo("35.00");
        assertThat(ledger.path("summary").path("surplus").asText()).isEqualTo("-34.50");
        assertThat(ledger.path("currentBalance").asText()).isEqualTo("60.50");
        assertThat(kinds(ledger)).contains("BALANCE_ADJUSTMENT");
        var dining = categories("EXPENSE").path("items").get(0);
        assertThat(dining.path("categoryName").asText()).isEqualTo("餐饮");
        assertThat(dining.path("amount").asText()).isEqualTo("35.00");
        assertThat(tags("EXPENSE").path("items").get(0).path("tagName").asText()).isEqualTo("外卖");
        assertThat(tags("EXPENSE").path("items").get(0).path("amount").asText()).isEqualTo("30.00");

        var before = categories("EXPENSE").path("items").get(0);
        assertThat(child(before, "午餐").path("amount").asText()).isEqualTo("30.00");
        assertThat(child(before, "午餐").path("transactionCount").asInt()).isEqualTo(1);
        assertThat(child(before, "早餐").path("amount").asText()).isEqualTo("5.00");
        assertThat(named(before.path("children"), "晚餐")).isNull();
        long order = jdbc.sql("SELECT first_save_order FROM transactions WHERE id = :id").param("id", lunchId).query(Long.class).single();
        String typeBefore = jdbc.sql("SELECT type FROM transactions WHERE id = :id").param("id", lunchId).query(String.class).single();
        mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + lunchId).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount", "30.00", "categoryId", dinner, "tagIds", List.of(takeout),
                                "businessTime", "2026-09-12T12:30:00+08:00"))))
                .andExpect(status().isOk());
        var detail = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions/" + lunchId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        var edited = json.readTree(detail.getResponse().getContentAsString());
        assertThat(edited.path("id").asLong()).isEqualTo(lunchId);
        assertThat(edited.path("type").asText()).isEqualTo("EXPENSE").isEqualTo(typeBefore);
        assertThat(edited.path("category").path("id").asLong()).isEqualTo(dinner);
        assertThat(edited.path("category").path("name").asText()).isEqualTo("晚餐");
        assertThat(edited.path("tags")).extracting(tag -> tag.path("name").asText()).containsExactly("外卖");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE account_id = :id").param("id", accountId).query(Long.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT category_id FROM transactions WHERE id = :id").param("id", lunchId).query(Long.class).single()).isEqualTo(dinner);
        assertThat(jdbc.sql("SELECT tag_id FROM transaction_tags WHERE transaction_id = :id").param("id", lunchId).query(Long.class).single()).isEqualTo(takeout);
        assertThat(jdbc.sql("SELECT first_save_order FROM transactions WHERE id = :id").param("id", lunchId).query(Long.class).single()).isEqualTo(order);
        assertThat(balance()).isEqualTo("60.50");
        var afterSummary = monthly();
        assertThat(afterSummary.path("transactionCount").asInt()).isEqualTo(3);
        assertThat(afterSummary.path("netIncome").asText()).isEqualTo("0.50");
        assertThat(afterSummary.path("netExpense").asText()).isEqualTo("35.00");
        assertThat(afterSummary.path("surplus").asText()).isEqualTo("-34.50");
        var after = categories("EXPENSE").path("items").get(0);
        assertThat(after.path("categoryName").asText()).isEqualTo("餐饮");
        assertThat(after.path("amount").asText()).isEqualTo("35.00");
        assertThat(after.path("transactionCount").asInt()).isEqualTo(2);
        assertThat(named(after.path("children"), "午餐")).isNull();
        assertThat(child(after, "晚餐").path("amount").asText()).isEqualTo("30.00");
        assertThat(child(after, "晚餐").path("transactionCount").asInt()).isEqualTo(1);
        assertThat(child(after, "早餐").path("amount").asText()).isEqualTo("5.00");
        assertThat(child(after, "早餐").path("transactionCount").asInt()).isEqualTo(1);
        var tagStats = tags("EXPENSE").path("items").get(0);
        assertThat(tagStats.path("tagName").asText()).isEqualTo("外卖");
        assertThat(tagStats.path("amount").asText()).isEqualTo("30.00");
        assertThat(tagStats.path("transactionCount").asInt()).isEqualTo(1);
    }

    private JsonNode child(JsonNode parent, String name) {
        var found = named(parent.path("children"), name);
        assertThat(found).as(name).isNotNull();
        return found;
    }

    private static JsonNode named(JsonNode items, String name) {
        for (var item : items) if (item.path("categoryName").asText().equals(name)) return item;
        return null;
    }

    private String balance() throws Exception {
        var result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("items").get(0).path("currentBalance").asText();
    }

    private JsonNode monthly() throws Exception {
        return read("/statistics/monthly", null);
    }

    private JsonNode ledger() throws Exception {
        var result = mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token).param("month", "2026-09"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode categories(String type) throws Exception {
        return read("/statistics/categories", type);
    }

    private JsonNode tags(String type) throws Exception {
        return read("/statistics/tags", type);
    }

    private JsonNode read(String path, String type) throws Exception {
        var request = get("/api/v1/accounts/" + accountId + path).header("Authorization", "Bearer " + token).param("month", "2026-09");
        if (type != null) request.param("type", type);
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private List<String> kinds(JsonNode ledger) {
        var kinds = new ArrayList<String>();
        ledger.path("items").forEach(item -> kinds.add(item.path("kind").asText()));
        return kinds;
    }

    private long transact(String type, String amount, long categoryId, String time, List<Long> tagIds) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", type, "amount", amount, "categoryId", categoryId, "tagIds", tagIds, "businessTime", time))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private JsonNode adjust(String target, String time) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/" + accountId + "/balance-adjustments").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("targetBalance", target, "businessTime", time))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private long initialize(String opening) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"" + opening + "\"}"))
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
