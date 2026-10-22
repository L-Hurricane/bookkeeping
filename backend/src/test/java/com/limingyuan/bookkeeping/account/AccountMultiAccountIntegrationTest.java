package com.limingyuan.bookkeeping.account;

import com.limingyuan.bookkeeping.account.persistence.AccountRepository;
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
class AccountMultiAccountIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
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
        String name = "s1t03_" + UUID.randomUUID();
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
            jdbc.sql("DELETE FROM accounts WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
            users.deleteById(id);
        }
    }

    @Test void createNormalAndCreditAccountsListsBothAndKeepsBalances() throws Exception {
        var cash = create("现金", "NORMAL", "100.00", token);
        var card = create("信用卡", "CREDIT", "-50.00", token);
        assertThat(cash.path("kind").asText()).isEqualTo("NORMAL");
        assertThat(card.path("kind").asText()).isEqualTo("CREDIT");
        assertThat(card.path("currentBalance").asText()).isEqualTo("-50.00");

        var listed = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)).andReturn();
        var items = json.readTree(listed.getResponse().getContentAsString()).path("items");
        assertThat(items.get(0).path("id").asLong()).isEqualTo(cash.path("id").asLong());
        assertThat(items.get(1).path("id").asLong()).isEqualTo(card.path("id").asLong());
        assertThat(items.get(0).path("name").asText()).isEqualTo("现金");
        assertThat(items.get(1).path("name").asText()).isEqualTo("信用卡");
    }

    @Test void legacyInitializeStillCreatesWechatThenAllowsSecondNamedAccount() throws Exception {
        var wechat = initialize("10.00", token);
        assertThat(wechat.path("name").asText()).isEqualTo("微信");
        assertThat(wechat.path("kind").asText()).isEqualTo("NORMAL");
        var second = create("支付宝", "NORMAL", "0.00", token);
        assertThat(second.path("id").asLong()).isNotEqualTo(wechat.path("id").asLong());
        mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"1\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ALREADY_INITIALIZED"));
        assertThat(accounts.findAllByUserIdOrderByIdAsc(userId)).hasSize(2);
    }

    @Test void sameUserDuplicateNameIs409AndDifferentUsersMayShareNames() throws Exception {
        create("日常", "NORMAL", "0", token);
        mvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", " 日常 ", "kind", "CREDIT", "openingBalance", "1.00"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_NAME_CONFLICT"));

        var other = newUser();
        var theirs = create("日常", "NORMAL", "3.00", other.token());
        assertThat(theirs.path("name").asText()).isEqualTo("日常");
        assertThat(theirs.path("openingBalance").asText()).isEqualTo("3.00");
    }

    @Test void renameUpdatesNameKeepsIdAndRejectsConflictsAndForeignAccounts() throws Exception {
        var first = create("旧名", "NORMAL", "1.00", token);
        var second = create("占用", "CREDIT", "2.00", token);
        long firstId = first.path("id").asLong();

        var renamed = mvc.perform(patch("/api/v1/accounts/" + firstId).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "新名"))))
                .andExpect(status().isOk()).andReturn();
        var body = json.readTree(renamed.getResponse().getContentAsString());
        assertThat(body.path("id").asLong()).isEqualTo(firstId);
        assertThat(body.path("name").asText()).isEqualTo("新名");
        assertThat(body.path("kind").asText()).isEqualTo("NORMAL");
        assertThat(body.path("openingBalance").asText()).isEqualTo("1.00");

        mvc.perform(patch("/api/v1/accounts/" + firstId).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "占用"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_NAME_CONFLICT"));
        assertThat(accounts.findByIdAndUserId(firstId, userId).orElseThrow().getName()).isEqualTo("新名");
        assertThat(second.path("id").asLong()).isNotEqualTo(firstId);

        var other = newUser();
        mvc.perform(patch("/api/v1/accounts/" + firstId).header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "窃取"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test void ownershipIsolationOnCreateListAndForeignUserIdParams() throws Exception {
        var mine = create("我的", "NORMAL", "5.00", token);
        var other = newUser();
        create("对方", "CREDIT", "6.00", other.token());

        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token)
                        .param("userId", Long.toString(other.id())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(mine.path("id").asLong()))
                .andExpect(jsonPath("$.items[0].name").value("我的"));

        mvc.perform(patch("/api/v1/accounts/" + mine.path("id").asLong())
                        .header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "改掉"))))
                .andExpect(status().isNotFound());
    }

    @Test void rejectsInvalidKindNameAndMoneyOnCreate() throws Exception {
        for (String body : List.of(
                "{\"name\":\"\",\"kind\":\"NORMAL\",\"openingBalance\":\"0\"}",
                "{\"name\":\"  \",\"kind\":\"NORMAL\",\"openingBalance\":\"0\"}",
                "{\"name\":\"a\",\"kind\":\"unknown\",\"openingBalance\":\"0\"}",
                "{\"name\":\"a\",\"kind\":\"NORMAL\",\"openingBalance\":1}",
                "{\"name\":\"a\",\"kind\":\"NORMAL\",\"openingBalance\":\"1.234\"}")) {
            mvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM accounts WHERE user_id = :id").param("id", userId)
                .query(Long.class).single()).isZero();
    }

    private JsonNode initialize(String amount, String bearer) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("openingBalance", amount))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode create(String name, String kind, String openingBalance, String bearer) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", name, "kind", kind, "openingBalance", openingBalance))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
