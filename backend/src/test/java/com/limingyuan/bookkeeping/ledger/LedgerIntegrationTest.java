package com.limingyuan.bookkeeping.ledger;

import com.limingyuan.bookkeeping.common.Money;
import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class LedgerIntegrationTest {
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
    long expenseTag;
    List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() throws Exception {
        var user = newUser("phase6");
        userId = user.id();
        token = user.token();
        accountId = initialize(token);
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        incomeCategory = categoryId("INCOME", "工资");
        expenseCategory = categoryId("EXPENSE", "早餐");
        expenseTag = tag("EXPENSE", "外卖");
    }

    @AfterEach void cleanup() {
        for (long id : fixtures) {
            jdbc.sql("DELETE FROM idempotency_records WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM balance_adjustments WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM transaction_tags WHERE transaction_id IN (SELECT id FROM transactions WHERE user_id = :id)").param("id", id).update();
            jdbc.sql("DELETE FROM transactions WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM transfers WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM tags WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM accounts WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
            users.deleteById(id);
        }
    }

    @Test void emptyMonthStillReturnsCurrentBalance() throws Exception {
        transact("INCOME", "10.00", null, "2099-01-01T00:00:00+08:00", List.of());
        var ledger = ledger("2026-09");
        assertThat(ledger.path("month").asText()).isEqualTo("2026-09");
        assertThat(ledger.path("items")).isEmpty();
        assertThat(ledger.path("summary").path("transactionCount").asInt()).isZero();
        assertThat(ledger.path("summary").path("netIncome").asText()).isEqualTo("0.00");
        assertThat(ledger.path("summary").path("netExpense").asText()).isEqualTo("0.00");
        assertThat(ledger.path("summary").path("surplus").asText()).isEqualTo("0.00");
        assertThat(ledger.path("currentBalance").asText()).isEqualTo("10.00");
        assertThat(ledger("2099-01").path("items")).hasSize(1);
    }

    @Test void mixedEntriesSortByBusinessTimeThenSharedSaveOrder() throws Exception {
        long earlier = transact("EXPENSE", "4.00", "  ", "2026-09-02T08:00:00+08:00", List.of(expenseTag));
        long later = transact("INCOME", "-1.00", "工资到账", "2026-09-03T08:00:00+08:00", List.of());
        adjust("20.00", "2026-09-03T08:00:00+08:00");
        var items = ledger("2026-09").path("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).path("kind").asText()).isEqualTo("BALANCE_ADJUSTMENT");
        assertThat(items.get(0).path("displayName").asText()).isEqualTo("余额调整");
        assertThat(items.get(0).path("amount").asText()).isEqualTo("25.00");
        assertThat(items.get(0).path("transactionType").isMissingNode()).isTrue();
        assertThat(items.get(0).path("category").isMissingNode()).isTrue();
        assertThat(items.get(0).path("tags").isMissingNode()).isTrue();
        assertThat(items.get(1).path("id").asLong()).isEqualTo(later);
        assertThat(items.get(1).path("kind").asText()).isEqualTo("TRANSACTION");
        assertThat(items.get(1).path("transactionType").asText()).isEqualTo("INCOME");
        assertThat(items.get(1).path("displayName").asText()).isEqualTo("工资到账");
        assertThat(items.get(2).path("id").asLong()).isEqualTo(earlier);
        assertThat(items.get(2).path("displayName").asText()).isEqualTo("早餐");
        assertThat(items.get(2).path("category").path("name").asText()).isEqualTo("早餐");
        assertThat(items.get(2).path("tags")).hasSize(1);
        assertThat(items.get(2).path("tags").get(0).path("name").asText()).isEqualTo("外卖");
        var summary = ledger("2026-09").path("summary");
        assertThat(summary.path("transactionCount").asInt()).isEqualTo(2);
        assertThat(summary.path("netIncome").asText()).isEqualTo("-1.00");
        assertThat(summary.path("netExpense").asText()).isEqualTo("4.00");
        assertThat(summary.path("surplus").asText()).isEqualTo("-5.00");
        assertThat(ledger("2026-09").path("currentBalance").asText()).isEqualTo("20.00");
        assertThat(ledger("2026-08").path("items")).isEmpty();
    }

    @Test void monthBoundaryAndEditedBusinessTimeKeepSaveOrder() throws Exception {
        long id = transact("EXPENSE", "2.00", "边界", "2026-09-30T23:59:59+08:00", List.of());
        transact("INCOME", "1.00", "下月", "2026-10-01T00:00:00+08:00", List.of());
        assertThat(ids(ledger("2026-09"))).containsExactly(id);
        assertThat(ids(ledger("2026-10"))).hasSize(1);
        long order = jdbc.sql("SELECT first_save_order FROM transactions WHERE id = :id").param("id", id).query(Long.class).single();
        mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount", "2.00", "name", "边界", "categoryId", expenseCategory,
                                "tagIds", List.of(), "businessTime", "2026-10-01T00:00:00+08:00"))))
                .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT first_save_order FROM transactions WHERE id = :id").param("id", id).query(Long.class).single()).isEqualTo(order);
        assertThat(ledger("2026-09").path("items")).isEmpty();
        var october = ledger("2026-10").path("items");
        assertThat(october).hasSize(2);
        assertThat(october.get(0).path("displayName").asText()).isEqualTo("下月");
        assertThat(october.get(1).path("id").asLong()).isEqualTo(id);
    }

    @Test void onlyAdjustmentAndForeignAccountStayIsolated() throws Exception {
        adjust("5.00", "2026-11-01T08:00:00+08:00");
        var items = ledger("2026-11").path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("kind").asText()).isEqualTo("BALANCE_ADJUSTMENT");
        assertThat(ledger("2026-11").path("summary").path("transactionCount").asInt()).isZero();
        var other = newUser("other-ledger");
        long otherAccount = initialize(other.token());
        mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + other.token()).param("month", "2026-11"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/accounts/" + otherAccount + "/ledger").header("Authorization", "Bearer " + other.token()).param("month", "2026-11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token).param("month", "2026-13"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test void legacyMonthQueryReturnsEveryRowWithoutACursor() throws Exception {
        insertTransactions(51, LocalDateTime.of(2022, 4, 1, 8, 0), 510000, false);
        var full = ledger("2022-04");
        assertThat(full.path("items")).hasSize(51);
        assertThat(absent(full.path("nextCursor"))).isTrue();
        assertThat(full.path("month").asText()).isEqualTo("2022-04");
        assertThat(full.path("summary").path("transactionCount").asInt()).isEqualTo(51);
    }

    @Test void pagesStayOrderedAcrossATieOnThePageBoundary() throws Exception {
        insertTransactions(49, LocalDateTime.of(2021, 8, 2, 0, 0), 610000, false);
        long lower = insertTransaction("EXPENSE", "1.00", expenseCategory, LocalDateTime.of(2021, 7, 1, 0, 0),
                LocalDateTime.of(2021, 7, 1, 0, 0, 0, 111111000), 770001);
        long higher = insertTransaction("EXPENSE", "1.00", expenseCategory, LocalDateTime.of(2021, 7, 1, 0, 0),
                LocalDateTime.of(2021, 7, 1, 0, 0, 0, 111111000), 770001);
        var first = page(null, null, null, null);
        var second = page(null, null, null, first.path("nextCursor").asText());
        assertThat(first.path("items")).hasSize(50);
        assertThat(second.path("items")).hasSize(1);
        assertThat(absent(second.path("nextCursor"))).isTrue();
        assertThat(first.path("items").get(49).path("id").asLong()).isEqualTo(higher);
        assertThat(second.path("items").get(0).path("id").asLong()).isEqualTo(lower);
        var seen = new LinkedHashSet<Long>();
        first.path("items").forEach(item -> seen.add(item.path("id").asLong()));
        second.path("items").forEach(item -> seen.add(item.path("id").asLong()));
        assertThat(seen).hasSize(51);
    }

    @Test void equalSortKeysBreakByKindThenId() throws Exception {
        LocalDateTime business = LocalDateTime.of(2024, 6, 15, 12, 0);
        LocalDateTime saved = LocalDateTime.of(2024, 6, 15, 4, 0, 0, 111111000);
        long firstTransaction = insertTransaction("EXPENSE", "1.00", expenseCategory, business, saved, 880001);
        long secondTransaction = insertTransaction("INCOME", "-2.50", incomeCategory, business, saved, 880001);
        long adjustment = insertAdjustment("4.00", business, saved, 880001);
        long other = account("对手账户", "0.00");
        long transfer = insertTransfer(accountId, other, business, business, saved, 880001);
        var items = page("2024-06", null, null, null).path("items");
        assertThat(items).extracting(item -> item.path("kind").asText() + ":" + item.path("id").asLong())
                .containsExactly("TRANSFER:" + transfer, "TRANSACTION:" + secondTransaction, "TRANSACTION:" + firstTransaction,
                        "BALANCE_ADJUSTMENT:" + adjustment);
        assertThat(items.get(1).path("amount").asText()).isEqualTo("-2.50");
        assertThat(items.get(1).path("transactionType").asText()).isEqualTo("INCOME");
    }

    @Test void cursorIsBoundToTheAccountAndFilter() throws Exception {
        insertTransactions(51, LocalDateTime.of(2023, 1, 1, 9, 0), 300000, false);
        String cursor = page(null, null, null, null).path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token)
                        .param("month", "2023-01").param("cursor", cursor))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token)
                        .param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token)
                        .param("month", "2023-01").param("from", "2023-01-01").param("to", "2023-01-31"))
                .andExpect(status().isBadRequest());
        var other = newUser("outsider");
        mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + other.token()).param("paged", "true"))
                .andExpect(status().isNotFound());
    }

    @Test void explicitMonthAndDateRangePageWithoutChangingBalanceFacts() throws Exception {
        long refund = transact("EXPENSE", "-5.00", "退款", "2024-12-15T10:00:00+08:00", List.of());
        long income = transact("INCOME", "-1.00", "冲减", "2024-12-20T10:00:00+08:00", List.of());
        transact("EXPENSE", "8.00", "下月", "2025-01-02T10:00:00+08:00", List.of());
        long other = account("转入", "0.00");
        long transfer = transfer(accountId, other, "6.00", "2024-12-31T23:00:00+08:00", "2025-01-01T01:00:00+08:00");
        var month = page("2024-12", null, null, null);
        assertThat(ids(month)).containsExactly(transfer, income, refund);
        assertThat(month.path("summary").path("netIncome").asText()).isEqualTo("-1.00");
        assertThat(month.path("summary").path("netExpense").asText()).isEqualTo("-5.00");
        assertThat(month.path("summary").path("surplus").asText()).isEqualTo("4.00");
        assertThat(month.path("items").get(0).path("businessTime").asText()).isEqualTo("2024-12-31T23:00:00+08:00");
        assertThat(month.path("items").get(0).path("amount").asText()).isEqualTo("-6.00");
        var arrival = mvc.perform(get("/api/v1/accounts/" + other + "/ledger").header("Authorization", "Bearer " + token)
                        .param("from", "2025-01-01").param("to", "2025-01-01").param("paged", "true"))
                .andExpect(status().isOk()).andReturn();
        var arrived = json.readTree(arrival.getResponse().getContentAsString());
        assertThat(absent(arrived.path("month"))).isTrue();
        assertThat(arrived.path("summary").path("transactionCount").asInt()).isZero();
        assertThat(ids(arrived)).containsExactly(transfer);
        assertThat(arrived.path("items").get(0).path("businessTime").asText()).isEqualTo("2025-01-01T01:00:00+08:00");
        assertThat(arrived.path("items").get(0).path("amount").asText()).isEqualTo("6.00");
        var balance = month.path("currentBalance").asText();
        assertThat(page(null, null, null, null).path("currentBalance").asText()).isEqualTo(balance);
        mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + refund).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amount", "-5.00", "name", "退款", "categoryId", expenseCategory,
                                "tagIds", List.of(), "businessTime", "2023-02-02T08:00:00+08:00"))))
                .andExpect(status().isOk());
        var reloaded = page("2024-12", null, null, null);
        assertThat(ids(reloaded)).doesNotContain(refund);
        assertThat(ids(page("2023-02", null, null, null))).containsExactly(refund);
        assertThat(reloaded.path("currentBalance").asText()).isEqualTo(balance);
    }

    @Test void fiveThousandMixedRowsPageWithoutGapsOrOffset() throws Exception {
        int transactions = 4997;
        insertTransactions(transactions, LocalDateTime.of(2019, 1, 1, 8, 0), 900000);
        long negativeIncome = insertTransaction("INCOME", "-2.50", incomeCategory, LocalDateTime.of(2018, 3, 1, 8, 0),
                LocalDateTime.of(2018, 3, 1, 0, 0, 1), 899001);
        long refund = insertTransaction("EXPENSE", "-3.25", expenseCategory, LocalDateTime.of(2026, 11, 2, 8, 0),
                LocalDateTime.of(2026, 11, 2, 0, 0, 1), 899002);
        insertAdjustment("10.00", LocalDateTime.of(2018, 6, 15, 9, 0), LocalDateTime.of(2018, 6, 15, 0, 0, 1), 899003);
        insertAdjustment("-4.00", LocalDateTime.of(2025, 12, 31, 23, 0), LocalDateTime.of(2025, 12, 31, 0, 0, 1), 899004);
        long other = account("历史对手", "0.00");
        insertTransfer(accountId, other, LocalDateTime.of(2024, 12, 31, 22, 0), LocalDateTime.of(2025, 1, 1, 1, 0),
                LocalDateTime.of(2024, 12, 31, 0, 0, 1), 899005);
        var expectedRows = expectedRows();
        var expected = expectedRows.stream().map(SortRow::key).toList();
        assertThat(expected).hasSize(5002);
        var seen = new ArrayList<String>();
        String cursor = null;
        String balance = null;
        int requests = 0;
        do {
            var body = page(null, null, null, cursor);
            requests++;
            if (balance == null) balance = body.path("currentBalance").asText();
            assertThat(body.path("currentBalance").asText()).isEqualTo(balance);
            assertThat(absent(body.path("month"))).isTrue();
            int size = body.path("items").size();
            assertThat(size).isBetween(1, 50);
            if (!absent(body.path("nextCursor"))) assertThat(size).isEqualTo(50);
            body.path("items").forEach(item -> seen.add(item.path("kind").asText() + ":" + item.path("id").asLong()));
            cursor = absent(body.path("nextCursor")) ? null : body.path("nextCursor").asText();
        } while (cursor != null);
        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(seen).contains("TRANSACTION:" + negativeIncome, "TRANSACTION:" + refund);
        assertThat(new LinkedHashSet<>(seen)).hasSize(seen.size());
        assertThat(requests).isGreaterThan(1);
        assertThat(balance).isEqualTo(Money.format(new BigDecimal("1.00").subtract(new BigDecimal("2.50"))
                .add(new BigDecimal("3.25")).add(new BigDecimal("6.00")).subtract(new BigDecimal("8.00"))));
        var rangeSeen = new ArrayList<String>();
        String rangeCursor = null;
        do {
            var body = page(null, "2024-01-01", "2024-12-31", rangeCursor);
            body.path("items").forEach(item -> {
                assertThat(item.path("businessTime").asText()).startsWith("2024-");
                rangeSeen.add(item.path("kind").asText() + ":" + item.path("id").asLong());
            });
            rangeCursor = absent(body.path("nextCursor")) ? null : body.path("nextCursor").asText();
        } while (rangeCursor != null);
        var expectedRange = expectedRows.stream().filter(row -> row.business().getYear() == 2024).map(SortRow::key).toList();
        assertThat(rangeSeen).containsExactlyElementsOf(expectedRange);
        assertThat(rangeSeen).hasSizeGreaterThan(50);
    }

    private JsonNode ledger(String month) throws Exception {
        var result = mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token).param("month", month))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private List<Long> ids(JsonNode ledger) {
        var ids = new ArrayList<Long>();
        ledger.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private long transact(String type, String amount, String name, String time, List<Long> tagIds) throws Exception {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", type);
        map.put("amount", amount);
        if (name != null) map.put("name", name);
        map.put("categoryId", "INCOME".equals(type) ? incomeCategory : expenseCategory);
        map.put("tagIds", tagIds);
        map.put("businessTime", time);
        var result = mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(map)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private void adjust(String target, String time) throws Exception {
        mvc.perform(post("/api/v1/accounts/" + accountId + "/balance-adjustments").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("targetBalance", target, "businessTime", time))))
                .andExpect(status().isCreated());
    }

    private long initialize(String bearer) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"0\"}"))
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
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("type", type, "name", name))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private JsonNode page(String month, String from, String to, String cursor) throws Exception {
        var request = get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token).param("paged", "true");
        if (month != null) request.param("month", month);
        if (from != null) request.param("from", from);
        if (to != null) request.param("to", to);
        if (cursor != null) request.param("cursor", cursor);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private void insertTransactions(int count, LocalDateTime start, long firstOrder) {
        insertTransactions(count, start, firstOrder, true);
    }

    private void insertTransactions(int count, LocalDateTime start, long firstOrder, boolean daily) {
        var stamp = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
        var saved = "2020-01-01 00:00:00.000000";
        var values = new StringBuilder();
        int batched = 0;
        for (int i = 0; i < count; i++) {
            if (batched == 200) {
                flushTransactions(values);
                values.setLength(0);
                batched = 0;
            }
            if (batched > 0) values.append(',');
            String type = i % 2 == 0 ? "INCOME" : "EXPENSE";
            long category = type.equals("INCOME") ? incomeCategory : expenseCategory;
            LocalDateTime when = daily ? start.plusDays(i) : start.plusMinutes(i);
            values.append("(%d,%d,'%s',1.00,%d,'%s','%s',%d,'%s')".formatted(userId, accountId, type, category,
                    when.format(stamp), saved, firstOrder + i, saved));
            batched++;
        }
        if (batched > 0) flushTransactions(values);
    }

    private void flushTransactions(StringBuilder values) {
        jdbc.sql("""
                INSERT INTO transactions (user_id, account_id, type, amount, category_id, business_time,
                    first_saved_at_utc, first_save_order, updated_at_utc)
                VALUES %s
                """.formatted(values)).update();
    }

    private long insertTransaction(String type, String amount, long category, LocalDateTime business, LocalDateTime saved, long order) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO transactions (user_id, account_id, type, amount, category_id, business_time,
                    first_saved_at_utc, first_save_order, updated_at_utc)
                VALUES (:userId, :accountId, :type, :amount, :category, :business, :saved, :orderNo, :saved)
                """).param("userId", userId).param("accountId", accountId).param("type", type).param("amount", new BigDecimal(amount))
                .param("category", category).param("business", business).param("saved", saved).param("orderNo", order)
                .update(keys, "id");
        return keys.getKey().longValue();
    }

    private long insertAdjustment(String amount, LocalDateTime business, LocalDateTime saved, long order) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO balance_adjustments (user_id, account_id, adjustment_amount, business_time,
                    first_saved_at_utc, first_save_order)
                VALUES (:userId, :accountId, :amount, :business, :saved, :orderNo)
                """).param("userId", userId).param("accountId", accountId).param("amount", new BigDecimal(amount))
                .param("business", business).param("saved", saved).param("orderNo", order).update(keys, "id");
        return keys.getKey().longValue();
    }

    private long insertTransfer(long from, long to, LocalDateTime fromTime, LocalDateTime toTime, LocalDateTime saved, long order) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO transfers (user_id, from_account_id, to_account_id, amount, from_business_time, to_business_time,
                    first_saved_at_utc, first_save_order, updated_at_utc)
                VALUES (:userId, :fromId, :toId, 8.00, :fromTime, :toTime, :saved, :orderNo, :saved)
                """).param("userId", userId).param("fromId", from).param("toId", to).param("fromTime", fromTime)
                .param("toTime", toTime).param("saved", saved).param("orderNo", order).update(keys, "id");
        return keys.getKey().longValue();
    }

    private long account(String name, String opening) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", name, "kind", "NORMAL", "openingBalance", opening))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private long transfer(long from, long to, String amount, String fromTime, String toTime) throws Exception {
        var result = mvc.perform(post("/api/v1/transfers").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":" + from + ",\"toAccountId\":" + to + ",\"amount\":\"" + amount
                                + "\",\"fromBusinessTime\":\"" + fromTime + "\",\"toBusinessTime\":\"" + toTime + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private List<SortRow> expectedRows() {
        var rows = new ArrayList<SortRow>();
        jdbc.sql("""
                SELECT 'TRANSACTION' AS kind, id, business_time, first_save_order, first_saved_at_utc
                FROM transactions WHERE account_id = :accountId AND user_id = :userId
                UNION ALL
                SELECT 'BALANCE_ADJUSTMENT', id, business_time, first_save_order, first_saved_at_utc
                FROM balance_adjustments WHERE account_id = :accountId AND user_id = :userId
                UNION ALL
                SELECT 'TRANSFER', id,
                       CASE WHEN from_account_id = :accountId THEN from_business_time ELSE to_business_time END,
                       first_save_order, first_saved_at_utc
                FROM transfers
                WHERE user_id = :userId AND (from_account_id = :accountId OR to_account_id = :accountId)
                """).param("accountId", accountId).param("userId", userId).query((rs, number) -> rows.add(new SortRow(
                rs.getString("kind"), rs.getLong("id"), rs.getObject("business_time", LocalDateTime.class),
                rs.getLong("first_save_order"), rs.getObject("first_saved_at_utc", LocalDateTime.class)))).list();
        rows.sort(Comparator.comparing(SortRow::business).reversed()
                .thenComparing(Comparator.comparingLong(SortRow::order).reversed())
                .thenComparing(Comparator.comparing(SortRow::saved).reversed())
                .thenComparing(Comparator.comparing(SortRow::kind).reversed())
                .thenComparing(Comparator.comparingLong(SortRow::id).reversed()));
        return rows;
    }

    private record SortRow(String kind, long id, LocalDateTime business, long order, LocalDateTime saved) {
        private String key() { return kind + ":" + id; }
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
