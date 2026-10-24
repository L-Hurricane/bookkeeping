package com.limingyuan.bookkeeping.transaction;

import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
class CrossAccountTransactionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    @Autowired JdbcClient jdbc;

    long userId;
    String token;
    long accountA;
    long accountB;
    long expenseCategory;
    long expenseTag;
    long otherTag;
    List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() throws Exception {
        var user = newUser("s1t04");
        userId = user.id();
        token = user.token();
        accountA = createAccount("账户A", "NORMAL", "100.00");
        accountB = createAccount("账户B", "CREDIT", "50.00");
        expenseCategory = categoryId("EXPENSE", "早餐");
        expenseTag = tag("EXPENSE", "外卖");
        otherTag = tag("EXPENSE", "自备");
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

    @Test void createOnChosenAccountAndIdempotentReplayStayOnThatAccount() throws Exception {
        String key = "create-on-b";
        var first = submitCreate(accountB, key, body("EXPENSE", "12.00", "午餐", expenseCategory, List.of(expenseTag),
                "2026-09-24T12:00:00+08:00", null));
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        var created = json.readTree(first.getResponse().getContentAsString());
        long id = created.path("id").asLong();
        assertThat(created.path("accountId").asLong()).isEqualTo(accountB);
        assertThat(balance(accountA)).isEqualTo("100.00");
        assertThat(balance(accountB)).isEqualTo("38.00");

        var replay = submitCreate(accountB, key, body("EXPENSE", "12.00", "午餐", expenseCategory, List.of(expenseTag),
                "2026-09-24T12:00:00+08:00", null));
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("id").asLong()).isEqualTo(id);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE user_id = :id").param("id", userId).query(Long.class).single())
                .isEqualTo(1);
        assertThat(balance(accountB)).isEqualTo("38.00");
    }

    @Test void moveKeepsIdentityTagsAndMovesBalanceEffectAtomically() throws Exception {
        var created = create(accountA, body("EXPENSE", "20.00", "跨账户", expenseCategory, List.of(expenseTag, otherTag),
                "2026-09-24T12:30:00+08:00", "备注"));
        long id = created.path("id").asLong();
        var identity = jdbc.sql("""
                SELECT type, first_save_order, first_saved_at_utc FROM transactions WHERE id = :id
                """).param("id", id).query((rs, row) -> Map.of(
                        "type", rs.getString("type"),
                        "order", rs.getLong("first_save_order"),
                        "saved", rs.getTimestamp("first_saved_at_utc").getTime())).single();
        assertThat(balance(accountA)).isEqualTo("80.00");
        assertThat(balance(accountB)).isEqualTo("50.00");

        var moved = mvc.perform(put("/api/v1/accounts/" + accountA + "/transactions/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit(accountB, "20.00", "跨账户", expenseCategory, List.of(expenseTag, otherTag),
                                "2026-09-24T12:30:00+08:00", "备注")))
                .andExpect(status().isOk()).andReturn();
        var body = json.readTree(moved.getResponse().getContentAsString());
        assertThat(body.path("id").asLong()).isEqualTo(id);
        assertThat(body.path("accountId").asLong()).isEqualTo(accountB);
        assertThat(body.path("type").asText()).isEqualTo("EXPENSE");
        assertThat(body.path("tags")).extracting(node -> node.path("id").asLong())
                .containsExactlyInAnyOrder(expenseTag, otherTag);

        var after = jdbc.sql("""
                SELECT account_id, type, first_save_order, first_saved_at_utc FROM transactions WHERE id = :id
                """).param("id", id).query((rs, row) -> Map.of(
                        "account", rs.getLong("account_id"),
                        "type", rs.getString("type"),
                        "order", rs.getLong("first_save_order"),
                        "saved", rs.getTimestamp("first_saved_at_utc").getTime())).single();
        assertThat(after.get("account")).isEqualTo(accountB);
        assertThat(after.get("type")).isEqualTo(identity.get("type"));
        assertThat(after.get("order")).isEqualTo(identity.get("order"));
        assertThat(after.get("saved")).isEqualTo(identity.get("saved"));
        assertThat(jdbc.sql("SELECT last_first_save_order FROM accounts WHERE id = :id").param("id", accountB)
                .query(Long.class).single()).isZero();

        assertThat(balance(accountA)).isEqualTo("100.00");
        assertThat(balance(accountB)).isEqualTo("30.00");
        mvc.perform(get("/api/v1/accounts/" + accountA + "/transactions/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/accounts/" + accountB + "/transactions/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(accountB));
    }

    @Test void foreignTargetOrActorCannotMoveAndLeavesSourceIntact() throws Exception {
        var created = create(accountA, body("EXPENSE", "8.00", null, expenseCategory, List.of(expenseTag),
                "2026-09-24T10:00:00+08:00", null));
        long id = created.path("id").asLong();
        var other = newUser("s1t04-other");
        long foreignAccount = createAccountFor(other.token(), "别人的", "NORMAL", "0.00");

        mvc.perform(put("/api/v1/accounts/" + accountA + "/transactions/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit(foreignAccount, "8.00", null, expenseCategory, List.of(expenseTag),
                                "2026-09-24T10:00:00+08:00", null)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(put("/api/v1/accounts/" + accountA + "/transactions/" + id)
                        .header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit(accountB, "8.00", null, expenseCategory, List.of(expenseTag),
                                "2026-09-24T10:00:00+08:00", null)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        assertThat(jdbc.sql("SELECT account_id FROM transactions WHERE id = :id").param("id", id).query(Long.class).single())
                .isEqualTo(accountA);
        assertThat(balance(accountA)).isEqualTo("92.00");
        assertThat(balance(accountB)).isEqualTo("50.00");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE account_id = :id").param("id", foreignAccount)
                .query(Long.class).single()).isZero();
    }

    @Test void failedCrossAccountEditRollsBackWithoutPartialMove() throws Exception {
        var created = create(accountA, body("EXPENSE", "6.00", "保留", expenseCategory, List.of(expenseTag),
                "2026-09-24T11:00:00+08:00", null));
        long id = created.path("id").asLong();
        long incomeTag = tag("INCOME", "工资标签");

        mvc.perform(put("/api/v1/accounts/" + accountA + "/transactions/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit(accountB, "6.00", "保留", expenseCategory, List.of(incomeTag),
                                "2026-09-24T11:00:00+08:00", null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TAG_TYPE_MISMATCH"));

        assertThat(jdbc.sql("SELECT account_id FROM transactions WHERE id = :id").param("id", id).query(Long.class).single())
                .isEqualTo(accountA);
        assertThat(jdbc.sql("SELECT tag_id FROM transaction_tags WHERE transaction_id = :id").param("id", id)
                .query(Long.class).list()).containsExactly(expenseTag);
        assertThat(balance(accountA)).isEqualTo("94.00");
        assertThat(balance(accountB)).isEqualTo("50.00");
    }

    @Test void collidingFirstSaveOrdersSortStablyAfterCrossAccountMove() throws Exception {
        var onA = create(accountA, body("EXPENSE", "1.00", "先存A", expenseCategory, List.of(), "2026-09-24T12:00:00+08:00", null));
        Thread.sleep(5);
        var onB = create(accountB, body("EXPENSE", "2.00", "先存B", expenseCategory, List.of(), "2026-09-24T12:00:00+08:00", null));
        long movedId = onA.path("id").asLong();
        long residentId = onB.path("id").asLong();
        assertThat(orderOf(movedId)).isEqualTo(1);
        assertThat(orderOf(residentId)).isEqualTo(1);

        mvc.perform(put("/api/v1/accounts/" + accountA + "/transactions/" + movedId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit(accountB, "1.00", "先存A", expenseCategory, List.of(), "2026-09-24T12:00:00+08:00", null)))
                .andExpect(status().isOk());
        assertThat(orderOf(movedId)).isEqualTo(1);

        var listed = mvc.perform(get("/api/v1/accounts/" + accountB + "/transactions")
                        .header("Authorization", "Bearer " + token).param("month", "2026-09"))
                .andExpect(status().isOk()).andReturn();
        var listIds = ids(json.readTree(listed.getResponse().getContentAsString()).path("items"));
        assertThat(listIds).containsExactly(residentId, movedId);

        adjust(accountB, "60.00", "2026-09-24T12:00:00+08:00", "tie-adj");
        long adjustmentOrder = jdbc.sql("SELECT first_save_order FROM balance_adjustments WHERE account_id = :id")
                .param("id", accountB).query(Long.class).single();
        assertThat(adjustmentOrder).isEqualTo(2);

        jdbc.sql("""
                UPDATE balance_adjustments SET first_save_order = 1
                WHERE account_id = :id AND user_id = :userId
                """).param("id", accountB).param("userId", userId).update();

        var ledger = mvc.perform(get("/api/v1/accounts/" + accountB + "/ledger")
                        .header("Authorization", "Bearer " + token).param("month", "2026-09"))
                .andExpect(status().isOk()).andReturn();
        var items = json.readTree(ledger.getResponse().getContentAsString()).path("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).path("kind").asText()).isEqualTo("BALANCE_ADJUSTMENT");
        assertThat(items.get(1).path("id").asLong()).isEqualTo(residentId);
        assertThat(items.get(2).path("id").asLong()).isEqualTo(movedId);
        assertThat(items.get(1).path("kind").asText()).isEqualTo("TRANSACTION");
        assertThat(items.get(2).path("kind").asText()).isEqualTo("TRANSACTION");
    }

    private List<Long> ids(JsonNode items) {
        var result = new ArrayList<Long>();
        items.forEach(node -> result.add(node.path("id").asLong()));
        return result;
    }

    private long orderOf(long id) {
        return jdbc.sql("SELECT first_save_order FROM transactions WHERE id = :id").param("id", id).query(Long.class).single();
    }

    private String balance(long accountId) throws Exception {
        var result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        for (var item : json.readTree(result.getResponse().getContentAsString()).path("items")) {
            if (item.path("id").asLong() == accountId) return item.path("currentBalance").asText();
        }
        throw new AssertionError("account " + accountId);
    }

    private JsonNode create(long accountId, String payload) throws Exception {
        var result = submitCreate(accountId, UUID.randomUUID().toString(), payload);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.MvcResult submitCreate(long accountId, String key, String payload)
            throws Exception {
        return mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andReturn();
    }

    private void adjust(long accountId, String target, String time, String key) throws Exception {
        mvc.perform(post("/api/v1/accounts/" + accountId + "/balance-adjustments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("targetBalance", target, "businessTime", time))))
                .andExpect(status().isCreated());
    }

    private String body(String type, String amount, String name, long categoryId, List<Long> tagIds, String time,
            String remark) throws Exception {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", type);
        map.put("amount", amount);
        if (name != null) map.put("name", name);
        map.put("categoryId", categoryId);
        map.put("tagIds", tagIds);
        map.put("businessTime", time);
        if (remark != null) map.put("remark", remark);
        return json.writeValueAsString(map);
    }

    private String edit(long targetAccountId, String amount, String name, long categoryId, List<Long> tagIds,
            String time, String remark) throws Exception {
        var map = new LinkedHashMap<String, Object>();
        map.put("accountId", targetAccountId);
        map.put("amount", amount);
        if (name != null) map.put("name", name);
        map.put("categoryId", categoryId);
        map.put("tagIds", tagIds);
        map.put("businessTime", time);
        if (remark != null) map.put("remark", remark);
        return json.writeValueAsString(map);
    }

    private long createAccount(String name, String kind, String opening) throws Exception {
        return createAccountFor(token, name, kind, opening);
    }

    private long createAccountFor(String bearer, String name, String kind, String opening) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name, "kind", kind, "openingBalance", opening))))
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", type, "name", name))))
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
