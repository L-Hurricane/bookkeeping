package com.limingyuan.bookkeeping.category;

import com.limingyuan.bookkeeping.category.application.CategoryService;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class CategoryManagementIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    @Autowired JdbcClient jdbc;
    @Autowired CategoryService categories;
    @Autowired PlatformTransactionManager transactions;
    long userId;
    String token;
    long accountId;
    List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() throws Exception {
        var user = newUser();
        userId = user.id();
        token = user.token();
        accountId = initialize("100.00");
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        for (long id : fixtures) {
            jdbc.sql("DELETE FROM idempotency_records WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM transaction_tags WHERE transaction_id IN (SELECT id FROM transactions WHERE user_id = :id)")
                    .param("id", id).update();
            jdbc.sql("DELETE FROM transactions WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM accounts WHERE user_id = :id").param("id", id).update();
            for (int pass = 0; pass < 3; pass++) {
                jdbc.sql("""
                        DELETE FROM categories
                        WHERE user_id = :id AND parent_id IS NOT NULL
                          AND id NOT IN (
                            SELECT parent_id FROM (
                              SELECT parent_id FROM categories
                              WHERE user_id = :id AND parent_id IS NOT NULL
                            ) parents
                          )
                        """).param("id", id).update();
            }
            jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
            users.deleteById(id);
        }
    }

    @Test void createsRenamesAndMovesWithinThreeLevels() throws Exception {
        long breakfast = categoryId("EXPENSE", "早餐");
        long food = categoryId("EXPENSE", "餐饮");
        var created = send("/api/v1/categories", Map.of("type", "EXPENSE", "name", " 豆浆 ", "parentId", breakfast));
        assertThat(created.path("id").asLong()).isPositive();
        assertThat(created.path("name").asText()).isEqualTo("豆浆");
        assertThat(created.path("bookable").asBoolean()).isTrue();
        long soy = created.path("id").asLong();
        var listed = categoryList("EXPENSE");
        var breakfastNode = named(named(listed, "餐饮").path("children"), "早餐");
        assertThat(named(breakfastNode.path("children"), "豆浆").path("id").asLong()).isEqualTo(soy);
        postStatus("/api/v1/categories", Map.of("type", "EXPENSE", "name", "更深", "parentId", soy), 409, "CATEGORY_DEPTH");
        long shopping = categoryId("EXPENSE", "购物");
        postStatus("/api/v1/categories/" + shopping + "/move", Map.of("parentId", soy), 409, "CATEGORY_DEPTH");
        postStatus("/api/v1/categories/" + food + "/move", Map.of("parentId", breakfast), 409, "CATEGORY_CYCLE");
        var renamed = send("/api/v1/categories/" + shopping + "/rename", Map.of("name", "日用品"));
        assertThat(renamed.path("id").asLong()).isEqualTo(shopping);
        assertThat(renamed.path("name").asText()).isEqualTo("日用品");
        var moved = send("/api/v1/categories/" + shopping + "/move", Map.of("parentId", food));
        assertThat(moved.path("id").asLong()).isEqualTo(shopping);
        assertThat(jdbc.sql("SELECT parent_id FROM categories WHERE id = :id").param("id", shopping).query(Long.class).single())
                .isEqualTo(food);
        postStatus("/api/v1/categories/" + shopping + "/rename", Map.of("name", "早餐"), 409, "CATEGORY_NAME_CONFLICT");
        postStatus("/api/v1/categories", Map.of("type", "EXPENSE", "name", "  午餐  ", "parentId", food), 409, "CATEGORY_NAME_CONFLICT");
        postStatus("/api/v1/categories", Map.of("type", "EXPENSE", "name", "   "), 400, "VALIDATION_FAILED");
        var sameName = send("/api/v1/categories", Map.of("type", "EXPENSE", "name", "奖金"));
        assertThat(sameName.path("type").asText()).isEqualTo("EXPENSE");
        postStatus("/api/v1/categories/" + food + "/deletion-preview", Map.of(), 409, "CATEGORY_HAS_CHILDREN");
    }

    @Test void deleteClearsCategoryWithoutChangingAmountOrBalanceAndRollsBackTogether() throws Exception {
        long breakfast = categoryId("EXPENSE", "早餐");
        long lunch = categoryId("EXPENSE", "午餐");
        long transactionId = transact(breakfast, "30.00");
        String balance = currentBalance();
        String amount = jdbc.sql("SELECT amount FROM transactions WHERE id = :id").param("id", transactionId)
                .query(String.class).single();
        var preview = send("/api/v1/categories/" + breakfast + "/deletion-preview", Map.of());
        assertThat(preview.path("directTransactionCount").asInt()).isEqualTo(1);
        postStatus("/api/v1/categories/" + breakfast + "/deletion", Map.of("directTransactionCount", 0), 409, "CATEGORY_DELETE_CONFLICT");
        mvc.perform(post("/api/v1/categories/" + breakfast + "/deletion").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("directTransactionCount", 1))))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM categories WHERE id = :id").param("id", breakfast).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT category_id FROM transactions WHERE id = :id").param("id", transactionId).query(Long.class).optional())
                .isEmpty();
        assertThat(jdbc.sql("SELECT amount FROM transactions WHERE id = :id").param("id", transactionId).query(String.class).single())
                .isEqualTo(amount);
        assertThat(currentBalance()).isEqualTo(balance);
        long kept = transact(lunch, "1.00");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new TrustedUserContext.Identity(userId, 1), null, List.of()));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            categories.delete(lunch, 1);
            throw new IllegalStateException("stop");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM categories WHERE id = :id").param("id", lunch).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT category_id FROM transactions WHERE id = :id").param("id", kept).query(Long.class).single())
                .isEqualTo(lunch);
    }

    @Test void rejectsAnotherUsersParentAndConcurrentDuplicateNames() throws Exception {
        var other = newUser();
        long foreign = jdbc.sql("SELECT id FROM categories WHERE user_id = :id AND name = '餐饮'")
                .param("id", other.id()).query(Long.class).single();
        postStatus("/api/v1/categories", Map.of("type", "EXPENSE", "name", "夜宵", "parentId", foreign), 404, "RESOURCE_NOT_FOUND");
        long wage = categoryId("INCOME", "工资");
        postStatus("/api/v1/categories/" + categoryId("EXPENSE", "购物") + "/move", Map.of("parentId", wage), 409, "CATEGORY_TYPE_MISMATCH");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/categories").header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"type\":\"EXPENSE\",\"name\":\"并发分类\"}"))
                        .andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/categories").header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"type\":\"EXPENSE\",\"name\":\"并发分类\"}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM categories WHERE user_id = :id AND name = '并发分类'")
                .param("id", userId).query(Long.class).single()).isEqualTo(1);
    }

    @Test void directTransactionBlocksTurningALeafIntoAParent() throws Exception {
        long shopping = categoryId("EXPENSE", "购物");
        transact(shopping, "5.00");
        postStatus("/api/v1/categories", Map.of("type", "EXPENSE", "name", "日用", "parentId", shopping),
                409, "CATEGORY_PARENT_HAS_TRANSACTIONS");
        assertThat(jdbc.sql("SELECT category_id FROM transactions WHERE user_id = :id").param("id", userId).query(Long.class).single())
                .isEqualTo(shopping);
    }

    private JsonNode named(JsonNode nodes, String name) {
        for (var node : nodes) if (node.path("name").asText().equals(name)) return node;
        throw new AssertionError(name);
    }

    private long categoryId(String type, String name) throws Exception {
        var nodes = categoryList(type);
        for (var root : nodes) {
            if (root.path("name").asText().equals(name)) return root.path("id").asLong();
            for (var child : root.path("children")) {
                if (child.path("name").asText().equals(name)) return child.path("id").asLong();
            }
        }
        throw new AssertionError(name);
    }

    private JsonNode categoryList(String type) throws Exception {
        var result = mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + token).param("type", type))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("items");
    }

    private JsonNode send(String path, Map<String, ?> body) throws Exception {
        var result = mvc.perform(post(path).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is2xxSuccessful()).andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? json.createObjectNode() : json.readTree(content);
    }

    private void postStatus(String path, Map<String, ?> body, int status, String code) throws Exception {
        mvc.perform(post(path).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    private long transact(long categoryId, String amount) throws Exception {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", "EXPENSE");
        map.put("amount", amount);
        map.put("categoryId", categoryId);
        map.put("tagIds", List.of());
        map.put("businessTime", "2026-09-24T12:00:00+08:00");
        var result = mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(map)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private String currentBalance() throws Exception {
        var result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("items").get(0).path("currentBalance").asText();
    }

    private long initialize(String amount) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("openingBalance", amount))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private TestUser newUser() throws Exception {
        String name = "s3t01_" + UUID.randomUUID();
        String password = tokens.generate();
        bootstrap.ensureUser(name, password);
        long id = users.findByUsername(name).orElseThrow().getId();
        fixtures.add(id);
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", name, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return new TestUser(id, json.readTree(result.getResponse().getContentAsString()).path("token").asText());
    }

    private record TestUser(long id, String token) { }
}
