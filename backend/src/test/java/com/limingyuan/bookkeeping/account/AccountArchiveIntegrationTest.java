package com.limingyuan.bookkeeping.account;

import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
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
class AccountArchiveIntegrationTest {
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

    record TestUser(long id, String token) { }

    private TestUser newUser() throws Exception {
        String name = "s2t02_" + UUID.randomUUID();
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
            jdbc.sql("DELETE FROM idempotency_records WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM balance_adjustments WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM transaction_tags WHERE transaction_id IN (SELECT id FROM transactions WHERE user_id = :id)")
                    .param("id", id).update();
            jdbc.sql("DELETE FROM transactions WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM accounts WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
            users.deleteById(id);
        }
    }

    @Test void archiveKeepsHistoryAndBalanceUntilRestore() throws Exception {
        var account = create("现金", "20.00");
        long accountId = account.path("id").asLong();
        var created = transaction(accountId, "tx-archive-1", "8.00");
        long transactionId = created.path("id").asLong();
        var adjustment = adjustment(accountId, "adj-archive-1", "10.00");
        long adjustmentId = adjustment.path("adjustment").path("id").asLong();

        var archived = mvc.perform(post("/api/v1/accounts/" + accountId + "/archive").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        var archivedBody = json.readTree(archived.getResponse().getContentAsString());
        assertThat(archivedBody.path("lifecycle").asText()).isEqualTo("ARCHIVED");
        assertThat(archivedBody.path("currentBalance").asText()).isEqualTo("10.00");
        assertThat(archivedBody.path("openingBalance").asText()).isEqualTo("20.00");
        mvc.perform(post("/api/v1/accounts/" + accountId + "/archive").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentBalance").value("10.00"));

        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token).param("lifecycle", "ARCHIVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(accountId))
                .andExpect(jsonPath("$.items[0].currentBalance").value("10.00"));
        mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value("8.00"));

        transactionExpect(accountId, "tx-archive-2", 409);
        adjustmentExpect(accountId, "adj-archive-2", "1.00", 409);
        mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"7.00\",\"businessTime\":\"2026-10-23T10:00:00+08:00\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED"));
        mvc.perform(delete("/api/v1/accounts/" + accountId + "/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED"));
        mvc.perform(delete("/api/v1/accounts/" + accountId + "/balance-adjustments/" + adjustmentId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED"));
        assertThat(storedAmount(transactionId)).isEqualByComparingTo("8.00");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM balance_adjustments WHERE id = :id").param("id", adjustmentId)
                .query(Long.class).single()).isEqualTo(1);

        mvc.perform(put("/api/v1/accounts/" + accountId + "/name").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"现金归档\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("现金归档"));
        mvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "现金归档", "kind", "NORMAL", "openingBalance", "1.00"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_NAME_CONFLICT"));

        var restored = mvc.perform(post("/api/v1/accounts/" + accountId + "/restore").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(restored.getResponse().getContentAsString()).path("lifecycle").asText()).isEqualTo("ACTIVE");
        assertThat(json.readTree(restored.getResponse().getContentAsString()).path("currentBalance").asText()).isEqualTo("10.00");
        transactionExpect(accountId, "tx-archive-3", 201);
    }

    @Test void archivedAccountRejectsCrossAccountEditAndOtherUsers() throws Exception {
        var source = create("来源", "30.00");
        var target = create("去向", "0.00");
        long sourceId = source.path("id").asLong();
        long targetId = target.path("id").asLong();
        long transactionId = transaction(sourceId, "tx-move-1", "5.00").path("id").asLong();
        mvc.perform(post("/api/v1/accounts/" + targetId + "/archive").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/accounts/" + sourceId + "/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":" + targetId + ",\"amount\":\"5.00\",\"businessTime\":\"2026-10-23T10:00:00+08:00\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED"));
        assertThat(jdbc.sql("SELECT account_id FROM transactions WHERE id = :id").param("id", transactionId)
                .query(Long.class).single()).isEqualTo(sourceId);

        var other = newUser();
        mvc.perform(post("/api/v1/accounts/" + sourceId + "/archive").header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + other.token()).param("lifecycle", "ARCHIVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test void archiveAndCreateDoNotLeaveAPartialWrite() throws Exception {
        long accountId = create("并发", "20.00").path("id").asLong();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> archive = () -> {
                start.await();
                return mvc.perform(post("/api/v1/accounts/" + accountId + "/archive").header("Authorization", "Bearer " + token))
                        .andReturn().getResponse().getStatus();
            };
            Callable<Integer> createTransaction = () -> {
                start.await();
                return mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", "tx-race-1").contentType(MediaType.APPLICATION_JSON)
                                .content(transactionBody("4.00")))
                        .andReturn().getResponse().getStatus();
            };
            var archived = executor.submit(archive);
            var created = executor.submit(createTransaction);
            start.countDown();
            assertThat(archived.get(15, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(created.get(15, TimeUnit.SECONDS)).isIn(201, 409);
        }
        var row = jdbc.sql("SELECT lifecycle, opening_balance FROM accounts WHERE id = :id").param("id", accountId)
                .query((rs, n) -> rs.getString("lifecycle") + " " + rs.getBigDecimal("opening_balance")).single();
        assertThat(row).startsWith("ARCHIVED 20.00");
        long count = jdbc.sql("SELECT COUNT(*) FROM transactions WHERE account_id = :id").param("id", accountId)
                .query(Long.class).single();
        assertThat(count).isIn(0L, 1L);
    }

    private JsonNode create(String name, String openingBalance) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name, "kind", "NORMAL", "openingBalance", openingBalance))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode transaction(long accountId, String key, String amount) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(transactionBody(amount)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void transactionExpect(long accountId, String key, int status) throws Exception {
        mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(transactionBody("1.00")))
                .andExpect(status().is(status));
    }

    private String transactionBody(String amount) {
        return "{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"businessTime\":\"2026-10-23T10:00:00+08:00\"}";
    }

    private JsonNode adjustment(long accountId, String key, String target) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/" + accountId + "/balance-adjustments")
                        .header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetBalance\":\"" + target + "\",\"businessTime\":\"2026-10-23T11:00:00+08:00\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void adjustmentExpect(long accountId, String key, String target, int status) throws Exception {
        mvc.perform(post("/api/v1/accounts/" + accountId + "/balance-adjustments")
                        .header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetBalance\":\"" + target + "\",\"businessTime\":\"2026-10-23T11:00:00+08:00\"}"))
                .andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED"));
    }

    private java.math.BigDecimal storedAmount(long transactionId) {
        return jdbc.sql("SELECT amount FROM transactions WHERE id = :id").param("id", transactionId)
                .query(java.math.BigDecimal.class).single();
    }
}
