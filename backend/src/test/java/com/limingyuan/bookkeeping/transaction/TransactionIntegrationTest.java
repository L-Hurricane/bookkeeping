package com.limingyuan.bookkeeping.transaction;

import com.limingyuan.bookkeeping.common.CanonicalEncoding;
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
class TransactionIntegrationTest {
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
    long groupCategory;
    long incomeTag;
    long expenseTag;
    long otherExpenseTag;
    List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() throws Exception {
        var user = newUser("phase4a");
        userId = user.id();
        token = user.token();
        accountId = initialize(token);
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        incomeCategory = categoryId("INCOME", "工资", token);
        expenseCategory = categoryId("EXPENSE", "早餐", token);
        groupCategory = categoryId("EXPENSE", "餐饮", token);
        incomeTag = tag("INCOME", "工资标签", token);
        expenseTag = tag("EXPENSE", "外卖", token);
        otherExpenseTag = tag("EXPENSE", "自备", token);
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

    @Test void createsSignedAmountsWithOptionalNameAndTags() throws Exception {
        var income = create(body("INCOME", "100.00", "工资", incomeCategory, List.of(incomeTag), "2026-09-01T08:00:00+08:00", null), token);
        assertThat(income.path("amount").asText()).isEqualTo("100.00");
        assertThat(income.path("name").asText()).isEqualTo("工资");
        assertThat(income.path("displayName").asText()).isEqualTo("工资");
        assertThat(storedAmount(income.path("id").asLong())).isEqualByComparingTo("100.00");
        var negativeIncome = create(body("INCOME", "-50.50", null, incomeCategory, List.of(), "2026-09-01T09:00:00+08:00", "  "), token);
        assertThat(negativeIncome.path("amount").asText()).isEqualTo("-50.50");
        assertThat(negativeIncome.has("name")).isFalse();
        assertThat(negativeIncome.path("displayName").asText()).isEqualTo("工资");
        assertThat(negativeIncome.has("remark")).isFalse();
        var expense = create(body("EXPENSE", "30", "午餐", expenseCategory, List.of(expenseTag, otherExpenseTag), "2026-09-24T12:30:00+08:00", "工作日"), token);
        assertThat(expense.path("tags").size()).isEqualTo(2);
        assertThat(expense.path("remark").asText()).isEqualTo("工作日");
        var negativeExpense = create(body("EXPENSE", "-1.20", null, expenseCategory, List.of(), "2026-09-24T13:00:00+08:00", null), token);
        assertThat(negativeExpense.path("type").asText()).isEqualTo("EXPENSE");
        assertThat(storedAmount(negativeExpense.path("id").asLong())).isEqualByComparingTo("-1.20");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM tags WHERE user_id = :id").param("id", userId).query(Long.class).single()).isEqualTo(3);
    }

    @Test void rejectsZeroWrongCategoryWrongTagAndForeignResources() throws Exception {
        expect(400, body("EXPENSE", "0.00", null, expenseCategory, List.of(), "2026-09-24T12:00:00+08:00", null), token, "VALIDATION_FAILED");
        expect(409, body("EXPENSE", "1", null, incomeCategory, List.of(), "2026-09-24T12:00:00+08:00", null), token, "CATEGORY_TYPE_MISMATCH");
        expect(409, body("EXPENSE", "1", null, groupCategory, List.of(), "2026-09-24T12:00:00+08:00", null), token, "CATEGORY_NOT_BOOKABLE");
        expect(409, body("EXPENSE", "1", null, expenseCategory, List.of(incomeTag), "2026-09-24T12:00:00+08:00", null), token, "TAG_TYPE_MISMATCH");
        var other = newUser("other");
        long foreignTag = tag("EXPENSE", "别人的", other.token());
        long foreignAccount = initialize(other.token());
        expect(404, body("EXPENSE", "1", null, expenseCategory, List.of(foreignTag), "2026-09-24T12:00:00+08:00", null), token, "RESOURCE_NOT_FOUND");
        mvc.perform(post("/api/v1/accounts/" + foreignAccount + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXPENSE", "1", null, expenseCategory, List.of(), "2026-09-24T12:00:00+08:00", null)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE user_id = :id").param("id", userId).query(Long.class).single()).isZero();
    }

    @Test void allocatesIndependentOrdersAndSortsByBusinessTimeThenOrder() throws Exception {
        var first = create(body("EXPENSE", "1", null, expenseCategory, List.of(), "2026-09-24T12:00:00+08:00", null), token);
        var second = create(body("EXPENSE", "2", null, expenseCategory, List.of(), "2026-09-24T12:00:00+08:00", null), token);
        var older = create(body("EXPENSE", "3", null, expenseCategory, List.of(), "2026-08-01T08:00:00+08:00", null), token);
        assertThat(orderOf(first.path("id").asLong())).isEqualTo(1);
        assertThat(orderOf(second.path("id").asLong())).isEqualTo(2);
        var other = newUser("other-account");
        long otherAccount = initialize(other.token());
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", otherAccount).update();
        long otherCategory = categoryId("EXPENSE", "早餐", other.token());
        var theirs = mvc.perform(post("/api/v1/accounts/" + otherAccount + "/transactions").header("Authorization", "Bearer " + other.token())
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXPENSE", "4", null, otherCategory, List.of(), "2026-09-24T12:00:00+08:00", null)))
                .andExpect(status().isCreated()).andReturn();
        long theirId = json.readTree(theirs.getResponse().getContentAsString()).path("id").asLong();
        assertThat(orderOf(theirId)).isEqualTo(1);
        var listed = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .param("month", "2026-09")).andExpect(status().isOk()).andReturn();
        var items = json.readTree(listed.getResponse().getContentAsString()).path("items");
        assertThat(items).extracting(node -> node.path("id").asLong()).containsExactly(second.path("id").asLong(), first.path("id").asLong());
        assertThat(items.findValues("id").stream().map(JsonNode::asLong).toList()).doesNotContain(older.path("id").asLong());
        var august = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .param("month", "2026-08").param("categoryId", String.valueOf(expenseCategory)).param("tagId", String.valueOf(expenseTag)))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(august.getResponse().getContentAsString()).path("items")).isEmpty();
    }

    @Test void concurrentCreatesReceiveUniqueIncreasingOrders() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<JsonNode> call = () -> {
                start.await();
                var result = mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("EXPENSE", "9.00", null, expenseCategory, List.of(expenseTag), "2026-09-24T12:00:01+08:00", null)))
                        .andReturn();
                assertThat(result.getResponse().getStatus()).isEqualTo(201);
                return json.readTree(result.getResponse().getContentAsString());
            };
            var first = executor.submit(call);
            var second = executor.submit(call);
            start.countDown();
            var orders = List.of(orderOf(first.get(20, TimeUnit.SECONDS).path("id").asLong()),
                    orderOf(second.get(20, TimeUnit.SECONDS).path("id").asLong()));
            assertThat(orders).containsExactlyInAnyOrder(1L, 2L);
        }
        assertThat(jdbc.sql("SELECT last_first_save_order FROM accounts WHERE id = :id").param("id", accountId)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test void editKeepsTypeAndOrderAndFailedEditKeepsAssociations() throws Exception {
        var created = create(body("EXPENSE", "30.00", "午餐", expenseCategory, List.of(expenseTag), "2026-09-24T12:30:00+08:00", "原始"), token);
        long id = created.path("id").asLong();
        var before = jdbc.sql("SELECT first_save_order, first_saved_at_utc FROM transactions WHERE id = :id").param("id", id)
                .query((rs, row) -> rs.getLong(1) + " " + rs.getTimestamp(2).getTime()).single();
        Thread.sleep(5);
        var edited = mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit("12.00", null, expenseCategory, List.of(otherExpenseTag), "2027-01-02T00:00:00+08:00", "改后备注")))
                .andExpect(status().isOk()).andReturn();
        var body = json.readTree(edited.getResponse().getContentAsString());
        assertThat(body.path("type").asText()).isEqualTo("EXPENSE");
        assertThat(body.path("amount").asText()).isEqualTo("12.00");
        assertThat(body.has("name")).isFalse();
        assertThat(body.path("displayName").asText()).isEqualTo("早餐");
        assertThat(body.path("businessTime").asText()).isEqualTo("2027-01-02T00:00:00+08:00");
        assertThat(body.path("tags").get(0).path("id").asLong()).isEqualTo(otherExpenseTag);
        var after = jdbc.sql("SELECT first_save_order, first_saved_at_utc, updated_at_utc FROM transactions WHERE id = :id").param("id", id)
                .query((rs, row) -> rs.getLong(1) + " " + rs.getTimestamp(2).getTime() + " " + rs.getTimestamp(3).getTime()).single();
        assertThat(after).startsWith(before);
        assertThat(after.split(" ")[2]).isGreaterThan(after.split(" ")[1]);
        mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(edit("1", "x", expenseCategory, List.of(incomeTag),
                                "2027-01-02T00:00:00+08:00", null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("TAG_TYPE_MISMATCH"));
        mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"INCOME\",\"amount\":\"1\",\"categoryId\":" + expenseCategory
                                + ",\"businessTime\":\"2027-01-02T00:00:00+08:00\"}"))
                .andExpect(status().isBadRequest());
        var detail = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        var unchanged = json.readTree(detail.getResponse().getContentAsString());
        assertThat(unchanged.path("amount").asText()).isEqualTo("12.00");
        assertThat(unchanged.path("tags").get(0).path("id").asLong()).isEqualTo(otherExpenseTag);
        mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXPENSE", "1", null, expenseCategory, List.of(), "2019-12-31T23:59:59+08:00", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.details[0].reason").value("BEFORE_ACCOUNT_CREATION"));
    }

    @Test void failedCreateAfterOrderAllocationRollsBackTheAccountCounter() throws Exception {
        assertThat(accountOrder()).isZero();
        expect(409, body("EXPENSE", "1", null, incomeCategory, List.of(), "2026-09-24T12:00:00+08:00", null), token, "CATEGORY_TYPE_MISMATCH");
        expect(409, body("EXPENSE", "1", null, groupCategory, List.of(), "2026-09-24T12:00:00+08:00", null), token, "CATEGORY_NOT_BOOKABLE");
        expect(409, body("EXPENSE", "1", null, expenseCategory, List.of(incomeTag), "2026-09-24T12:00:00+08:00", null), token, "TAG_TYPE_MISMATCH");
        expect(409, body("EXPENSE", "1", null, expenseCategory, List.of(), "2019-12-31T23:59:59+08:00", null), token, "BUSINESS_RULE_VIOLATION");
        assertThat(accountOrder()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE account_id = :id").param("id", accountId).query(Long.class).single()).isZero();
    }

    @Test void editCanChangeBookableCategoryWithoutChangingSaveIdentity() throws Exception {
        long lunch = categoryId("EXPENSE", "午餐", token);
        var created = create(body("EXPENSE", "30.00", "午餐", expenseCategory, List.of(expenseTag), "2026-09-24T12:30:00+08:00", null), token);
        long id = created.path("id").asLong();
        var saved = jdbc.sql("SELECT first_save_order, first_saved_at_utc FROM transactions WHERE id = :id").param("id", id)
                .query((rs, row) -> rs.getLong(1) + "|" + rs.getObject(2)).single();
        var edited = mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit("30.00", "午餐", lunch, List.of(expenseTag), "2026-09-24T12:30:00+08:00", null)))
                .andExpect(status().isOk()).andReturn();
        var body = json.readTree(edited.getResponse().getContentAsString());
        assertThat(body.path("category").path("id").asLong()).isEqualTo(lunch);
        assertThat(body.path("category").path("name").asText()).isEqualTo("午餐");
        assertThat(body.path("displayName").asText()).isEqualTo("午餐");
        var after = jdbc.sql("SELECT first_save_order, first_saved_at_utc FROM transactions WHERE id = :id").param("id", id)
                .query((rs, row) -> rs.getLong(1) + "|" + rs.getObject(2)).single();
        assertThat(after).isEqualTo(saved);
        assertThat(accountOrder()).isEqualTo(1);
    }

    @Test void listFiltersByOwnedCategoryAndTag() throws Exception {
        long lunch = categoryId("EXPENSE", "午餐", token);
        var breakfast = create(body("EXPENSE", "10", "早餐", expenseCategory, List.of(expenseTag), "2026-09-10T08:00:00+08:00", null), token);
        var lunchTx = create(body("EXPENSE", "20", "午餐", lunch, List.of(otherExpenseTag), "2026-09-10T12:00:00+08:00", null), token);
        var byCategory = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .param("month", "2026-09").param("categoryId", Long.toString(lunch)))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(byCategory.getResponse().getContentAsString()).path("items"))
                .extracting(node -> node.path("id").asLong()).containsExactly(lunchTx.path("id").asLong());
        var byTag = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .param("month", "2026-09").param("tagId", Long.toString(expenseTag)))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(byTag.getResponse().getContentAsString()).path("items"))
                .extracting(node -> node.path("id").asLong()).containsExactly(breakfast.path("id").asLong());
    }

    @Test void foreignCategoryAndTransactionAccessReturnNotFound() throws Exception {
        var created = create(body("EXPENSE", "6", null, expenseCategory, List.of(), "2026-09-11T08:00:00+08:00", null), token);
        long id = created.path("id").asLong();
        var other = newUser("foreign");
        long foreignCategory = categoryId("EXPENSE", "早餐", other.token());
        expect(404, body("EXPENSE", "1", null, foreignCategory, List.of(), "2026-09-11T09:00:00+08:00", null), token, "RESOURCE_NOT_FOUND");
        assertThat(accountOrder()).isEqualTo(1);
        mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit("99", null, foreignCategory, List.of(), "2026-09-11T08:00:00+08:00", null)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        var detail = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(detail.getResponse().getContentAsString()).path("amount").asText()).isEqualTo("6.00");
    }

    @Test void idempotencyKeyIsRequiredAndReplayDoesNotWriteAgain() throws Exception {
        mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXPENSE", "1.2", " 午餐 ", expenseCategory, List.of(otherExpenseTag, expenseTag), "2026-09-12T08:00:00+08:00", "  ")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", " ").contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXPENSE", "1", null, expenseCategory, List.of(), "2026-09-12T08:00:00+08:00", null)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "k".repeat(65)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXPENSE", "1", null, expenseCategory, List.of(), "2026-09-12T08:00:00+08:00", null)))
                .andExpect(status().isBadRequest());
        String key = "create-key-1";
        String payload = body("EXPENSE", "1.2", " 午餐 ", expenseCategory, List.of(otherExpenseTag, expenseTag), "2026-09-12T08:00:00+08:00", " 备注 ");
        var first = submit(key, payload, token);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(first.getResponse().getHeader("Idempotent-Replay")).isNull();
        long id = json.readTree(first.getResponse().getContentAsString()).path("id").asLong();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM idempotency_records WHERE user_id = :id AND scope = 'CREATE_TRANSACTION' AND operation_id = :key")
                .param("id", userId).param("key", key).query(Long.class).single()).isEqualTo(1);
        String equivalent = body("EXPENSE", "1.20", "午餐", expenseCategory, List.of(expenseTag, otherExpenseTag), "2026-09-12T08:00:00+08:00", "备注");
        var replay = submit(key, equivalent, token);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("id").asLong()).isEqualTo(id);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE account_id = :id").param("id", accountId).query(Long.class).single()).isEqualTo(1);
        assertThat(accountOrder()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transaction_tags WHERE transaction_id = :id").param("id", id).query(Long.class).single()).isEqualTo(2);
        long lunch = categoryId("EXPENSE", "午餐", token);
        for (String changed : List.of(
                body("EXPENSE", "2.00", "午餐", expenseCategory, List.of(expenseTag, otherExpenseTag), "2026-09-12T08:00:00+08:00", "备注"),
                body("EXPENSE", "1.20", "午餐", lunch, List.of(expenseTag, otherExpenseTag), "2026-09-12T08:00:00+08:00", "备注"),
                body("EXPENSE", "1.20", "午餐", expenseCategory, List.of(expenseTag), "2026-09-12T08:00:00+08:00", "备注"),
                body("EXPENSE", "1.20", "午餐", expenseCategory, List.of(expenseTag, otherExpenseTag), "2026-09-12T09:00:00+08:00", "备注"))) {
            var conflict = submit(key, changed, token);
            assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
            assertThat(json.readTree(conflict.getResponse().getContentAsString()).path("error").path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        }
        assertThat(accountOrder()).isEqualTo(1);
        assertThat(storedAmount(id)).isEqualByComparingTo("1.20");
    }

    @Test void embeddedNewlinesDoNotCollapseDistinctNameAndRemark() throws Exception {
        String key = "newline-boundary";
        var first = submit(key, body("EXPENSE", "3.00", "a\nb", expenseCategory, List.of(), "2026-09-12T10:00:00+08:00", "c"), token);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        var conflict = submit(key, body("EXPENSE", "3.00", "a", expenseCategory, List.of(), "2026-09-12T10:00:00+08:00", "b\nc"), token);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(conflict.getResponse().getContentAsString()).path("error").path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE account_id = :id").param("id", accountId).query(Long.class).single()).isEqualTo(1);
        assertThat(accountOrder()).isEqualTo(1);
    }

    @Test void concurrentSameKeyCreatesOneTransactionAndDifferentRequestConflicts() throws Exception {
        String key = "concurrent-same";
        String payload = body("EXPENSE", "4.00", null, expenseCategory, List.of(expenseTag), "2026-09-13T08:00:00+08:00", null);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<org.springframework.test.web.servlet.MvcResult> call = () -> { start.await(); return submit(key, payload, token); };
            var first = executor.submit(call);
            var second = executor.submit(call);
            start.countDown();
            var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isIn(200, 201));
            var ids = results.stream().map(result -> {
                try { return json.readTree(result.getResponse().getContentAsString()).path("id").asLong(); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            }).distinct().toList();
            assertThat(ids).hasSize(1);
        }
        assertThat(accountOrder()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM idempotency_records WHERE user_id = :id AND operation_id = :key")
                .param("id", userId).param("key", key).query(Long.class).single()).isEqualTo(1);
        String otherKey = "concurrent-conflict";
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> {
                gate.await();
                return submit(otherKey, body("EXPENSE", "5.00", null, expenseCategory, List.of(), "2026-09-13T09:00:00+08:00", null), token).getResponse().getStatus();
            });
            var right = executor.submit(() -> {
                gate.await();
                return submit(otherKey, body("EXPENSE", "6.00", null, expenseCategory, List.of(), "2026-09-13T09:00:00+08:00", null), token).getResponse().getStatus();
            });
            gate.countDown();
            assertThat(List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE account_id = :id").param("id", accountId).query(Long.class).single()).isEqualTo(2);
        assertThat(accountOrder()).isEqualTo(2);
    }

    @Test void failedCreateLeavesNoIdempotencySuccessAndDeletedRetryDoesNotRecreate() throws Exception {
        String failedKey = "bad-category";
        var failed = submit(failedKey, body("EXPENSE", "1", null, incomeCategory, List.of(), "2026-09-14T08:00:00+08:00", null), token);
        assertThat(failed.getResponse().getStatus()).isEqualTo(409);
        assertThat(accountOrder()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM idempotency_records WHERE operation_id = :key").param("key", failedKey).query(Long.class).single()).isZero();
        String key = "delete-then-retry";
        var created = submit(key, body("EXPENSE", "7", null, expenseCategory, List.of(expenseTag), "2026-09-14T09:00:00+08:00", null), token);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(delete("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        var retry = submit(key, body("EXPENSE", "7", null, expenseCategory, List.of(expenseTag), "2026-09-14T09:00:00+08:00", null), token);
        assertThat(retry.getResponse().getStatus()).isEqualTo(404);
        assertThat(retry.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(json.readTree(retry.getResponse().getContentAsString()).path("error").path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE id = :id").param("id", id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM idempotency_records WHERE operation_id = :key").param("key", key).query(Long.class).single()).isEqualTo(1);
        assertThat(accountOrder()).isEqualTo(1);
        var other = newUser("same-key");
        long otherAccount = initialize(other.token());
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", otherAccount).update();
        long otherCategory = categoryId("EXPENSE", "早餐", other.token());
        var foreign = mvc.perform(post("/api/v1/accounts/" + otherAccount + "/transactions").header("Authorization", "Bearer " + other.token())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXPENSE", "7", null, otherCategory, List.of(), "2026-09-14T09:00:00+08:00", null)))
                .andExpect(status().isCreated()).andReturn();
        assertThat(json.readTree(foreign.getResponse().getContentAsString()).path("id").asLong()).isNotEqualTo(id);
    }

    @Test void categoryMayBeAbsentAndCanBeClearedOrAssignedLater() throws Exception {
        String omitted = "{\"type\":\"EXPENSE\",\"amount\":\"30.00\",\"name\":\"午餐\",\"tagIds\":[" + expenseTag
                + "],\"businessTime\":\"2026-09-18T12:00:00+08:00\"}";
        var created = create(omitted, token);
        long id = created.path("id").asLong();
        assertThat(created.has("category")).isFalse();
        assertThat(created.path("displayName").asText()).isEqualTo("午餐");
        assertThat(created.path("tags").get(0).path("id").asLong()).isEqualTo(expenseTag);
        assertThat(jdbc.sql("SELECT category_id FROM transactions WHERE id = :id").param("id", id).query(Long.class).optional()).isEmpty();
        String explicitNull = "{\"type\":\"EXPENSE\",\"amount\":\"8.00\",\"tagIds\":[],\"businessTime\":\"2026-09-18T13:00:00+08:00\",\"categoryId\":null}";
        var nameless = create(explicitNull, token);
        assertThat(nameless.has("category")).isFalse();
        assertThat(nameless.has("displayName")).isFalse();
        assertThat(nameless.has("name")).isFalse();
        var listed = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .param("month", "2026-09")).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(listed.getResponse().getContentAsString()).path("items"))
                .extracting(node -> node.path("id").asLong()).contains(id, nameless.path("id").asLong());
        var onlyNull = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .param("month", "2026-09").param("uncategorizedOnly", "true")).andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(onlyNull.getResponse().getContentAsString()).path("items"))
                .extracting(node -> node.path("id").asLong()).containsExactly(nameless.path("id").asLong(), id);
        expect(404, body("EXPENSE", "1", null, 999999L, List.of(), "2026-09-18T14:00:00+08:00", null), token, "RESOURCE_NOT_FOUND");
        expect(409, body("EXPENSE", "1", null, incomeCategory, List.of(), "2026-09-18T14:00:00+08:00", null), token, "CATEGORY_TYPE_MISMATCH");
        expect(409, body("EXPENSE", "1", null, groupCategory, List.of(), "2026-09-18T14:00:00+08:00", null), token, "CATEGORY_NOT_BOOKABLE");
        var other = newUser("foreign-category");
        long foreignCategory = categoryId("EXPENSE", "早餐", other.token());
        expect(404, body("EXPENSE", "1", null, foreignCategory, List.of(), "2026-09-18T14:00:00+08:00", null), token, "RESOURCE_NOT_FOUND");
        long order = orderOf(id);
        String savedAt = jdbc.sql("SELECT first_saved_at_utc FROM transactions WHERE id = :id").param("id", id).query(String.class).single();
        var assigned = mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(edit("30.00", "午餐", expenseCategory, List.of(expenseTag), "2026-09-18T12:00:00+08:00", null)))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(assigned.getResponse().getContentAsString()).path("category").path("id").asLong()).isEqualTo(expenseCategory);
        var cleared = mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"30.00\",\"name\":\"午餐\",\"categoryId\":null,\"tagIds\":[" + expenseTag
                                + "],\"businessTime\":\"2026-09-18T12:00:00+08:00\"}"))
                .andExpect(status().isOk()).andReturn();
        var clearedBody = json.readTree(cleared.getResponse().getContentAsString());
        assertThat(clearedBody.path("id").asLong()).isEqualTo(id);
        assertThat(clearedBody.path("type").asText()).isEqualTo("EXPENSE");
        assertThat(clearedBody.has("category")).isFalse();
        assertThat(clearedBody.path("tags").get(0).path("id").asLong()).isEqualTo(expenseTag);
        var keptNull = mvc.perform(put("/api/v1/accounts/" + accountId + "/transactions/" + nameless.path("id").asLong())
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":\"8.00\",\"categoryId\":null,\"tagIds\":[],\"businessTime\":\"2026-09-18T13:00:00+08:00\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(keptNull.getResponse().getContentAsString()).has("category")).isFalse();
        assertThat(orderOf(id)).isEqualTo(order);
        assertThat(jdbc.sql("SELECT first_saved_at_utc FROM transactions WHERE id = :id").param("id", id).query(String.class).single()).isEqualTo(savedAt);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE account_id = :id").param("id", accountId).query(Long.class).single()).isEqualTo(2);
        var detail = mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(detail.getResponse().getContentAsString()).has("category")).isFalse();
        String key = "null-category";
        String payload = "{\"type\":\"EXPENSE\",\"amount\":\"4.00\",\"categoryId\":null,\"tagIds\":[],\"businessTime\":\"2026-09-18T15:00:00+08:00\"}";
        var first = submit(key, payload, token);
        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        long nullId = json.readTree(first.getResponse().getContentAsString()).path("id").asLong();
        var replay = submit(key, payload, token);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("id").asLong()).isEqualTo(nullId);
        var conflict = submit(key, body("EXPENSE", "4.00", null, expenseCategory, List.of(), "2026-09-18T15:00:00+08:00", null), token);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(conflict.getResponse().getContentAsString()).path("error").path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        mvc.perform(delete("/api/v1/accounts/" + accountId + "/transactions/" + nullId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        var deleted = submit(key, payload, token);
        assertThat(deleted.getResponse().getStatus()).isEqualTo(404);
        assertThat(deleted.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .param("month", "2026-09").param("categoryId", Long.toString(expenseCategory)).param("uncategorizedOnly", "true"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test void v01CategorizedHashStillReplaysAfterUpgrade() throws Exception {
        String key = "v01-category-hash";
        String payload = body("EXPENSE", "30.00", "午餐", expenseCategory, List.of(), "2026-09-19T12:00:00+08:00", null);
        var created = submit(key, payload, token);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        jdbc.sql("DELETE FROM idempotency_records WHERE user_id = :id AND operation_id = :key")
                .param("id", userId).param("key", key).update();
        byte[] v01 = v01CategoryHash(accountId, "EXPENSE", "30.00", expenseCategory, List.of(),
                "2026-09-19T12:00:00+08:00", "午餐", "");
        jdbc.sql("""
                INSERT INTO idempotency_records (user_id, scope, operation_id, request_hash, result_code, resource_id, created_at_utc)
                VALUES (:userId, 'CREATE_TRANSACTION', :key, :hash, 'CREATED', :resourceId, '2026-09-19 04:00:00.000000')
                """).param("userId", userId).param("key", key).param("hash", v01).param("resourceId", id).update();
        var replay = submit(key, payload, token);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat(json.readTree(replay.getResponse().getContentAsString()).path("id").asLong()).isEqualTo(id);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transactions WHERE account_id = :id").param("id", accountId).query(Long.class).single()).isEqualTo(1);
    }

    private static byte[] v01CategoryHash(long accountId, String type, String amount, long categoryId, List<Long> tagIds,
            String businessTime, String name, String remark) {
        var canonical = new java.io.ByteArrayOutputStream();
        CanonicalEncoding.writeUtf8(canonical, Long.toString(accountId));
        CanonicalEncoding.writeUtf8(canonical, type);
        CanonicalEncoding.writeUtf8(canonical, amount);
        CanonicalEncoding.writeUtf8(canonical, Long.toString(categoryId));
        CanonicalEncoding.writeInt(canonical, tagIds.size());
        for (Long tagId : tagIds) CanonicalEncoding.writeUtf8(canonical, Long.toString(tagId));
        CanonicalEncoding.writeUtf8(canonical, businessTime);
        CanonicalEncoding.writeUtf8(canonical, name);
        CanonicalEncoding.writeUtf8(canonical, remark);
        return CanonicalEncoding.sha256(canonical.toByteArray());
    }

    @Test void untaggedOnlySelectsTransactionsWithoutTagAssociations() throws Exception {
        long none = create(body("EXPENSE", "3.00", "无标签", expenseCategory, List.of(), "2026-09-03T12:00:00+08:00", null), token).path("id").asLong();
        long one = create(body("EXPENSE", "4.00", "一个标签", expenseCategory, List.of(expenseTag), "2026-09-02T12:00:00+08:00", null), token).path("id").asLong();
        long many = create(body("EXPENSE", "5.00", "多个标签", expenseCategory, List.of(expenseTag, otherExpenseTag), "2026-09-01T12:00:00+08:00", null), token).path("id").asLong();
        long income = create(body("INCOME", "6.00", "收入无标签", incomeCategory, List.of(), "2026-09-04T12:00:00+08:00", null), token).path("id").asLong();
        assertThat(ids("2026-09", Map.of("untaggedOnly", "true"))).containsExactly(income, none);
        assertThat(ids("2026-09", Map.of("type", "EXPENSE", "untaggedOnly", "true"))).containsExactly(none);
        mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token)
                        .param("month", "2026-09").param("tagId", Long.toString(expenseTag)).param("untaggedOnly", "true"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        assertThat(ids("2026-09", Map.of())).containsExactly(income, none, one, many);
        assertThat(ids("2026-09", Map.of("untaggedOnly", "false"))).containsExactly(income, none, one, many);
        var other = newUser("untagged-scope");
        long otherAccount = initialize(other.token());
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", otherAccount).update();
        long otherCategory = categoryId("EXPENSE", "早餐", other.token());
        var foreign = mvc.perform(post("/api/v1/accounts/" + otherAccount + "/transactions").header("Authorization", "Bearer " + other.token())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content(body("EXPENSE", "7.00", "他人无标签", otherCategory, List.of(), "2026-09-05T12:00:00+08:00", null)))
                .andExpect(status().isCreated()).andReturn();
        long foreignId = json.readTree(foreign.getResponse().getContentAsString()).path("id").asLong();
        assertThat(ids("2026-09", Map.of("untaggedOnly", "true"))).doesNotContain(foreignId);
        assertThat(ids("2026-09", Map.of("tagId", Long.toString(expenseTag)))).containsExactly(one, many);
        assertThat(ids("2026-09", Map.of("categoryId", Long.toString(expenseCategory)))).containsExactly(none, one, many);
        assertThat(ids("2026-09", Map.of("uncategorizedOnly", "true"))).isEmpty();
    }

    @Test void deleteRemovesAssociationsAndDoesNotReuseOrder() throws Exception {
        var created = create(body("EXPENSE", "8", null, expenseCategory, List.of(expenseTag), "2026-09-03T10:00:00+08:00", null), token);
        long id = created.path("id").asLong();
        mvc.perform(delete("/api/v1/accounts/" + accountId + "/transactions/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM transaction_tags WHERE transaction_id = :id").param("id", id).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT last_first_save_order FROM accounts WHERE id = :id").param("id", accountId).query(Long.class).single()).isEqualTo(1);
        var again = create(body("EXPENSE", "8", null, expenseCategory, List.of(), "2026-09-03T11:00:00+08:00", null), token);
        assertThat(orderOf(again.path("id").asLong())).isEqualTo(2);
        var other = newUser("stranger");
        mvc.perform(delete("/api/v1/accounts/" + accountId + "/transactions/" + again.path("id").asLong())
                        .header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + other.token()).param("month", "2026-09"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/accounts/" + accountId + "/transactions").param("month", "2026-09")).andExpect(status().isUnauthorized());
    }

    private List<Long> ids(String month, Map<String, String> params) throws Exception {
        var request = get("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + token).param("month", month);
        for (var param : params.entrySet()) request.param(param.getKey(), param.getValue());
        var listed = mvc.perform(request).andExpect(status().isOk()).andReturn();
        var ids = new ArrayList<Long>();
        json.readTree(listed.getResponse().getContentAsString()).path("items").forEach(node -> ids.add(node.path("id").asLong()));
        return ids;
    }

    private String body(String type, String amount, String name, long categoryId, List<Long> tagIds, String time, String remark) throws Exception {
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

    private String edit(String amount, String name, long categoryId, List<Long> tagIds, String time, String remark) throws Exception {
        var map = new LinkedHashMap<String, Object>();
        map.put("amount", amount);
        if (name != null) map.put("name", name);
        map.put("categoryId", categoryId);
        map.put("tagIds", tagIds);
        map.put("businessTime", time);
        if (remark != null) map.put("remark", remark);
        return json.writeValueAsString(map);
    }

    private org.springframework.test.web.servlet.MvcResult submit(String key, String payload, String bearer) throws Exception {
        return mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + bearer)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn();
    }

    private JsonNode create(String payload, String bearer) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + bearer)
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void expect(int status, String payload, String bearer, String code) throws Exception {
        mvc.perform(post("/api/v1/accounts/" + accountId + "/transactions").header("Authorization", "Bearer " + bearer)
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    private long accountOrder() {
        return jdbc.sql("SELECT last_first_save_order FROM accounts WHERE id = :id").param("id", accountId).query(Long.class).single();
    }

    private long orderOf(long id) {
        return jdbc.sql("SELECT first_save_order FROM transactions WHERE id = :id").param("id", id).query(Long.class).single();
    }

    private java.math.BigDecimal storedAmount(long id) {
        return jdbc.sql("SELECT amount FROM transactions WHERE id = :id").param("id", id).query(java.math.BigDecimal.class).single();
    }

    private long initialize(String bearer) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"0\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private long categoryId(String type, String name, String bearer) throws Exception {
        var result = mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + bearer).param("type", type))
                .andExpect(status().isOk()).andReturn();
        var items = json.readTree(result.getResponse().getContentAsString()).path("items");
        for (var root : items) {
            if (root.path("name").asText().equals(name)) return root.path("id").asLong();
            for (var child : root.path("children")) if (child.path("name").asText().equals(name)) return child.path("id").asLong();
        }
        throw new AssertionError(name);
    }

    private long tag(String type, String name, String bearer) throws Exception {
        var result = mvc.perform(post("/api/v1/tags").header("Authorization", "Bearer " + bearer)
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
