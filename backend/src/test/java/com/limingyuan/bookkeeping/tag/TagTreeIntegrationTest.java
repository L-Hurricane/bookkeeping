package com.limingyuan.bookkeeping.tag;

import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import com.limingyuan.bookkeeping.tag.application.TagService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class TagTreeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    @Autowired JdbcClient jdbc;
    @Autowired TagService tagService;
    @Autowired PlatformTransactionManager transactions;
    long userId;
    String token;
    long accountId;
    long expenseCategory;
    long incomeCategory;
    List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() throws Exception {
        var user = newUser();
        userId = user.id();
        token = user.token();
        accountId = initialize("100.00");
        expenseCategory = categoryId("EXPENSE", "早餐");
        incomeCategory = categoryId("INCOME", "工资");
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
                        DELETE FROM tags
                        WHERE user_id = :id AND parent_id IS NOT NULL
                          AND id NOT IN (
                            SELECT parent_id FROM (
                              SELECT parent_id FROM tags WHERE user_id = :id AND parent_id IS NOT NULL
                            ) parents
                          )
                        """).param("id", id).update();
            }
            jdbc.sql("DELETE FROM tags WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
            users.deleteById(id);
        }
    }

    @Test void managesAThreeLevelTreeAndKeepsExplicitParentAndChildAssociations() throws Exception {
        var parent = send("/api/v1/tags", Map.of("type", "EXPENSE", "name", " 饮食 "));
        long parentId = parent.path("id").asLong();
        var child = send("/api/v1/tags", Map.of("type", "INCOME", "name", "外卖", "parentId", parentId));
        long childId = child.path("id").asLong();
        var grandchild = send("/api/v1/tags", Map.of("type", "EXPENSE", "name", "午餐外卖", "parentId", childId));
        long grandchildId = grandchild.path("id").asLong();
        postStatus("/api/v1/tags", Map.of("type", "EXPENSE", "name", "过深", "parentId", grandchildId), 409, "TAG_DEPTH");
        postStatus("/api/v1/tags/" + parentId + "/move", move(childId), 409, "TAG_CYCLE");
        var listed = tagList();
        assertThat(named(listed, "饮食").path("children").get(0).path("children").get(0).path("name").asText()).isEqualTo("午餐外卖");

        long expenseId = transact("EXPENSE", "30.00", expenseCategory, List.of(parentId, childId));
        long incomeId = transact("INCOME", "12.00", incomeCategory, List.of(childId));
        assertThat(tagIds(expenseId)).containsExactly(parentId, childId);
        assertThat(tagIds(incomeId)).containsExactly(childId);
        String balance = currentBalance();
        String amount = jdbc.sql("SELECT amount FROM transactions WHERE id = :id").param("id", expenseId).query(String.class).single();

        var moved = send("/api/v1/tags/" + grandchildId + "/move", move(null));
        assertThat(moved.path("id").asLong()).isEqualTo(grandchildId);
        assertThat(tagIds(expenseId)).containsExactly(parentId, childId);
        var renamed = send("/api/v1/tags/" + parentId + "/rename", Map.of("type", "EXPENSE", "name", "餐饮标签"));
        assertThat(renamed.path("id").asLong()).isEqualTo(parentId);
        assertThat(tagIds(expenseId)).containsExactly(parentId, childId);

        var edited = mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + expenseId)
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(edit("30.00", expenseCategory, List.of(parentId, childId))))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(edited.getResponse().getContentAsString()).path("tags"))
                .extracting(node -> node.path("id").asLong()).containsExactly(parentId, childId);

        var stats = mvc.perform(get("/api/v1/accounts/" + accountId + "/statistics/tags")
                        .header("Authorization", "Bearer " + token).param("month", "2026-09").param("type", "EXPENSE"))
                .andExpect(status().isOk()).andReturn();
        var items = json.readTree(stats.getResponse().getContentAsString()).path("items");
        assertThat(items).extracting(node -> node.path("tagId").asLong()).containsExactly(parentId, childId);
        assertThat(items).allSatisfy(node -> assertThat(node.path("amount").asText()).isEqualTo("30.00"));

        postStatus("/api/v1/tags/" + parentId + "/deletion-preview", Map.of(), 409, "TAG_HAS_CHILDREN");
        var preview = send("/api/v1/tags/" + childId + "/deletion-preview", Map.of());
        assertThat(preview.path("directTransactionCount").asInt()).isEqualTo(2);
        postStatus("/api/v1/tags/" + childId + "/deletion", Map.of("directTransactionCount", 1), 409, "TAG_DELETE_CONFLICT");
        mvc.perform(post("/api/v1/tags/" + childId + "/deletion").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("directTransactionCount", 2))))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM tags WHERE id = :id").param("id", childId).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE id = :id").param("id", expenseId).query(Long.class).single()).isEqualTo(1);
        assertThat(tagIds(expenseId)).containsExactly(parentId);
        assertThat(tagIds(incomeId)).isEmpty();
        assertThat(jdbc.sql("SELECT amount FROM transactions WHERE id = :id").param("id", expenseId).query(String.class).single()).isEqualTo(amount);
        assertThat(currentBalance()).isEqualTo(balance);
        assertThat(jdbc.sql("SELECT category_id FROM transactions WHERE id = :id").param("id", expenseId).query(Long.class).single())
                .isEqualTo(expenseCategory);
    }

    @Test void failedTransactionDoesNotRemoveAnIndependentTagAndDeleteRollsBack() throws Exception {
        var created = send("/api/v1/tags", Map.of("type", "EXPENSE", "name", "独立标签"));
        long tagId = created.path("id").asLong();
        mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(transaction("EXPENSE", "0.00", expenseCategory, List.of(tagId))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM tags WHERE id = :id").param("id", tagId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE user_id = :id").param("id", userId).query(Long.class).single()).isZero();

        long transactionId = transact("EXPENSE", "4.00", expenseCategory, List.of(tagId));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new TrustedUserContext.Identity(userId, 1), null, List.of()));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            tagService.delete(tagId, 1);
            throw new IllegalStateException("stop");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM tags WHERE id = :id").param("id", tagId).query(Long.class).single()).isEqualTo(1);
        assertThat(tagIds(transactionId)).containsExactly(tagId);
    }

    @Test void rejectsAnotherUsersParentBlankNamesAndConcurrentDuplicates() throws Exception {
        var other = newUser();
        var foreign = mvc.perform(post("/api/v1/tags").header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", "EXPENSE", "name", "别人的"))))
                .andExpect(status().isCreated()).andReturn();
        long foreignId = json.readTree(foreign.getResponse().getContentAsString()).path("id").asLong();
        postStatus("/api/v1/tags", Map.of("type", "EXPENSE", "name", "借用", "parentId", foreignId), 404, "RESOURCE_NOT_FOUND");
        postStatus("/api/v1/tags", Map.of("type", "EXPENSE", "name", "   "), 400, "VALIDATION_FAILED");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/tags").header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"type\":\"EXPENSE\",\"name\":\"并发标签\"}"))
                        .andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                start.await();
                return mvc.perform(post("/api/v1/tags").header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"type\":\"INCOME\",\"name\":\"并发标签\"}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM tags WHERE user_id = :id AND name = '并发标签'")
                .param("id", userId).query(Long.class).single()).isEqualTo(1);
    }

    private Map<String, Object> move(Long parentId) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "EXPENSE");
        body.put("parentId", parentId);
        return body;
    }

    private JsonNode tagList() throws Exception {
        var result = mvc.perform(get("/api/v1/tags").header("Authorization", "Bearer " + token).param("type", "EXPENSE"))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("items");
    }

    private JsonNode named(JsonNode nodes, String name) {
        for (var node : nodes) if (node.path("name").asText().equals(name)) return node;
        throw new AssertionError(name);
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

    private long transact(String type, String amount, long categoryId, List<Long> tagIds) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(transaction(type, amount, categoryId, tagIds)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private String transaction(String type, String amount, long categoryId, List<Long> tagIds) throws Exception {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", type);
        map.put("amount", amount);
        map.put("categoryId", categoryId);
        map.put("tagIds", tagIds);
        map.put("businessTime", "2026-09-24T12:00:00+08:00");
        return json.writeValueAsString(map);
    }

    private String edit(String amount, long categoryId, List<Long> tagIds) throws Exception {
        var map = new LinkedHashMap<String, Object>();
        map.put("amount", amount);
        map.put("categoryId", categoryId);
        map.put("tagIds", tagIds);
        map.put("businessTime", "2026-09-24T12:00:00+08:00");
        return json.writeValueAsString(map);
    }

    private List<Long> tagIds(long transactionId) {
        return jdbc.sql("SELECT tag_id FROM transaction_tags WHERE transaction_id = :id ORDER BY tag_id")
                .param("id", transactionId).query(Long.class).list();
    }

    private String currentBalance() throws Exception {
        var result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("items").get(0).path("currentBalance").asText();
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

    private long initialize(String amount) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("openingBalance", amount))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private TestUser newUser() throws Exception {
        String name = "s3t02_" + UUID.randomUUID();
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
