package com.limingyuan.bookkeeping.ledger;

import com.limingyuan.bookkeeping.common.Money;
import com.limingyuan.bookkeeping.tag.domain.TagNames;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test void historicalBusinessTimeStaysOnTheStoredClockWithPlusEight() throws Exception {
        transact("EXPENSE", "12.00", "夏令时", "1991-07-15T12:00:00+08:00", List.of());
        transact("EXPENSE", "8.00", "近年", "2026-11-01T09:30:00+08:00", List.of());
        // Imported history stores a DATETIME local clock. This row is that shape.
        jdbc.sql("""
                INSERT INTO transactions
                    (user_id, account_id, type, amount, name, category_id, business_time,
                     first_saved_at_utc, first_save_order, updated_at_utc)
                VALUES
                    (:userId, :accountId, 'EXPENSE', 3.00, '时区空隙', :categoryId, '1986-05-04 02:30:00',
                     '2026-01-01 00:00:00.000000', 900001, '2026-01-01 00:00:00.000000')
                """)
                .param("userId", userId).param("accountId", accountId).param("categoryId", expenseCategory).update();
        var body = page(null, null, null, null);
        assertThat(body.path("month").isMissingNode() || body.path("month").isNull()).isTrue();
        var times = new ArrayList<String>();
        body.path("items").forEach(item -> times.add(item.path("businessTime").asText()));
        assertThat(times).containsExactly(
                "2026-11-01T09:30:00+08:00",
                "1991-07-15T12:00:00+08:00",
                "1986-05-04T02:30:00+08:00");
        assertThat(absent(body.path("nextCursor"))).isTrue();

        var july = page("1991-07", null, null, null);
        assertThat(july.path("month").asText()).isEqualTo("1991-07");
        var julyTimes = new ArrayList<String>();
        july.path("items").forEach(item -> julyTimes.add(item.path("businessTime").asText()));
        assertThat(julyTimes).containsExactly("1991-07-15T12:00:00+08:00");

        var empty = page(null, "1990-01-01", "1990-01-31", null);
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("currentBalance").asText()).isEqualTo(body.path("currentBalance").asText());
    }

    @Test void blankStoredTagNameDoesNotRejectTheTransactionPage() throws Exception {
        mvc.perform(post("/api/v1/tags").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", "EXPENSE", "name", ""))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO tags (user_id, name, created_at_utc)
                VALUES (:userId, NULL, '2020-01-01 00:00:00.000000')
                """).param("userId", userId).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        long blankTag = insertTag("");
        long spaceTag = insertTag(" ");
        long octoberPlain = transact("EXPENSE", "1.00", "十月普通", "2026-10-02T08:00:00+08:00", List.of());
        long octoberTagged = transact("EXPENSE", "2.00", "十月外卖", "2026-10-03T08:00:00+08:00", List.of(expenseTag));
        long octoberSpace = transact("EXPENSE", "3.00", "十月空格", "2026-10-04T08:00:00+08:00", List.of());
        long januaryBlank = transact("EXPENSE", "4.00", "一月空名", "2025-01-05T08:00:00+08:00", List.of());
        long januaryMixed = transact("EXPENSE", "5.00", "一月混合", "2025-01-06T08:00:00+08:00", List.of(expenseTag));
        long aprilBlank = transact("EXPENSE", "6.00", "四月空名", "2026-04-07T08:00:00+08:00", List.of());
        link(januaryBlank, blankTag);
        link(januaryMixed, blankTag);
        link(aprilBlank, blankTag);
        link(octoberSpace, spaceTag);

        String marker = TagNames.UNNAMED_DISPLAY;
        var april = page("2026-04", null, null, null);
        assertThat(april.path("month").asText()).isEqualTo("2026-04");
        assertThat(ids(april)).containsExactly(aprilBlank);
        assertThat(item(april, aprilBlank).path("kind").asText()).isEqualTo("TRANSACTION");
        assertThat(tagIds(item(april, aprilBlank))).containsExactly(blankTag);
        assertThat(tagNames(item(april, aprilBlank))).containsExactly(marker);
        assertThat(april.path("summary").path("transactionCount").asInt()).isEqualTo(1);

        var january = page("2025-01", null, null, null);
        assertThat(january.path("month").asText()).isEqualTo("2025-01");
        assertThat(ids(january)).containsExactly(januaryMixed, januaryBlank);
        assertThat(tagIds(item(january, januaryBlank))).containsExactly(blankTag);
        assertThat(tagNames(item(january, januaryBlank))).containsExactly(marker);
        assertThat(tagIds(item(january, januaryMixed))).containsExactly(expenseTag, blankTag);
        assertThat(tagNames(item(january, januaryMixed))).containsExactly("外卖", marker);

        var october = page("2026-10", null, null, null);
        assertThat(october.path("month").asText()).isEqualTo("2026-10");
        assertThat(ids(october)).containsExactly(octoberSpace, octoberTagged, octoberPlain);
        assertThat(tagNames(item(october, octoberPlain))).isEmpty();
        assertThat(tagNames(item(october, octoberTagged))).containsExactly("外卖");
        assertThat(tagNames(item(october, octoberSpace))).containsExactly(" ");
        assertThat(ids(october)).doesNotContain(januaryBlank, aprilBlank);

        var allTime = page(null, null, null, null);
        assertThat(allTime.path("month").isMissingNode() || allTime.path("month").isNull()).isTrue();
        assertThat(absent(allTime.path("nextCursor"))).isTrue();
        assertThat(ids(allTime)).containsExactly(octoberSpace, octoberTagged, octoberPlain, aprilBlank, januaryMixed, januaryBlank);
        assertNoBlankTagName(allTime);

        var detail = readTransaction(januaryMixed);
        assertThat(tagIds(detail)).containsExactly(expenseTag, blankTag);
        assertThat(tagNames(detail)).containsExactly("外卖", marker);

        var listed = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions")
                        .header("Authorization", "Bearer " + token).param("month", "2025-01").param("type", "EXPENSE"))
                .andExpect(status().isOk()).andReturn();
        var listedBody = json.readTree(listed.getResponse().getContentAsString());
        assertThat(ids(listedBody)).containsExactly(januaryMixed, januaryBlank);
        assertThat(tagNames(item(listedBody, januaryMixed))).containsExactly("外卖", marker);
        assertThat(tagNames(item(listedBody, januaryBlank))).containsExactly(marker);

        var byBlankTag = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions")
                        .header("Authorization", "Bearer " + token).param("month", "2026-04").param("tagId", String.valueOf(blankTag)))
                .andExpect(status().isOk()).andReturn();
        assertThat(ids(json.readTree(byBlankTag.getResponse().getContentAsString()))).containsExactly(aprilBlank);

        var untagged = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions")
                        .header("Authorization", "Bearer " + token).param("month", "2026-04").param("untaggedOnly", "true"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(untagged.getResponse().getContentAsString()).path("items")).isEmpty();

        var searched = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions/search")
                        .header("Authorization", "Bearer " + token).param("name", "四月空名"))
                .andExpect(status().isOk()).andReturn();
        var searchedItems = json.readTree(searched.getResponse().getContentAsString());
        assertThat(ids(searchedItems)).containsExactly(aprilBlank);
        assertThat(tagIds(item(searchedItems, aprilBlank))).containsExactly(blankTag);
        assertThat(tagNames(item(searchedItems, aprilBlank))).containsExactly(marker);

        var statistics = mvc.perform(get("/api/v1/statistics/transactions").header("Authorization", "Bearer " + token)
                        .param("preset", "CUSTOM").param("from", "2025-01-01").param("to", "2025-01-31")
                        .param("accountScope", "ACTIVE").param("type", "EXPENSE")
                        .param("tagId", String.valueOf(blankTag)).param("tagMode", "DIRECT"))
                .andExpect(status().isOk()).andReturn();
        var statisticsItems = json.readTree(statistics.getResponse().getContentAsString());
        assertThat(ids(statisticsItems)).containsExactlyInAnyOrder(januaryBlank, januaryMixed);
        assertThat(tagIds(item(statisticsItems, januaryMixed))).containsExactly(expenseTag, blankTag);
        assertThat(tagNames(item(statisticsItems, januaryBlank))).containsExactly(marker);
        assertNoBlankTagName(statisticsItems);

        var tagStatistics = mvc.perform(get("/api/v1/accounts/" + accountId + "/statistics/tags")
                        .header("Authorization", "Bearer " + token).param("month", "2026-04").param("type", "EXPENSE"))
                .andExpect(status().isOk()).andReturn();
        var tagStatisticItems = json.readTree(tagStatistics.getResponse().getContentAsString()).path("items");
        assertThat(tagStatisticItems).hasSize(1);
        assertThat(tagStatisticItems.get(0).path("tagId").asLong()).isEqualTo(blankTag);
        assertThat(tagStatisticItems.get(0).path("tagName").asText()).isEqualTo(marker);
        assertThat(tagStatisticItems.get(0).path("transactionCount").asInt()).isEqualTo(1);
        assertThat(tagStatisticItems.get(0).path("amount").asText()).isEqualTo("6.00");

        var octoberStatistics = mvc.perform(get("/api/v1/accounts/" + accountId + "/statistics/tags")
                        .header("Authorization", "Bearer " + token).param("month", "2026-10").param("type", "EXPENSE"))
                .andExpect(status().isOk()).andReturn();
        var octoberTagNames = new ArrayList<String>();
        json.readTree(octoberStatistics.getResponse().getContentAsString()).path("items")
                .forEach(item -> octoberTagNames.add(item.path("tagName").asText()));
        assertThat(octoberTagNames).containsExactly("外卖", " ", "无标签");

        var storedTags = mvc.perform(get("/api/v1/tags").header("Authorization", "Bearer " + token).param("type", "EXPENSE"))
                .andExpect(status().isOk()).andReturn();
        var stored = json.readTree(storedTags.getResponse().getContentAsString()).path("items");
        var storedNames = new ArrayList<String>();
        Long listedBlankId = null;
        for (JsonNode tag : stored) {
            storedNames.add(tag.path("name").asText());
            if (tag.path("id").asLong() == blankTag) listedBlankId = tag.path("id").asLong();
        }
        assertThat(storedNames).contains(marker, " ", "外卖").doesNotContain("");
        assertThat(listedBlankId).isEqualTo(blankTag);

        var beforeEdit = readTransaction(aprilBlank);
        assertThat(tagIds(beforeEdit)).containsExactly(blankTag);
        var edit = new LinkedHashMap<String, Object>();
        edit.put("amount", beforeEdit.path("amount").asText());
        edit.put("name", beforeEdit.path("name").asText());
        edit.put("categoryId", beforeEdit.path("category").path("id").asLong());
        edit.put("tagIds", tagIds(beforeEdit));
        edit.put("businessTime", beforeEdit.path("businessTime").asText());
        edit.put("remark", "只改备注");
        var edited = mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + aprilBlank)
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(edit)))
                .andExpect(status().isOk()).andReturn();
        var editedBody = json.readTree(edited.getResponse().getContentAsString());
        assertThat(editedBody.path("remark").asText()).isEqualTo("只改备注");
        assertThat(editedBody.path("amount").asText()).isEqualTo("6.00");
        assertThat(tagIds(editedBody)).containsExactly(blankTag);
        assertThat(tagNames(editedBody)).containsExactly(marker);
        assertThat(linkedTagIds(aprilBlank)).containsExactly(blankTag);
        assertThat(storedTagName(blankTag)).isEmpty();

        mvc.perform(post("/api/v1/tags/" + blankTag + "/rename").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", "EXPENSE", "name", marker))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/tags").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", "EXPENSE", "name", marker))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(storedTagName(blankTag)).isEmpty();
        assertThat(linkedTagIds(aprilBlank)).containsExactly(blankTag);
        assertThat(linkedTagIds(januaryMixed)).containsExactly(expenseTag, blankTag);
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

    @Test void nameSearchPagesInsideTheActiveAccountAndTimeFilter() throws Exception {
        insertNamed("检索样本", 49, LocalDateTime.of(2024, 3, 2, 0, 0), 810000);
        LocalDateTime tied = LocalDateTime.of(2024, 3, 1, 0, 0);
        LocalDateTime saved = LocalDateTime.of(2024, 3, 1, 0, 0, 0, 111111000);
        long lower = insertNamedOne("检索样本", tied, saved, 770001);
        long higher = insertNamedOne("检索样本", tied, saved, 770001);
        insertNamed("检索样本", 5, LocalDateTime.of(2024, 4, 2, 9, 0), 820000);
        insertNamedOne("其他名称", LocalDateTime.of(2024, 3, 15, 8, 0), saved, 830001);
        insertAdjustment("3.00", LocalDateTime.of(2024, 3, 20, 8, 0), saved, 830002);
        long otherAccount = account("搜索对手", "0.00");
        insertTransfer(accountId, otherAccount, LocalDateTime.of(2024, 3, 18, 8, 0), LocalDateTime.of(2024, 3, 18, 9, 0), saved, 830003);
        insertNamedOne("100%_检索", LocalDateTime.of(2024, 5, 1, 8, 0), saved, 840001);
        insertNamedOne("100X_检索", LocalDateTime.of(2024, 5, 1, 9, 0), saved, 840002);
        var outsider = newUser("search-outsider");
        long outsiderAccount = initialize(outsider.token());
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", outsiderAccount).update();
        long outsiderCategory = categoryId(outsider.token(), "EXPENSE", "早餐");
        mvc.perform(post("/api/v1/accounts/" + outsiderAccount + "/transactions").header("Authorization", "Bearer " + outsider.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("type", "EXPENSE", "amount", "1.00", "name", "检索样本",
                                "categoryId", outsiderCategory, "tagIds", List.of(), "businessTime", "2024-03-03T08:00:00+08:00"))))
                .andExpect(status().isCreated());

        var march = namedPage("2024-03", null, null, "检索样本", null);
        var marchNext = namedPage("2024-03", null, null, "检索样本", march.path("nextCursor").asText());
        var marchRetry = namedPage("2024-03", null, null, "检索样本", march.path("nextCursor").asText());
        assertThat(march.path("items")).hasSize(50);
        assertThat(march.path("month").asText()).isEqualTo("2024-03");
        assertThat(march.path("summary").path("transactionCount").asInt()).isEqualTo(52);
        assertThat(march.path("items").get(49).path("id").asLong()).isEqualTo(higher);
        assertThat(marchNext.path("items")).hasSize(1);
        assertThat(marchNext.path("items").get(0).path("id").asLong()).isEqualTo(lower);
        assertThat(absent(marchNext.path("nextCursor"))).isTrue();
        assertThat(ids(marchRetry)).containsExactlyElementsOf(ids(marchNext));
        assertThat(names(march)).allMatch(name -> name.contains("检索样本"));
        assertThat(names(marchNext)).containsExactly("检索样本");
        assertThat(kinds(march)).containsOnly("TRANSACTION");

        var april = namedPage(null, "2024-04-01", "2024-04-30", "检索样本", null);
        assertThat(april.path("items")).hasSize(5);
        assertThat(absent(april.path("month"))).isTrue();
        april.path("items").forEach(item -> assertThat(item.path("businessTime").asText()).startsWith("2024-04-"));
        var marchDay = namedPage(null, "2024-03-01", "2024-03-01", "检索样本", null);
        assertThat(ids(marchDay)).containsExactly(higher, lower);

        var history = new ArrayList<Long>();
        String cursor = null;
        int pages = 0;
        do {
            var body = namedPage(null, null, null, "检索样本", cursor);
            assertThat(absent(body.path("month"))).isTrue();
            body.path("items").forEach(item -> {
                assertThat(item.path("kind").asText()).isEqualTo("TRANSACTION");
                assertThat(item.path("displayName").asText()).contains("检索样本");
                history.add(item.path("id").asLong());
            });
            cursor = absent(body.path("nextCursor")) ? null : body.path("nextCursor").asText();
            pages++;
        } while (cursor != null);
        assertThat(pages).isGreaterThan(1);
        assertThat(history).hasSize(56);
        assertThat(new LinkedHashSet<>(history)).hasSize(56);

        mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token)
                        .param("paged", "true").param("month", "2024-04").param("name", "检索样本")
                        .param("cursor", march.path("nextCursor").asText()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token)
                        .param("paged", "true").param("month", "2024-03").param("name", "其他名称")
                        .param("cursor", march.path("nextCursor").asText()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token)
                        .param("paged", "true").param("month", "2024-03").param("cursor", march.path("nextCursor").asText()))
                .andExpect(status().isBadRequest());

        var legacyNamed = mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token)
                        .param("month", "2024-03").param("name", "检索样本"))
                .andExpect(status().isOk()).andReturn();
        var legacyNamedBody = json.readTree(legacyNamed.getResponse().getContentAsString());
        assertThat(legacyNamedBody.path("items")).hasSize(51);
        assertThat(absent(legacyNamedBody.path("nextCursor"))).isTrue();
        var legacyMonth = ledger("2024-03");
        assertThat(legacyMonth.path("items")).hasSize(54);
        assertThat(absent(legacyMonth.path("nextCursor"))).isTrue();
        assertThat(kinds(legacyMonth)).contains("BALANCE_ADJUSTMENT", "TRANSFER", "TRANSACTION");

        var literal = namedPage(null, null, null, "100%_检索", null);
        assertThat(literal.path("items")).hasSize(1);
        assertThat(literal.path("items").get(0).path("displayName").asText()).isEqualTo("100%_检索");
        var legacySearch = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions/search")
                        .header("Authorization", "Bearer " + token).param("name", "检索样本"))
                .andExpect(status().isOk()).andReturn();
        var legacySearchBody = json.readTree(legacySearch.getResponse().getContentAsString());
        assertThat(legacySearchBody.path("items")).hasSize(56);
        assertThat(legacySearchBody.path("nextCursor").isMissingNode()).isTrue();
        var escapedSearch = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions/search")
                        .header("Authorization", "Bearer " + token).param("name", "100%_检索"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(escapedSearch.getResponse().getContentAsString()).path("items")).hasSize(1);

        mvc.perform(get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + outsider.token())
                        .param("paged", "true").param("name", "检索样本"))
                .andExpect(status().isNotFound());
        var outsiderHit = mvc.perform(get("/api/v1/accounts/" + outsiderAccount + "/ledger")
                        .header("Authorization", "Bearer " + outsider.token()).param("paged", "true").param("name", "检索样本"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(outsiderHit.getResponse().getContentAsString()).path("items")).hasSize(1);
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
        return categoryId(token, type, name);
    }

    private long categoryId(String bearer, String type, String name) throws Exception {
        var result = mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + bearer).param("type", type))
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
        return page(month, from, to, null, cursor);
    }

    private JsonNode namedPage(String month, String from, String to, String name, String cursor) throws Exception {
        return page(month, from, to, name, cursor);
    }

    private JsonNode page(String month, String from, String to, String name, String cursor) throws Exception {
        var request = get("/api/v1/accounts/" + accountId + "/ledger").header("Authorization", "Bearer " + token).param("paged", "true");
        if (month != null) request.param("month", month);
        if (from != null) request.param("from", from);
        if (to != null) request.param("to", to);
        if (name != null) request.param("name", name);
        if (cursor != null) request.param("cursor", cursor);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> names(JsonNode ledger) {
        var names = new ArrayList<String>();
        ledger.path("items").forEach(item -> names.add(item.path("displayName").asText()));
        return names;
    }

    private static List<String> kinds(JsonNode ledger) {
        var kinds = new ArrayList<String>();
        ledger.path("items").forEach(item -> kinds.add(item.path("kind").asText()));
        return kinds;
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private static JsonNode item(JsonNode body, long id) {
        for (JsonNode item : body.path("items")) if (item.path("id").asLong() == id) return item;
        throw new AssertionError(id);
    }

    private static List<String> tagNames(JsonNode item) {
        var names = new ArrayList<String>();
        item.path("tags").forEach(tag -> names.add(tag.path("name").asText()));
        return names;
    }

    private static List<Long> tagIds(JsonNode item) {
        var ids = new ArrayList<Long>();
        item.path("tags").forEach(tag -> ids.add(tag.path("id").asLong()));
        return ids;
    }

    private JsonNode readTransaction(long transactionId) throws Exception {
        var result = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private List<Long> linkedTagIds(long transactionId) {
        return jdbc.sql("SELECT tag_id FROM transaction_tags WHERE transaction_id = :id ORDER BY tag_id")
                .param("id", transactionId).query(Long.class).list();
    }

    private String storedTagName(long tagId) {
        return jdbc.sql("SELECT name FROM tags WHERE id = :id").param("id", tagId).query(String.class).single();
    }

    private static void assertNoBlankTagName(JsonNode body) {
        body.path("items").forEach(item -> item.path("tags").forEach(tag -> assertThat(tag.path("name").asText()).isNotEmpty()));
    }

    private long insertTag(String name) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO tags (user_id, name, created_at_utc)
                VALUES (:userId, :name, '2020-01-01 00:00:00.000000')
                """).param("userId", userId).param("name", name).update(keys, "id");
        return keys.getKey().longValue();
    }

    private void link(long transactionId, long tagId) {
        jdbc.sql("INSERT INTO transaction_tags (transaction_id, tag_id) VALUES (:transactionId, :tagId)")
                .param("transactionId", transactionId).param("tagId", tagId).update();
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

    private void insertNamed(String name, int count, LocalDateTime start, long firstOrder) {
        var stamp = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
        var saved = "2020-01-01 00:00:00.000000";
        var values = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) values.append(',');
            values.append("(%d,%d,'EXPENSE',1.00,%d,'%s','%s','%s',%d,'%s')".formatted(userId, accountId, expenseCategory, name,
                    start.plusMinutes(i).format(stamp), saved, firstOrder + i, saved));
        }
        jdbc.sql("""
                INSERT INTO transactions (user_id, account_id, type, amount, category_id, name, business_time,
                    first_saved_at_utc, first_save_order, updated_at_utc)
                VALUES %s
                """.formatted(values)).update();
    }

    private long insertNamedOne(String name, LocalDateTime business, LocalDateTime saved, long order) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO transactions (user_id, account_id, type, amount, category_id, name, business_time,
                    first_saved_at_utc, first_save_order, updated_at_utc)
                VALUES (:userId, :accountId, 'EXPENSE', 1.00, :category, :name, :business, :saved, :orderNo, :saved)
                """).param("userId", userId).param("accountId", accountId).param("category", expenseCategory).param("name", name)
                .param("business", business).param("saved", saved).param("orderNo", order).update(keys, "id");
        return keys.getKey().longValue();
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
