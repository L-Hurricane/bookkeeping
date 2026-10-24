package com.limingyuan.bookkeeping.adjustment;

import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AdjustmentIntegrationTest {
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
    List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() throws Exception {
        var user = newUser("phase5");
        userId = user.id();
        token = user.token();
        accountId = initialize(token, "0");
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        incomeCategory = categoryId("INCOME", "工资");
        expenseCategory = categoryId("EXPENSE", "早餐");
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

    @Test void currentBalanceAggregatesSignedTransactionsAndAdjustments() throws Exception {
        assertThat(balance()).isEqualTo("0.00");
        transact("INCOME", "10.00", "2026-09-01T08:00:00+08:00");
        transact("INCOME", "-3.00", "2026-09-01T09:00:00+08:00");
        transact("EXPENSE", "4.00", "2026-09-02T08:00:00+08:00");
        transact("EXPENSE", "-1.50", "2099-01-01T00:00:00+08:00");
        assertThat(balance()).isEqualTo("4.50");
        var created = adjust("10.00", "2026-01-01T00:00:00+08:00", "核对", "adj-up");
        assertThat(created.path("created").asBoolean()).isTrue();
        assertThat(created.path("currentBalanceBefore").asText()).isEqualTo("4.50");
        assertThat(created.path("adjustment").path("adjustmentAmount").asText()).isEqualTo("5.50");
        assertThat(created.path("currentBalanceAfter").asText()).isEqualTo("10.00");
        assertThat(balance()).isEqualTo("10.00");
        var down = adjust("-2.00", "2026-09-03T00:00:00+08:00", null, "adj-down");
        assertThat(down.path("adjustment").path("adjustmentAmount").asText()).isEqualTo("-12.00");
        assertThat(balance()).isEqualTo("-2.00");
        var zero = adjust("0.00", "2026-09-03T01:00:00+08:00", null, "adj-zero");
        assertThat(zero.path("adjustment").path("adjustmentAmount").asText()).isEqualTo("2.00");
        assertThat(balance()).isEqualTo("0.00");
    }

    @Test void noChangeIsReplayedWithoutABusinessRow() throws Exception {
        transact("INCOME", "8.00", "2026-09-01T08:00:00+08:00");
        var first = submit("same", "8.00", "2026-09-02T08:00:00+08:00", "  ");
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(first.getResponse().getHeader("Idempotent-Replay")).isNull();
        var body = json.readTree(first.getResponse().getContentAsString());
        assertThat(body.path("created").asBoolean()).isFalse();
        assertThat(body.path("reason").asText()).isEqualTo("NO_ADJUSTMENT_NEEDED");
        assertThat(body.path("currentBalance").asText()).isEqualTo("8.00");
        assertThat(countAdjustments()).isZero();
        transact("EXPENSE", "1.00", "2026-09-03T08:00:00+08:00");
        var replay = submit("same", "8.00", "2026-09-02T08:00:00+08:00", null);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("currentBalance").asText()).isEqualTo("8.00");
        assertThat(balance()).isEqualTo("7.00");
        assertThat(countAdjustments()).isZero();
        assertThat(jdbc.sql("SELECT result_code FROM idempotency_records WHERE user_id = :id AND scope = 'CREATE_BALANCE_ADJUSTMENT'")
                .param("id", userId).query(String.class).single()).isEqualTo("NO_CHANGE");
    }

    @Test void replayDoesNotWriteAgainAndDifferentRequestConflicts() throws Exception {
        var first = submit("k1", "6.00", "2026-09-04T08:00:00+08:00", " 备注 ");
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        long id = json.readTree(first.getResponse().getContentAsString()).path("adjustment").path("id").asLong();
        var replay = submit("k1", "6.00", "2026-09-04T08:00:00+08:00", "备注");
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("adjustment").path("id").asLong()).isEqualTo(id);
        assertThat(countAdjustments()).isEqualTo(1);
        assertThat(accountOrder()).isEqualTo(1);
        var conflict = submit("k1", "7.00", "2026-09-04T08:00:00+08:00", "备注");
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(conflict.getResponse().getContentAsString()).path("error").path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(countAdjustments()).isEqualTo(1);
        assertThat(accountOrder()).isEqualTo(1);
        mvc.perform(get("/api/v1/accounts/" + accountId + "/balance-adjustments/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.adjustmentAmount").value("6.00"));
    }

    @Test void deleteReversesEffectAndSharesFirstSaveOrder() throws Exception {
        transact("INCOME", "3.00", "2026-09-01T08:00:00+08:00");
        var created = adjust("1.00", "2026-09-05T08:00:00+08:00", null, "share");
        long id = created.path("adjustment").path("id").asLong();
        assertThat(orderOfTransaction()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT first_save_order FROM balance_adjustments WHERE id = :id").param("id", id).query(Long.class).single()).isEqualTo(2);
        mvc.perform(delete("/api/v1/accounts/" + accountId + "/balance-adjustments/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(balance()).isEqualTo("3.00");
        assertThat(accountOrder()).isEqualTo(2);
        assertThat(countAdjustments()).isZero();
        transact("EXPENSE", "1.00", "2026-09-06T08:00:00+08:00");
        assertThat(accountOrder()).isEqualTo(3);
    }

    @Test void allowsBusinessTimeBeforeAccountCreationAndForeignUserIsHidden() throws Exception {
        var early = adjust("5.00", "2019-01-01T00:00:00+08:00", null, "early");
        assertThat(early.path("adjustment").path("businessTime").asText()).isEqualTo("2019-01-01T00:00:00+08:00");
        assertThat(early.path("adjustment").path("adjustmentAmount").asText()).isEqualTo("5.00");
        assertThat(balance()).isEqualTo("5.00");
        assertThat(accountOrder()).isEqualTo(1);
        long id = early.path("adjustment").path("id").asLong();
        var other = newUser("other");
        long otherAccount = initialize(other.token(), "0");
        mvc.perform(get("/api/v1/accounts/" + otherAccount + "/balance-adjustments/" + id).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/accounts/" + accountId + "/balance-adjustments/" + id).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
        var foreign = mvc.perform(post("/api/v1/accounts/" + otherAccount + "/balance-adjustments").header("Authorization", "Bearer " + other.token())
                        .header("Idempotency-Key", "owned").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("9.00", "2099-09-07T08:00:00+08:00", null)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(foreign.getResponse().getContentAsString()).path("adjustment").path("id").asLong()).isNotEqualTo(id);
    }

    @Test void deletedAdjustmentReplayDoesNotRecreate() throws Exception {
        var created = adjust("12.00", "2026-09-09T08:00:00+08:00", null, "delete-replay");
        long id = created.path("adjustment").path("id").asLong();
        mvc.perform(delete("/api/v1/accounts/" + accountId + "/balance-adjustments/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        long order = accountOrder();
        var retry = submit("delete-replay", "12.00", "2026-09-09T08:00:00+08:00", null);
        assertThat(retry.getResponse().getStatus()).isEqualTo(404);
        assertThat(retry.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(json.readTree(retry.getResponse().getContentAsString()).path("error").path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(countAdjustments()).isZero();
        assertThat(accountOrder()).isEqualTo(order);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM idempotency_records WHERE user_id = :id AND operation_id = 'delete-replay'")
                .param("id", userId).query(Long.class).single()).isEqualTo(1);
    }

    @Test void concurrentTransactionAndAdjustmentFollowSerialBalance() throws Exception {
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var income = executor.submit(() -> {
                gate.await();
                return mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", "income-10").contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("type", "INCOME", "amount", "10.00", "categoryId", incomeCategory,
                                        "tagIds", List.of(), "businessTime", "2026-09-10T08:00:00+08:00"))))
                        .andReturn();
            });
            var adjustment = executor.submit(() -> { gate.await(); return submit("target-20", "20.00", "2026-09-10T08:00:00+08:00", null); });
            gate.countDown();
            assertThat(income.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
            var adjustmentResult = adjustment.get(20, TimeUnit.SECONDS);
            assertThat(adjustmentResult.getResponse().getStatus()).isEqualTo(201);
            var body = json.readTree(adjustmentResult.getResponse().getContentAsString());
            String before = body.path("currentBalanceBefore").asText();
            String amount = body.path("adjustment").path("adjustmentAmount").asText();
            String after = balance();
            if ("10.00".equals(before)) {
                assertThat(amount).isEqualTo("10.00");
                assertThat(after).isEqualTo("20.00");
            } else {
                assertThat(before).isEqualTo("0.00");
                assertThat(amount).isEqualTo("20.00");
                assertThat(after).isEqualTo("30.00");
            }
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE account_id = :id").param("id", accountId).query(Long.class).single()).isEqualTo(1);
        assertThat(countAdjustments()).isEqualTo(1);
        assertThat(accountOrder()).isEqualTo(2);
    }

    @Test void concurrentAdjustmentsUseCommitTimeBalance() throws Exception {
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> { gate.await(); return submit("left", "10.00", "2026-09-08T08:00:00+08:00", null); });
            var right = executor.submit(() -> { gate.await(); return submit("right", "20.00", "2026-09-08T08:00:00+08:00", null); });
            gate.countDown();
            var results = List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
            assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(201));
            var befores = results.stream().map(result -> {
                try { return json.readTree(result.getResponse().getContentAsString()).path("currentBalanceBefore").asText(); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            }).toList();
            assertThat(befores).contains("0.00");
            assertThat(befores).containsAnyOf("10.00", "20.00");
        }
        assertThat(balance()).isIn("10.00", "20.00");
        assertThat(countAdjustments()).isEqualTo(2);
        assertThat(accountOrder()).isEqualTo(2);
    }

    private String balance() throws Exception {
        var result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("items").get(0).path("currentBalance").asText();
    }

    private JsonNode adjust(String target, String time, String remark, String key) throws Exception {
        var result = submit(key, target, time, remark);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.MvcResult submit(String key, String target, String time, String remark) throws Exception {
        return mvc.perform(post("/api/v1/accounts/" + accountId + "/balance-adjustments").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(payload(target, time, remark)))
                .andReturn();
    }

    private String payload(String target, String time, String remark) throws Exception {
        var map = new LinkedHashMap<String, Object>();
        map.put("targetBalance", target);
        map.put("businessTime", time);
        if (remark != null) map.put("remark", remark);
        return json.writeValueAsString(map);
    }

    private void transact(String type, String amount, String time) throws Exception {
        long category = "INCOME".equals(type) ? incomeCategory : expenseCategory;
        mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", type, "amount", amount, "categoryId", category, "tagIds", List.of(), "businessTime", time))))
                .andExpect(status().isCreated());
    }

    private long countAdjustments() {
        return jdbc.sql("SELECT COUNT(*) FROM balance_adjustments WHERE account_id = :id").param("id", accountId).query(Long.class).single();
    }

    private long accountOrder() {
        return jdbc.sql("SELECT last_first_save_order FROM accounts WHERE id = :id").param("id", accountId).query(Long.class).single();
    }

    private long orderOfTransaction() {
        return jdbc.sql("SELECT first_save_order FROM transactions WHERE account_id = :id").param("id", accountId).query(Long.class).single();
    }

    private long initialize(String bearer, String opening) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + bearer)
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
