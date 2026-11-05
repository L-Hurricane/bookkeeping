package com.limingyuan.bookkeeping.account;

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
import org.springframework.security.core.context.SecurityContextHolder;
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
class AccountGroupIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
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

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        for (long id : fixtures) {
            jdbc.sql("DELETE FROM idempotency_records WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM transfers WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM balance_adjustments WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM transaction_tags WHERE transaction_id IN (SELECT id FROM transactions WHERE user_id = :id)")
                    .param("id", id).update();
            jdbc.sql("DELETE FROM transactions WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM accounts WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM account_groups WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
            users.deleteById(id);
        }
    }

    @Test void groupsKeepOrderBalancesArchiveAndHistory() throws Exception {
        var cash = create("现金", "NORMAL", "100.00");
        var card = create("信用卡", "CREDIT", "-40.00");
        long cashId = cash.path("id").asLong();
        long cardId = card.path("id").asLong();
        assertThat(cash.path("groupId").isMissingNode()).isTrue();
        assertThat(cash.path("displayOrder").isMissingNode()).isTrue();
        var listed = accounts();
        assertThat(listed.get(0).path("id").asLong()).isEqualTo(cashId);
        assertThat(listed.get(1).path("id").asLong()).isEqualTo(cardId);

        mvc.perform(post("/api/v1/account-groups").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" 未分组 \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/account-groups").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" 日常 \"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("日常"))
                .andExpect(jsonPath("$.subtotal").value("0.00"));
        var spare = group("备用");
        var daily = groups().get(0);
        long dailyId = daily.path("id").asLong();
        long spareId = spare.path("id").asLong();
        mvc.perform(post("/api/v1/account-groups").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"日常\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("GROUP_NAME_CONFLICT"));

        place(cashId, dailyId, List.of(cashId));
        place(cardId, dailyId, List.of(cardId, cashId));
        var grouped = groups().get(0);
        assertThat(grouped.path("subtotal").asText()).isEqualTo("60.00");
        assertThat(grouped.path("activeCount").asInt()).isEqualTo(2);

        mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + token).header("Idempotency-Key", "group-transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":" + cashId + ",\"toAccountId\":" + cardId
                                + ",\"amount\":\"10.00\",\"fromBusinessTime\":\"2026-11-05T10:00:00+08:00\",\"toBusinessTime\":\"2026-11-05T10:00:01+08:00\"}"))
                .andExpect(status().isCreated());
        assertThat(groups().get(0).path("subtotal").asText()).isEqualTo("60.00");
        assertThat(balance(cashId)).isEqualTo("90.00");
        assertThat(balance(cardId)).isEqualTo("-30.00");

        place(cardId, spareId, List.of(cardId));
        assertThat(groupByName("日常").path("subtotal").asText()).isEqualTo("90.00");
        assertThat(groupByName("备用").path("subtotal").asText()).isEqualTo("-30.00");
        place(cardId, null, List.of(cardId));
        assertThat(groupByName("备用").path("subtotal").asText()).isEqualTo("0.00");
        assertThat(account(cardId).path("groupId").isMissingNode()).isTrue();
        place(cardId, dailyId, List.of(cashId, cardId));

        mvc.perform(put("/api/v1/accounts/" + cardId + "/placement").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":" + dailyId + ",\"orderedIds\":[" + cardId + "]}"))
                .andExpect(status().isBadRequest());
        assertThat(account(cashId).path("groupId").asLong()).isEqualTo(dailyId);

        place(cashId, dailyId, List.of(cardId, cashId));
        var ordered = accounts();
        assertThat(ordered.get(0).path("id").asLong()).isEqualTo(cardId);
        assertThat(ordered.get(0).path("displayOrder").asLong()).isEqualTo(1);
        assertThat(ordered.get(1).path("displayOrder").asLong()).isEqualTo(2);

        mvc.perform(put("/api/v1/account-groups/order").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + spareId + "," + dailyId + "]}"))
                .andExpect(status().isOk());
        assertThat(groups().get(0).path("id").asLong()).isEqualTo(spareId);
        assertThat(groups().get(1).path("id").asLong()).isEqualTo(dailyId);

        mvc.perform(post("/api/v1/accounts/" + cardId + "/archive").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(groupByName("日常").path("subtotal").asText()).isEqualTo("90.00");
        assertThat(groupByName("日常").path("archivedCount").asInt()).isEqualTo(1);
        assertThat(archivedAccount(cardId).path("groupId").asLong()).isEqualTo(dailyId);
        mvc.perform(post("/api/v1/accounts/" + cardId + "/restore").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(account(cardId).path("groupId").asLong()).isEqualTo(dailyId);
        assertThat(groupByName("日常").path("subtotal").asText()).isEqualTo("60.00");

        mvc.perform(post("/api/v1/accounts/" + cardId + "/archive").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        long transfers = jdbc.sql("SELECT COUNT(*) FROM transfers WHERE user_id = :id").param("id", userId).query(Long.class).single();
        mvc.perform(delete("/api/v1/account-groups/" + dailyId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM accounts WHERE user_id = :id").param("id", userId).query(Long.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transfers WHERE user_id = :id").param("id", userId).query(Long.class).single()).isEqualTo(transfers);
        assertThat(jdbc.sql("SELECT group_id FROM accounts WHERE id = :id").param("id", cardId).query(Long.class).optional()).isEmpty();
        assertThat(jdbc.sql("SELECT group_id FROM accounts WHERE id = :id").param("id", cashId).query(Long.class).optional()).isEmpty();
        mvc.perform(post("/api/v1/accounts/" + cardId + "/restore").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(account(cardId).path("groupId").isMissingNode()).isTrue();
    }

    @Test void rejectsOtherUsersGroupsAndOrders() throws Exception {
        var mine = create("我的", "NORMAL", "5.00");
        var group = group("隔离");
        long groupId = group.path("id").asLong();
        var other = newUser();
        var foreignAccount = mvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "对方", "kind", "NORMAL", "openingBalance", "1.00"))))
                .andExpect(status().isCreated()).andReturn();
        long foreignId = json.readTree(foreignAccount.getResponse().getContentAsString()).path("id").asLong();

        mvc.perform(get("/api/v1/account-groups").header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(patch("/api/v1/account-groups/" + groupId).header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"改掉\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/accounts/" + foreignId + "/placement").header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":" + groupId + ",\"orderedIds\":[" + foreignId + "]}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/account-groups/" + groupId).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT name FROM account_groups WHERE id = :id").param("id", groupId).query(String.class).single())
                .isEqualTo("隔离");
        assertThat(jdbc.sql("SELECT group_id FROM accounts WHERE id = :id").param("id", mine.path("id").asLong())
                .query(Long.class).optional()).isEmpty();
        assertThat(jdbc.sql("SELECT group_id FROM accounts WHERE id = :id").param("id", foreignId)
                .query(Long.class).optional()).isEmpty();
    }

    private JsonNode create(String name, String kind, String openingBalance) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name, "kind", kind, "openingBalance", openingBalance))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode group(String name) throws Exception {
        var result = mvc.perform(post("/api/v1/account-groups").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void place(long accountId, Long groupId, List<Long> orderedIds) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("groupId", groupId);
        body.put("orderedIds", orderedIds);
        mvc.perform(put("/api/v1/accounts/" + accountId + "/placement").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isNoContent());
    }

    private JsonNode groups() throws Exception {
        var result = mvc.perform(get("/api/v1/account-groups").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("items");
    }

    private JsonNode groupByName(String name) throws Exception {
        for (var item : groups()) if (item.path("name").asText().equals(name)) return item;
        throw new AssertionError(name);
    }

    private JsonNode accounts() throws Exception {
        var result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("items");
    }

    private JsonNode account(long accountId) throws Exception {
        for (var item : accounts()) if (item.path("id").asLong() == accountId) return item;
        throw new AssertionError(accountId);
    }

    private JsonNode archivedAccount(long accountId) throws Exception {
        var result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token).param("lifecycle", "ARCHIVED"))
                .andExpect(status().isOk()).andReturn();
        for (var item : json.readTree(result.getResponse().getContentAsString()).path("items")) {
            if (item.path("id").asLong() == accountId) return item;
        }
        throw new AssertionError(accountId);
    }

    private String balance(long accountId) throws Exception {
        for (var item : accounts()) if (item.path("id").asLong() == accountId) return item.path("currentBalance").asText();
        throw new AssertionError(accountId);
    }

    private TestUser newUser() throws Exception {
        String name = "s1m02_" + UUID.randomUUID();
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
