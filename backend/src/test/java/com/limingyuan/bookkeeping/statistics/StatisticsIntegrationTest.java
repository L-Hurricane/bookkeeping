package com.limingyuan.bookkeeping.statistics;

import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
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
class StatisticsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    @Autowired JdbcClient jdbc;
    String token;
    long accountId;
    long incomeCategory;
    long breakfast;
    long lunch;
    long takeout;
    long home;
    List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() throws Exception {
        var user = newUser("phase7");
        token = user.token();
        accountId = initialize(token, "100.00");
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        incomeCategory = categoryId("INCOME", "工资");
        breakfast = categoryId("EXPENSE", "早餐");
        lunch = categoryId("EXPENSE", "午餐");
        takeout = tag("EXPENSE", "外卖");
        home = tag("EXPENSE", "自备");
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

    @Test void monthlySummaryUsesSignedTransactionsOnly() throws Exception {
        var empty = monthly("2026-09");
        assertThat(empty.path("transactionCount").asInt()).isZero();
        assertThat(empty.path("netIncome").asText()).isEqualTo("0.00");
        assertThat(empty.path("netExpense").asText()).isEqualTo("0.00");
        assertThat(empty.path("surplus").asText()).isEqualTo("0.00");
        transact("INCOME", "10.00", incomeCategory, "2026-09-01T08:00:00+08:00", List.of());
        transact("INCOME", "-2.50", incomeCategory, "2026-09-02T08:00:00+08:00", List.of());
        transact("EXPENSE", "4.00", breakfast, "2026-09-03T08:00:00+08:00", List.of());
        transact("EXPENSE", "-1.00", breakfast, "2026-09-30T23:59:59+08:00", List.of());
        transact("EXPENSE", "9.00", breakfast, "2026-10-01T00:00:00+08:00", List.of());
        transact("INCOME", "3.00", incomeCategory, "2099-01-01T00:00:00+08:00", List.of());
        adjust("50.00", "2026-09-15T08:00:00+08:00");
        var month = monthly("2026-09");
        assertThat(month.path("transactionCount").asInt()).isEqualTo(4);
        assertThat(month.path("netIncome").asText()).isEqualTo("7.50");
        assertThat(month.path("netExpense").asText()).isEqualTo("3.00");
        assertThat(month.path("surplus").asText()).isEqualTo("4.50");
        assertThat(monthly("2026-10").path("netExpense").asText()).isEqualTo("9.00");
        assertThat(monthly("2099-01").path("netIncome").asText()).isEqualTo("3.00");
        assertThat(monthly("2026-08").path("transactionCount").asInt()).isZero();
    }

    @Test void categoryStatisticsRollUpChildrenWithoutDoubleCounting() throws Exception {
        transact("EXPENSE", "5.00", breakfast, "2026-09-01T08:00:00+08:00", List.of());
        transact("EXPENSE", "-1.00", lunch, "2026-09-02T08:00:00+08:00", List.of());
        transact("INCOME", "8.00", incomeCategory, "2026-09-03T08:00:00+08:00", List.of());
        transact("EXPENSE", "2.00", breakfast, "2026-08-01T08:00:00+08:00", List.of());
        var expense = categories("2026-09", "EXPENSE");
        assertThat(expense.path("items")).hasSize(1);
        var parent = expense.path("items").get(0);
        assertThat(parent.path("categoryName").asText()).isEqualTo("餐饮");
        assertThat(parent.path("transactionCount").asInt()).isEqualTo(2);
        assertThat(parent.path("amount").asText()).isEqualTo("4.00");
        assertThat(parent.path("children")).hasSize(2);
        assertThat(parent.path("children").get(0).path("categoryName").asText()).isEqualTo("早餐");
        assertThat(parent.path("children").get(0).path("amount").asText()).isEqualTo("5.00");
        assertThat(parent.path("children").get(1).path("categoryName").asText()).isEqualTo("午餐");
        assertThat(parent.path("children").get(1).path("amount").asText()).isEqualTo("-1.00");
        var income = categories("2026-09", "INCOME");
        assertThat(income.path("items")).hasSize(1);
        assertThat(income.path("items").get(0).path("categoryName").asText()).isEqualTo("工资");
        assertThat(income.path("items").get(0).path("amount").asText()).isEqualTo("8.00");
        assertThat(income.path("items").get(0).path("children").isMissingNode()).isTrue();
        assertThat(categories("2026-08", "EXPENSE").path("items").get(0).path("amount").asText()).isEqualTo("2.00");
    }

    @Test void tagStatisticsOverlapAndKeepUntaggedSeparate() throws Exception {
        transact("EXPENSE", "6.00", breakfast, "2026-09-01T08:00:00+08:00", List.of(takeout, home));
        transact("EXPENSE", "-2.00", lunch, "2026-09-02T08:00:00+08:00", List.of());
        transact("INCOME", "4.00", incomeCategory, "2026-09-03T08:00:00+08:00", List.of());
        var expense = tags("2026-09", "EXPENSE");
        assertThat(expense.path("overlap").asBoolean()).isTrue();
        assertThat(expense.path("items")).hasSize(3);
        assertThat(expense.path("items").get(0).path("tagName").asText()).isEqualTo("外卖");
        assertThat(expense.path("items").get(0).path("amount").asText()).isEqualTo("6.00");
        assertThat(expense.path("items").get(1).path("tagName").asText()).isEqualTo("自备");
        assertThat(expense.path("items").get(1).path("amount").asText()).isEqualTo("6.00");
        assertThat(expense.path("items").get(2).path("tagName").asText()).isEqualTo("无标签");
        assertThat(expense.path("items").get(2).path("tagId").isMissingNode()).isTrue();
        assertThat(expense.path("items").get(2).path("amount").asText()).isEqualTo("-2.00");
        assertThat(tags("2026-09", "INCOME").path("items").get(0).path("tagName").asText()).isEqualTo("无标签");
        var other = newUser("other-stats");
        long otherAccount = initialize(other.token(), "0");
        mvc.perform(get("/api/v1/accounts/" + accountId + "/statistics/tags").header("Authorization", "Bearer " + other.token())
                        .param("month", "2026-09").param("type", "EXPENSE"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/accounts/" + otherAccount + "/statistics/monthly").header("Authorization", "Bearer " + other.token())
                        .param("month", "2026-09"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.transactionCount").value(0));
    }

    @Test void ledgerAndStatisticsShareTheSameTransactionFacts() throws Exception {
        long tagged = transact("EXPENSE", "6.00", lunch, "2026-09-04T08:00:00+08:00", List.of(takeout, home));
        long plain = transact("INCOME", "1.50", incomeCategory, "2026-09-05T08:00:00+08:00", List.of());
        adjust("0.00", "2026-09-06T08:00:00+08:00");
        var ledger = json.readTree(mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token)
                        .param("month", "2026-09")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var transactionIds = new ArrayList<Long>();
        ledger.path("items").forEach(item -> {
            if ("TRANSACTION".equals(item.path("kind").asText())) transactionIds.add(item.path("id").asLong());
        });
        assertThat(transactionIds).containsExactlyInAnyOrder(tagged, plain);
        assertThat(ledger.path("items").size()).isEqualTo(3);
        var summary = monthly("2026-09");
        assertThat(summary.path("transactionCount").asInt()).isEqualTo(transactionIds.size());
        assertThat(summary.path("netIncome").asText()).isEqualTo(ledger.path("summary").path("netIncome").asText());
        assertThat(summary.path("netExpense").asText()).isEqualTo(ledger.path("summary").path("netExpense").asText());
        assertThat(summary.path("surplus").asText()).isEqualTo(ledger.path("summary").path("surplus").asText());
        var lunchDrill = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .param("month", "2026-09").param("categoryId", String.valueOf(lunch)))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(lunchDrill.getResponse().getContentAsString()).path("items")).hasSize(1);
        var tagDrill = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .param("month", "2026-09").param("tagId", String.valueOf(takeout)))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(tagDrill.getResponse().getContentAsString()).path("items")).hasSize(1);
    }

    @Test void oneMonthProjectsConsistentlyAcrossLedgerAndStatistics() throws Exception {
        long dinner = categoryId("EXPENSE", "晚餐");
        long taxi = categoryId("EXPENSE", "打车");
        long interest = categoryId("INCOME", "利息");
        long income = transact("INCOME", "20.00", incomeCategory, "2026-09-01T00:00:00+08:00", List.of());
        long lunchTxn = transact("EXPENSE", "30.00", lunch, "2026-09-15T12:00:00+08:00", List.of(takeout, home));
        long dinnerTxn = transact("EXPENSE", "7.00", dinner, "2026-09-16T12:00:00+08:00", List.of(home));
        long refund = transact("EXPENSE", "-5.00", breakfast, "2026-09-30T23:59:59+08:00", List.of());
        transact("EXPENSE", "9.00", taxi, "2026-08-31T23:59:59+08:00", List.of(takeout));
        transact("INCOME", "1.00", interest, "2026-10-01T00:00:00+08:00", List.of());
        adjust("0.00", "2026-09-20T08:00:00+08:00");

        var ledger = json.readTree(mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token)
                        .param("month", "2026-09")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var transactionIds = new ArrayList<Long>();
        int adjustments = 0;
        ledger.path("items").forEach(item -> {
            if ("TRANSACTION".equals(item.path("kind").asText())) transactionIds.add(item.path("id").asLong());
        });
        for (var item : ledger.path("items")) if ("BALANCE_ADJUSTMENT".equals(item.path("kind").asText())) adjustments++;
        assertThat(transactionIds).containsExactlyInAnyOrder(income, lunchTxn, dinnerTxn, refund);
        assertThat(adjustments).isEqualTo(1);

        var summary = monthly("2026-09");
        assertThat(summary.path("transactionCount").asInt()).isEqualTo(transactionIds.size());
        assertThat(summary.path("netIncome").asText()).isEqualTo("20.00").isEqualTo(ledger.path("summary").path("netIncome").asText());
        assertThat(summary.path("netExpense").asText()).isEqualTo("32.00").isEqualTo(ledger.path("summary").path("netExpense").asText());
        assertThat(summary.path("surplus").asText()).isEqualTo("-12.00").isEqualTo(ledger.path("summary").path("surplus").asText());
        assertThat(summary.path("netIncome").asText()).isNotEqualTo("100.00");

        var expense = categories("2026-09", "EXPENSE").path("items");
        assertThat(expense).hasSize(1);
        var parent = expense.get(0);
        assertThat(parent.path("categoryName").asText()).isEqualTo("餐饮");
        assertThat(parent.path("transactionCount").asInt()).isEqualTo(3);
        assertThat(parent.path("amount").asText()).isEqualTo("32.00");
        assertThat(parent.path("children")).extracting(child -> child.path("categoryName").asText()).containsExactly("早餐", "午餐", "晚餐");
        assertThat(parent.path("children")).extracting(child -> child.path("amount").asText()).containsExactly("-5.00", "30.00", "7.00");
        assertThat(parent.path("children")).extracting(child -> child.path("transactionCount").asInt()).containsExactly(1, 1, 1);
        int childCount = 0;
        var childAmount = java.math.BigDecimal.ZERO;
        for (var child : parent.path("children")) {
            childCount += child.path("transactionCount").asInt();
            childAmount = childAmount.add(new java.math.BigDecimal(child.path("amount").asText()));
        }
        assertThat(childCount).isEqualTo(parent.path("transactionCount").asInt());
        assertThat(childAmount).isEqualByComparingTo(parent.path("amount").asText());
        var incomeStats = categories("2026-09", "INCOME").path("items");
        assertThat(incomeStats).hasSize(1);
        assertThat(incomeStats.get(0).path("categoryName").asText()).isEqualTo("工资");
        assertThat(incomeStats.get(0).path("transactionCount").asInt()).isEqualTo(1);
        assertThat(incomeStats.get(0).path("amount").asText()).isEqualTo("20.00");
        assertThat(incomeStats.get(0).path("children").isMissingNode()).isTrue();
        assertThat(categories("2026-08", "EXPENSE").path("items").get(0).path("categoryName").asText()).isEqualTo("交通");
        assertThat(categories("2026-08", "EXPENSE").path("items").get(0).path("amount").asText()).isEqualTo("9.00");

        var tags = tags("2026-09", "EXPENSE");
        assertThat(tags.path("overlap").asBoolean()).isTrue();
        assertThat(tags.path("items")).extracting(item -> item.path("tagName").asText()).containsExactly("外卖", "自备", "无标签");
        assertThat(tags.path("items")).extracting(item -> item.path("amount").asText()).containsExactly("30.00", "37.00", "-5.00");
        assertThat(tags.path("items")).extracting(item -> item.path("transactionCount").asInt()).containsExactly(1, 2, 1);
        var tagTotal = java.math.BigDecimal.ZERO;
        for (var item : tags.path("items")) tagTotal = tagTotal.add(new java.math.BigDecimal(item.path("amount").asText()));
        assertThat(tagTotal).isEqualByComparingTo("62.00").isNotEqualByComparingTo(summary.path("netExpense").asText());
        assertThat(tags("2026-09", "INCOME").path("items")).extracting(item -> item.path("tagName").asText()).containsExactly("无标签");
        assertThat(tags("2026-09", "INCOME").path("items").get(0).path("amount").asText()).isEqualTo("20.00");
        assertThat(tags("2026-08", "EXPENSE").path("items").get(0).path("tagName").asText()).isEqualTo("外卖");
        assertThat(tags("2026-08", "EXPENSE").path("items").get(0).path("amount").asText()).isEqualTo("9.00");
        assertThat(monthly("2026-10").path("netIncome").asText()).isEqualTo("1.00");
        assertThat(monthly("2026-10").path("transactionCount").asInt()).isEqualTo(1);
    }

    @Test void uncategorizedGroupCoversMonthlyFactsWithoutBecomingACategory() throws Exception {
        long transit = categoryId("EXPENSE", "公共交通");
        long dining = transact("EXPENSE", "60.00", breakfast, "2026-09-10T08:00:00+08:00", List.of());
        var open = mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPENSE\",\"amount\":\"20.00\",\"categoryId\":null,\"tagIds\":[" + takeout
                                + "],\"businessTime\":\"2026-09-11T12:00:00+08:00\"}"))
                .andExpect(status().isCreated()).andReturn();
        long uncategorized = json.readTree(open.getResponse().getContentAsString()).path("id").asLong();
        long ride = transact("EXPENSE", "20.00", transit, "2026-09-12T08:00:00+08:00", List.of());
        var summary = monthly("2026-09");
        assertThat(summary.path("transactionCount").asInt()).isEqualTo(3);
        assertThat(summary.path("netExpense").asText()).isEqualTo("100.00");
        var items = categories("2026-09", "EXPENSE").path("items");
        assertThat(items).extracting(item -> item.path("categoryName").asText()).containsExactly("餐饮", "交通", "未分类");
        assertThat(items.get(0).path("kind").asText()).isEqualTo("CATEGORY");
        assertThat(items.get(0).path("amount").asText()).isEqualTo("60.00");
        assertThat(items.get(1).path("amount").asText()).isEqualTo("20.00");
        assertThat(items.get(2).path("kind").asText()).isEqualTo("UNCATEGORIZED");
        assertThat(items.get(2).path("categoryId").isMissingNode()).isTrue();
        assertThat(items.get(2).path("amount").asText()).isEqualTo("20.00");
        assertThat(items.get(2).path("transactionCount").asInt()).isEqualTo(1);
        assertThat(items.get(2).path("children")).isEmpty();
        var covered = java.math.BigDecimal.ZERO;
        for (var item : items) covered = covered.add(new java.math.BigDecimal(item.path("amount").asText()));
        assertThat(covered).isEqualByComparingTo(summary.path("netExpense").asText());
        assertThat(tags("2026-09", "EXPENSE").path("items")).extracting(item -> item.path("tagName").asText()).contains("外卖");
        assertThat(tags("2026-09", "EXPENSE").path("items").get(0).path("amount").asText()).isEqualTo("20.00");
        var ledger = json.readTree(mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token)
                        .param("month", "2026-09")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var ids = new ArrayList<Long>();
        ledger.path("items").forEach(item -> { if ("TRANSACTION".equals(item.path("kind").asText())) ids.add(item.path("id").asLong()); });
        assertThat(ids).containsExactlyInAnyOrder(dining, uncategorized, ride);
        var openEntry = ledger.path("items").findValues("id").stream().filter(node -> node.asLong() == uncategorized).findFirst();
        assertThat(openEntry).isPresent();
        for (var item : ledger.path("items")) {
            if (item.path("id").asLong() == uncategorized) assertThat(item.has("category")).isFalse();
        }
        assertThat(ledger.path("currentBalance").asText()).isEqualTo("0.00");
        var drill = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .param("month", "2026-09").param("uncategorizedOnly", "true").param("type", "EXPENSE"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(drill.getResponse().getContentAsString()).path("items"))
                .extracting(node -> node.path("id").asLong()).containsExactly(uncategorized);
        var real = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .param("month", "2026-09").param("categoryId", String.valueOf(breakfast)))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(real.getResponse().getContentAsString()).path("items"))
                .extracting(node -> node.path("id").asLong()).containsExactly(dining);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM categories WHERE name = '未分类'").query(Long.class).single()).isZero();
    }

    @Test void categoryCanMoveToUncategorizedAndBackWithoutChangingIdentity() throws Exception {
        long id = transact("EXPENSE", "30.00", lunch, "2026-09-21T12:00:00+08:00", List.of());
        long order = jdbc.sql("SELECT first_save_order FROM transactions WHERE id = :id").param("id", id).query(Long.class).single();
        assertThat(monthly("2026-09").path("netExpense").asText()).isEqualTo("30.00");
        var initial = categories("2026-09", "EXPENSE").path("items");
        assertThat(initial).extracting(item -> item.path("categoryName").asText()).containsExactly("餐饮");
        assertThat(initial.get(0).path("amount").asText()).isEqualTo("30.00");
        assertThat(initial.get(0).path("transactionCount").asInt()).isEqualTo(1);
        assertThat(initial.get(0).path("children").get(0).path("categoryName").asText()).isEqualTo("午餐");
        assertThat(initial.get(0).path("children").get(0).path("amount").asText()).isEqualTo("30.00");
        assertThat(initial.get(0).path("children").get(0).has("kind")).isFalse();
        assertThat(initial).extracting(item -> item.path("kind").asText()).doesNotContain("UNCATEGORIZED");
        assertThat(ledgerBalance("2026-09")).isEqualTo("70.00");

        editCategory(id, null);
        assertThat(jdbc.sql("SELECT type, first_save_order FROM transactions WHERE id = :id").param("id", id)
                .query((rs, row) -> rs.getString(1) + " " + rs.getLong(2)).single()).isEqualTo("EXPENSE " + order);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE account_id = :id").param("id", accountId).query(Long.class).single()).isEqualTo(1);
        assertThat(monthly("2026-09").path("netExpense").asText()).isEqualTo("30.00");
        assertThat(ledgerBalance("2026-09")).isEqualTo("70.00");
        var cleared = categories("2026-09", "EXPENSE").path("items");
        assertThat(cleared).extracting(item -> item.path("categoryName").asText()).containsExactly("未分类");
        assertThat(cleared.get(0).path("kind").asText()).isEqualTo("UNCATEGORIZED");
        assertThat(cleared.get(0).path("amount").asText()).isEqualTo("30.00");
        assertThat(cleared.get(0).path("transactionCount").asInt()).isEqualTo(1);

        editCategory(id, lunch);
        assertThat(jdbc.sql("SELECT first_save_order FROM transactions WHERE id = :id").param("id", id).query(Long.class).single()).isEqualTo(order);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE id = :id").param("id", id).query(Long.class).single()).isEqualTo(1);
        assertThat(monthly("2026-09").path("netExpense").asText()).isEqualTo("30.00");
        assertThat(ledgerBalance("2026-09")).isEqualTo("70.00");
        var restored = categories("2026-09", "EXPENSE").path("items");
        assertThat(restored).extracting(item -> item.path("categoryName").asText()).containsExactly("餐饮");
        assertThat(restored.get(0).path("kind").asText()).isEqualTo("CATEGORY");
        assertThat(restored.get(0).path("amount").asText()).isEqualTo("30.00");
        assertThat(restored.get(0).path("transactionCount").asInt()).isEqualTo(1);
        assertThat(restored.get(0).path("children").get(0).path("categoryName").asText()).isEqualTo("午餐");
        assertThat(restored.get(0).path("children").get(0).path("amount").asText()).isEqualTo("30.00");
        assertThat(restored).extracting(item -> item.path("kind").asText()).doesNotContain("UNCATEGORIZED");
    }

    private void editCategory(long id, Long categoryId) throws Exception {
        String category = categoryId == null ? "null" : categoryId.toString();
        mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"30.00\",\"categoryId\":" + category + ",\"tagIds\":[],\"businessTime\":\"2026-09-21T12:00:00+08:00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
    }

    private String ledgerBalance(String month) throws Exception {
        var ledger = json.readTree(mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token)
                        .param("month", month)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return ledger.path("currentBalance").asText();
    }

    private JsonNode monthly(String month) throws Exception {
        return read("/statistics/monthly", month, null);
    }

    private JsonNode categories(String month, String type) throws Exception {
        return read("/statistics/categories", month, type);
    }

    private JsonNode tags(String month, String type) throws Exception {
        return read("/statistics/tags", month, type);
    }

    private JsonNode read(String path, String month, String type) throws Exception {
        var request = get("/api/v1/accounts/" + accountId + path).header("Authorization", "Bearer " + token).param("month", month);
        if (type != null) request.param("type", type);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private long transact(String type, String amount, long categoryId, String time, List<Long> tagIds) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", type, "amount", amount, "categoryId", categoryId, "tagIds", tagIds, "businessTime", time))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private void adjust(String target, String time) throws Exception {
        mvc.perform(post("/api/v1/accounts/" + accountId + "/balance-adjustments").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("targetBalance", target, "businessTime", time))))
                .andExpect(status().isCreated());
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

    private long tag(String type, String name) throws Exception {
        var result = mvc.perform(post("/api/v1/tags").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("type", type, "name", name))))
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
