package com.limingyuan.bookkeeping.transfer;

import com.limingyuan.bookkeeping.account.application.CurrentBalance;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Requires a database already authorized to apply V16. This class is not part of the
 * unauthenticated unit run.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class TransferIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    @Autowired JdbcClient jdbc;
    @Autowired CurrentBalance balances;

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
            jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
            users.deleteById(id);
        }
    }

    @Test void transferMovesBothBalancesAndStaysOutOfIncomeExpense() throws Exception {
        long from = account("现金", "100.00");
        long to = account("银行卡", "5.00");
        expense(from, "expense-1", "8.00");
        var created = transfer("key-1", from, to, "30.00", "2026-01-31T23:00:00+08:00", "2026-02-01T00:00:00+08:00", 201);
        long id = created.path("id").asLong();
        long order = created.path("firstSaveOrder").asLong();
        assertThat(created.path("amount").asText()).isEqualTo("30.00");
        assertThat(balance(from)).isEqualTo("62.00");
        assertThat(balance(to)).isEqualTo("35.00");
        mvc.perform(get("/api/v1/accounts/" + from + "/statistics/monthly").header("Authorization", "Bearer " + token)
                        .param("month", "2026-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionCount").value(1))
                .andExpect(jsonPath("$.netExpense").value("8.00"))
                .andExpect(jsonPath("$.netIncome").value("0.00"));
        mvc.perform(get("/api/v1/accounts/" + from + "/ledger").header("Authorization", "Bearer " + token).param("month", "2026-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.transactionCount").value(1))
                .andExpect(jsonPath("$.items[?(@.kind == 'TRANSFER')].amount").value("-30.00"))
                .andExpect(jsonPath("$.items[?(@.kind == 'TRANSFER')].displayName").value("转账"));
        mvc.perform(get("/api/v1/accounts/" + to + "/ledger").header("Authorization", "Bearer " + token).param("month", "2026-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.transactionCount").value(0))
                .andExpect(jsonPath("$.items[?(@.kind == 'TRANSFER')].amount").value("30.00"));
        mvc.perform(get("/api/v1/accounts/" + to + "/ledger").header("Authorization", "Bearer " + token).param("month", "2026-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.kind == 'TRANSFER')]").isEmpty());

        var replay = mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON)
                        .content(body(from, to, "30.00", "2026-01-31T23:00:00+08:00", "2026-02-01T00:00:00+08:00")))
                .andExpect(status().isOk()).andExpect(header().string("Idempotent-Replay", "true")).andReturn();
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("id").asLong()).isEqualTo(id);
        mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON)
                        .content(body(from, to, "31.00", "2026-01-31T23:00:00+08:00", "2026-02-01T00:00:00+08:00")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));

        var edited = transferUpdate(id, to, from, "12.50", "2026-12-31T23:59:59+08:00", "2026-01-01T00:00:00+08:00");
        assertThat(edited.path("id").asLong()).isEqualTo(id);
        assertThat(edited.path("firstSaveOrder").asLong()).isEqualTo(order);
        assertThat(storedOrder(id)).isEqualTo(order);
        assertThat(balance(from)).isEqualTo("104.50");
        assertThat(balance(to)).isEqualTo("-7.50");
        mvc.perform(get("/api/v1/accounts/" + from + "/ledger").header("Authorization", "Bearer " + token).param("month", "2026-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == " + id + " && @.kind == 'TRANSFER')].amount").value("12.50"));
        mvc.perform(get("/api/v1/accounts/" + to + "/ledger").header("Authorization", "Bearer " + token).param("month", "2026-12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == " + id + " && @.kind == 'TRANSFER')].amount").value("-12.50"));

        mvc.perform(delete("/api/v1/transfers/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        assertThat(balance(from)).isEqualTo("92.00");
        assertThat(balance(to)).isEqualTo("5.00");
        mvc.perform(get("/api/v1/transfers/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "key-1").contentType(MediaType.APPLICATION_JSON)
                        .content(body(from, to, "30.00", "2026-01-31T23:00:00+08:00", "2026-02-01T00:00:00+08:00")))
                .andExpect(status().isNotFound()).andExpect(header().string("Idempotent-Replay", "true"));
        assertThat(countTransfers()).isZero();
    }

    @Test void futureAndEarlierTimesAffectCurrentBalanceImmediatelyAndHistoryAtEachSide() throws Exception {
        long from = account("现金", "40.00");
        long to = account("银行卡", "1.00");
        transfer("future", from, to, "10.00", "2099-01-02T03:04:05+08:00", "2000-01-01T00:00:00+08:00", 201);
        assertThat(balance(from)).isEqualTo("30.00");
        assertThat(balance(to)).isEqualTo("11.00");
        asUser();
        try {
            assertThat(balances.asOf(from, LocalDateTime.parse("2099-01-02T03:04:04"))).isEqualByComparingTo("40.00");
            assertThat(balances.asOf(from, LocalDateTime.parse("2099-01-02T03:04:05"))).isEqualByComparingTo("30.00");
            assertThat(balances.asOf(to, LocalDateTime.parse("1999-12-31T23:59:59"))).isEqualByComparingTo("1.00");
            assertThat(balances.asOf(to, LocalDateTime.parse("2000-01-01T00:00:00"))).isEqualByComparingTo("11.00");
        } finally {
            SecurityContextHolder.clearContext();
        }
        mvc.perform(get("/api/v1/accounts/" + from + "/ledger").header("Authorization", "Bearer " + token).param("month", "2026-10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.kind == 'TRANSFER')]").isEmpty());
        mvc.perform(get("/api/v1/accounts/" + to + "/ledger").header("Authorization", "Bearer " + token).param("month", "2000-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.kind == 'TRANSFER')].amount").value("10.00"));
    }

    @Test void sameSecondOnBothSidesAndArchivedAccountsRejectTheWholeTransfer() throws Exception {
        long from = account("现金", "20.00");
        long to = account("银行卡", "3.00");
        var created = transfer("same-second", from, to, "4.00", "2026-05-05T12:00:00+08:00", "2026-05-05T12:00:00+08:00", 201);
        long id = created.path("id").asLong();
        mvc.perform(get("/api/v1/accounts/" + from + "/ledger").header("Authorization", "Bearer " + token).param("month", "2026-05"))
                .andExpect(jsonPath("$.items[?(@.kind == 'TRANSFER')].businessTime").value("2026-05-05T12:00:00+08:00"));
        mvc.perform(get("/api/v1/accounts/" + to + "/ledger").header("Authorization", "Bearer " + token).param("month", "2026-05"))
                .andExpect(jsonPath("$.items[?(@.kind == 'TRANSFER')].businessTime").value("2026-05-05T12:00:00+08:00"));
        mvc.perform(post("/api/v1/accounts/" + from + "/archive").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        transfer("archived-create", from, to, "1.00", "2026-05-05T12:00:01+08:00", "2026-05-05T12:00:01+08:00", 409);
        mvc.perform(put("/api/v1/transfers/" + id).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(body(from, to, "5.00", "2026-05-05T12:00:00+08:00", "2026-05-05T12:00:00+08:00")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED"));
        mvc.perform(delete("/api/v1/transfers/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ARCHIVED"));
        assertThat(balance(from)).isEqualTo("16.00");
        assertThat(balance(to)).isEqualTo("7.00");
        assertThat(storedAmount(id)).isEqualByComparingTo("4.00");
    }

    @Test void rejectsSameAccountNonPositiveAmountUnknownUserAndForeignTransfer() throws Exception {
        long from = account("现金", "10.00");
        long to = account("银行卡", "0.00");
        mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + token).header("Idempotency-Key", "same")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(from, from, "1.00", "2026-06-01T00:00:00+08:00", "2026-06-01T00:00:01+08:00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + token).header("Idempotency-Key", "zero")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(from, to, "0.00", "2026-06-01T00:00:00+08:00", "2026-06-01T00:00:01+08:00")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + token).header("Idempotency-Key", "neg")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(from, to, "-1.00", "2026-06-01T00:00:00+08:00", "2026-06-01T00:00:01+08:00")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + token).header("Idempotency-Key", "extra")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(from, to, "1.00", "2026-06-01T00:00:00+08:00", "2026-06-01T00:00:01+08:00").replace("}", ",\"fee\":\"1.00\"}")))
                .andExpect(status().isBadRequest());
        var other = newUser();
        long hidden = transfer("mine", from, to, "2.00", "2026-06-01T00:00:00+08:00", "2026-06-01T00:00:01+08:00", 201).path("id").asLong();
        mvc.perform(get("/api/v1/transfers/" + hidden).header("Authorization", "Bearer " + other.token())).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/transfers/" + hidden).header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(from, to, "9.00", "2026-06-01T00:00:00+08:00", "2026-06-01T00:00:01+08:00")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/transfers/" + hidden).header("Authorization", "Bearer " + other.token())).andExpect(status().isNotFound());
        assertThat(storedAmount(hidden)).isEqualByComparingTo("2.00");
    }

    @Test void oppositeTransfersLockInAccountIdOrderAndBothCommit() throws Exception {
        long from = account("现金", "50.00");
        long to = account("银行卡", "50.00");
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> left = pool.submit(() -> statusOf("left", from, to));
            Future<Integer> right = pool.submit(() -> statusOf("right", to, from));
            assertThat(left.get(15, TimeUnit.SECONDS)).isEqualTo(201);
            assertThat(right.get(15, TimeUnit.SECONDS)).isEqualTo(201);
        } finally {
            pool.shutdownNow();
        }
        assertThat(balance(from)).isEqualTo("50.00");
        assertThat(balance(to)).isEqualTo("50.00");
        assertThat(countTransfers()).isEqualTo(2);
    }

    private int statusOf(String key, long from, long to) throws Exception {
        return mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(from, to, "10.00", "2026-07-01T08:00:00+08:00", "2026-07-01T08:00:00+08:00")))
                .andReturn().getResponse().getStatus();
    }

    private void asUser() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new TrustedUserContext.Identity(userId, 0), null, List.of()));
    }

    private long account(String name, String opening) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name, "kind", "NORMAL", "openingBalance", opening))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private void expense(long accountId, String key, String amount) throws Exception {
        mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"businessTime\":\"2026-01-15T10:00:00+08:00\"}"))
                .andExpect(status().isCreated());
    }

    private JsonNode transfer(String key, long from, long to, String amount, String fromTime, String toTime, int expected) throws Exception {
        var result = mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(body(from, to, amount, fromTime, toTime)))
                .andExpect(status().is(expected)).andReturn();
        if (expected == 409) {
            assertThat(json.readTree(result.getResponse().getContentAsString()).path("error").path("code").asText()).isEqualTo("ACCOUNT_ARCHIVED");
        }
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode transferUpdate(long id, long from, long to, String amount, String fromTime, String toTime) throws Exception {
        var result = mvc.perform(put("/api/v1/transfers/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body(from, to, amount, fromTime, toTime)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String body(long from, long to, String amount, String fromTime, String toTime) {
        return "{\"fromAccountId\":" + from + ",\"toAccountId\":" + to + ",\"amount\":\"" + amount
                + "\",\"fromBusinessTime\":\"" + fromTime + "\",\"toBusinessTime\":\"" + toTime + "\"}";
    }

    private String balance(long accountId) throws Exception {
        var result = mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token)
                        .param("month", "2026-10")).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("currentBalance").asText();
    }

    private long storedOrder(long transferId) {
        return jdbc.sql("SELECT first_save_order FROM transfers WHERE id = :id").param("id", transferId).query(Long.class).single();
    }

    private BigDecimal storedAmount(long transferId) {
        return jdbc.sql("SELECT amount FROM transfers WHERE id = :id").param("id", transferId).query(BigDecimal.class).single();
    }

    private long countTransfers() {
        return jdbc.sql("SELECT COUNT(*) FROM transfers WHERE user_id = :id").param("id", userId).query(Long.class).single();
    }

    private TestUser newUser() throws Exception {
        String name = "s2t06_" + UUID.randomUUID();
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
