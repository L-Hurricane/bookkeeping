package com.limingyuan.bookkeeping.tag;

import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import com.limingyuan.bookkeeping.tag.domain.TagType;
import com.limingyuan.bookkeeping.tag.persistence.TagRepository;
import java.util.*;
import java.util.concurrent.*;
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
class TagIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired TagRepository tags;
    @Autowired OpaqueTokens tokens;
    @Autowired JdbcClient jdbc;
    long userId;
    String token;
    List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() throws Exception {
        var user = newUser();
        userId = user.id();
        token = user.token();
    }

    record TestUser(long id, String token) { }

    private TestUser newUser() throws Exception {
        String name = "phase3_" + UUID.randomUUID();
        String password = tokens.generate();
        bootstrap.ensureUser(name, password);
        long id = users.findByUsername(name).orElseThrow().getId();
        fixtures.add(id);
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", name, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return new TestUser(id, json.readTree(result.getResponse().getContentAsString()).path("token").asText());
    }

    @AfterEach void cleanup() {
        for (long id : fixtures) {
            jdbc.sql("DELETE FROM tags WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
            users.deleteById(id);
        }
    }

    @Test void createsIncomeAndExpenseTagsAndListsOnlyTheRequestedType() throws Exception {
        var income = create("INCOME", "  工资  ", token);
        var expense = create("EXPENSE", "外卖", token);
        assertThat(income.path("name").asText()).isEqualTo("工资");
        assertThat(income.path("type").asText()).isEqualTo("INCOME");
        assertThat(expense.path("type").asText()).isEqualTo("EXPENSE");
        var stored = tags.findById(income.path("id").asLong()).orElseThrow();
        assertThat(stored.getUserId()).isEqualTo(userId);
        assertThat(stored.getType()).isEqualTo(TagType.INCOME);
        assertThat(stored.getName()).isEqualTo("工资");
        assertThat(stored.getCreatedAtUtc()).isNotNull();
        mvc.perform(get("/api/v1/tags").header("Authorization", "Bearer " + token).param("type", "INCOME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(income.path("id").asLong()))
                .andExpect(jsonPath("$.items[0].name").value("工资"));
        mvc.perform(get("/api/v1/tags").header("Authorization", "Bearer " + token).param("type", "EXPENSE"))
                .andExpect(jsonPath("$.items[0].name").value("外卖"));
    }

    @Test void sameUserCannotReuseANameAcrossTypesAndCollation() throws Exception {
        var created = create("INCOME", "Takeout", token);
        conflict("EXPENSE", "Takeout", token);
        conflict("INCOME", "takeout", token);
        var accented = create("INCOME", " café ", token);
        conflict("EXPENSE", "cafe", token);
        assertThat(accented.path("name").asText()).isEqualTo("café");
        assertThat(tags.findById(created.path("id").asLong()).orElseThrow().getName()).isEqualTo("Takeout");
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM tags WHERE user_id = :id AND name = :name
                """).param("id", userId).param("name", "takeout").query(Long.class).single()).isEqualTo(1);
    }

    @Test void differentUsersMayUseTheSameName() throws Exception {
        create("EXPENSE", "外卖", token);
        var other = newUser();
        var theirs = create("INCOME", "外卖", other.token());
        mvc.perform(get("/api/v1/tags").header("Authorization", "Bearer " + other.token())
                        .param("type", "EXPENSE").param("userId", Long.toString(userId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/tags").header("Authorization", "Bearer " + other.token()).param("type", "INCOME"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(theirs.path("id").asLong()));
    }

    @Test void concurrentDuplicateCreateKeepsOneRow() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> create = () -> {
                start.await();
                return mvc.perform(post("/api/v1/tags").header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"type\":\"EXPENSE\",\"name\":\"并发\"}"))
                        .andReturn().getResponse().getStatus();
            };
            var first = executor.submit(create);
            var second = executor.submit(create);
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM tags WHERE user_id = :id").param("id", userId)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test void rejectsBlankInvalidAndUnauthenticatedRequests() throws Exception {
        for (String body : List.of("{}", "{\"type\":\"EXPENSE\"}", "{\"name\":\"外卖\"}",
                "{\"type\":\"EXPENSE\",\"name\":\"   \"}", "{\"type\":\"expense\",\"name\":\"外卖\"}",
                "{\"type\":\"EXPENSE\",\"name\":1}", "{\"type\":\"EXPENSE\",\"name\":\"外卖\",\"userId\":1}")) {
            mvc.perform(post("/api/v1/tags").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(get("/api/v1/tags").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/tags").header("Authorization", "Bearer " + token).param("type", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/tags").param("type", "EXPENSE")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPENSE\",\"name\":\"外卖\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(tags.findAllByUserIdAndTypeOrderById(userId, TagType.EXPENSE)).isEmpty();
    }

    private JsonNode create(String type, String name, String bearer) throws Exception {
        var result = mvc.perform(post("/api/v1/tags").header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", type, "name", name))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void conflict(String type, String name, String bearer) throws Exception {
        mvc.perform(post("/api/v1/tags").header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", type, "name", name))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TAG_NAME_CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("标签名称已存在"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
    }
}
