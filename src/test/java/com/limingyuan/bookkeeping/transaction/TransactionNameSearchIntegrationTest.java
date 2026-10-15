package com.limingyuan.bookkeeping.transaction;

import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import com.limingyuan.bookkeeping.transaction.application.TransactionService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class TransactionNameSearchIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    @Autowired JdbcClient jdbc;
    long userId;
    String token;
    long accountId;
    long expenseCategory;
    final List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() throws Exception {
        var user = newUser("search");
        userId = user.id();
        token = user.token();
        accountId = initialize(token);
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        expenseCategory = categoryId("EXPENSE", "早餐", token);
    }

    @AfterEach void cleanup() {
        for (long id : fixtures) {
            jdbc.sql("DELETE FROM idempotency_records WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM balance_adjustments WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM transaction_tags WHERE transaction_id IN (SELECT id FROM transactions WHERE user_id = :id)")
                    .param("id", id).update();
            jdbc.sql("DELETE FROM transactions WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM tags WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM accounts WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
            users.deleteById(id);
        }
    }

    @Test void searchesStoredNamesAcrossHistoryWithContiguousSubstringRules() throws Exception {
        var combo = create(body("EXPENSE", "10", "牛奶&鸡蛋&面包", expenseCategory, "2026-09-10T08:00:00+08:00"), token);
        var august = create(body("EXPENSE", "11", "牛奶早餐", expenseCategory, "2026-08-15T08:00:00+08:00"), token);
        var unnamed = create(body("EXPENSE", "12", null, expenseCategory, "2026-09-11T08:00:00+08:00"), token);
        assertThat(searchIds("牛奶", token)).containsExactlyInAnyOrder(combo.path("id").asLong(), august.path("id").asLong());
        assertThat(searchIds("鸡蛋", token)).containsExactly(combo.path("id").asLong());
        assertThat(searchIds("奶&鸡", token)).containsExactly(combo.path("id").asLong());
        assertThat(searchIds("奶鸡", token)).isEmpty();
        assertThat(searchIds("早餐", token)).containsExactly(august.path("id").asLong());
        assertThat(searchIds("早餐", token)).doesNotContain(unnamed.path("id").asLong());
    }

    @Test void escapesLikeMetacharactersAndSortsByBusinessTimeThenSaveOrder() throws Exception {
        var literal = create(body("EXPENSE", "1", "100%纯牛奶", expenseCategory, "2026-09-20T12:00:00+08:00"), token);
        var underscore = create(body("EXPENSE", "2", "a_b", expenseCategory, "2026-09-20T12:00:00+08:00"), token);
        var backslash = create(body("EXPENSE", "3", "x\\y", expenseCategory, "2026-09-19T12:00:00+08:00"), token);
        assertThat(searchIds("%", token)).containsExactly(literal.path("id").asLong());
        assertThat(searchIds("_", token)).containsExactly(underscore.path("id").asLong());
        assertThat(searchIds("\\", token)).containsExactly(backslash.path("id").asLong());
        assertThat(searchIds("不存在", token)).isEmpty();
    }

    @Test void sortsByBusinessTimeThenSaveOrder() throws Exception {
        var older = create(body("EXPENSE", "4", "排序甲", expenseCategory, "2026-09-24T12:00:00+08:00"), token);
        var newer = create(body("EXPENSE", "5", "排序乙", expenseCategory, "2026-09-24T12:00:00+08:00"), token);
        var previousMonth = create(body("EXPENSE", "6", "排序丙", expenseCategory, "2026-09-23T12:00:00+08:00"), token);
        assertThat(searchIds("排序", token)).containsExactly(
                newer.path("id").asLong(),
                older.path("id").asLong(),
                previousMonth.path("id").asLong());
    }

    @Test void enforcesOwnershipAuthenticationAndValidation() throws Exception {
        mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions/search").param("name", "牛奶"))
                .andExpect(status().isUnauthorized());
        var other = newUser("search-other");
        long foreignAccount = initialize(other.token());
        mvc.perform(get("/api/v1/accounts/" + foreignAccount + "/transactions/search").header("Authorization", "Bearer " + token)
                        .param("name", "牛奶"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions/search").header("Authorization", "Bearer " + token)
                        .param("name", " "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions/search").header("Authorization", "Bearer " + token)
                        .param("name", "x".repeat(256)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test void likePatternEscapesForSqlBinding() {
        assertThat(TransactionService.likeContainsPattern("a%b_c\\d")).isEqualTo("%a\\%b\\_c\\\\d%");
    }

    private List<Long> searchIds(String keyword, String bearer) throws Exception {
        var result = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions/search")
                        .header("Authorization", "Bearer " + bearer).param("name", keyword))
                .andExpect(status().isOk()).andReturn();
        var ids = new ArrayList<Long>();
        json.readTree(result.getResponse().getContentAsString()).path("items").forEach(node -> ids.add(node.path("id").asLong()));
        return ids;
    }

    private JsonNode create(String payload, String bearer) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + bearer)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String body(String type, String amount, String name, long categoryId, String time) throws Exception {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", type);
        map.put("amount", amount);
        if (name != null) map.put("name", name);
        map.put("categoryId", categoryId);
        map.put("tagIds", List.of());
        map.put("businessTime", time);
        return json.writeValueAsString(map);
    }

    private long initialize(String bearer) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"0\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private long categoryId(String type, String name, String bearer) throws Exception {
        var result = mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + bearer).param("type", type))
                .andExpect(status().isOk()).andReturn();
        var items = json.readTree(result.getResponse().getContentAsString()).path("items");
        for (var root : items) {
            if (root.path("name").asText().equals(name)) return root.path("id").asLong();
            for (var child : root.path("children")) if (child.path("name").asText().equals(name)) return child.path("id").asLong();
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
