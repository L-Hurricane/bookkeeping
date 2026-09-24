package com.limingyuan.bookkeeping.account;

import com.limingyuan.bookkeeping.account.application.AccountService;
import com.limingyuan.bookkeeping.account.persistence.*;
import com.limingyuan.bookkeeping.category.application.CategoryBootstrap;
import com.limingyuan.bookkeeping.category.domain.CategoryType;
import com.limingyuan.bookkeeping.category.persistence.*;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.identity.application.*;
import com.limingyuan.bookkeeping.identity.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AccountCategoryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired CategoryBootstrap categoryBootstrap;
    @Autowired UserRepository users;
    @Autowired AccountRepository accounts;
    @Autowired CategoryRepository categories;
    @Autowired OpaqueTokens tokens;
    @Autowired JdbcClient jdbc;
    @Autowired TrustedUserContext context;
    @Autowired PlatformTransactionManager transactions;
    long userId;
    String token;
    List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() throws Exception {
        var user = newUser(); userId = user.id(); token = user.token();
    }
    record TestUser(long id, String token) { }
    private TestUser newUser() throws Exception {
        String name = "phase2_" + UUID.randomUUID();
        String password = tokens.generate();
        bootstrap.ensureUser(name, password);
        long id = users.findByUsername(name).orElseThrow().getId(); fixtures.add(id);
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", name, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return new TestUser(id, json.readTree(result.getResponse().getContentAsString()).path("token").asText());
    }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        for (long id : fixtures) {
            jdbc.sql("DELETE FROM accounts WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
            users.deleteById(id);
        }
    }

    @Test void userBootstrapPreparesCategoriesButDoesNotCreateAccount() throws Exception {
        assertThat(accounts.findByUserId(userId)).isEmpty();
        assertThat(categories.findAllByUserIdOrderById(userId)).hasSize(18);
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"100.12", "0", "-35.40", "99999999999999999.99"})
    void initializesPositiveZeroNegativeAndCapacityBoundary(String amount) throws Exception {
        String expected = new BigDecimal(amount).setScale(2).toPlainString();
        Instant before = Instant.now().minusSeconds(1);
        var account = initialize(amount, token);
        assertThat(account.path("name").asText()).isEqualTo("微信");
        assertThat(account.path("currency").asText()).isEqualTo("CNY");
        assertThat(account.path("openingBalance").isString()).isTrue();
        assertThat(account.path("openingBalance").asText()).isEqualTo(expected);
        assertThat(account.path("currentBalance").asText()).isEqualTo(expected);
        var created = OffsetDateTime.parse(account.path("createdTime").asText());
        assertThat(created.getOffset()).isEqualTo(ZoneOffset.ofHours(8));
        assertThat(created.getNano()).isZero();
        assertThat(created.toInstant()).isBetween(before, Instant.now());
        var stored = accounts.findByUserId(userId).orElseThrow();
        assertThat(stored.getLastFirstSaveOrder()).isZero();
        assertThat(stored.getOpeningBalance()).isEqualByComparingTo(expected);
        assertThat(stored.getCreatedTime()).isEqualTo(created.toLocalDateTime());
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].currentBalance").value(expected));
    }

    @Test void initializationUsesExplicitBeijingAndUtcTimeDespiteClockZone() {
        Instant now = Instant.parse("2026-09-24T23:59:59.123456789Z");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new TrustedUserContext.Identity(userId, 1), null, List.of()));
        var service = new AccountService(accounts, context, Clock.fixed(now, ZoneOffset.ofHours(-7)), transactions);
        var result = service.initialize("0");
        assertThat(result.createdTime()).isEqualTo("2026-09-25T07:59:59+08:00");
        assertThat(accounts.findByUserId(userId).orElseThrow().getUpdatedAtUtc())
                .isEqualTo(LocalDateTime.parse("2026-09-24T23:59:59.123456"));
    }

    @Test void duplicateInitializationIs409AndDoesNotChangeOriginal() throws Exception {
        var first = initialize("10", token);
        mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"20\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACCOUNT_ALREADY_INITIALIZED"))
                .andExpect(jsonPath("$.error.requestId").isNotEmpty());
        var account = accounts.findByUserId(userId).orElseThrow();
        assertThat(account.getId()).isEqualTo(first.path("id").asLong());
        assertThat(account.getOpeningBalance()).isEqualByComparingTo("10.00");
        assertThatThrownBy(() -> accounts.saveAndFlush(new AccountEntity(userId, BigDecimal.ZERO,
                LocalDateTime.now(), LocalDateTime.now()))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void concurrentInitializationCreatesOnlyOneAccount() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> initialize = () -> {
                start.await();
                return mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"9.99\"}"))
                        .andReturn().getResponse().getStatus();
            };
            var first = executor.submit(initialize); var second = executor.submit(initialize); start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM accounts WHERE user_id = :id").param("id", userId).query(Long.class).single()).isEqualTo(1);
    }

    @Test void accountQueriesCannotSelectAnotherUsersAccount() throws Exception {
        var mine = initialize("1.00", token);
        var other = newUser();
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + other.token())
                        .param("userId", Long.toString(userId)).param("accountId", mine.path("id").asText()).header("X-User-Id", userId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        var theirs = initialize("2.00", other.token());
        assertThat(theirs.path("id").asLong()).isNotEqualTo(mine.path("id").asLong());
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + other.token()).param("userId", Long.toString(userId)))
                .andExpect(jsonPath("$.items[0].id").value(theirs.path("id").asLong()));
    }

    @Test void rejectsNonStringPrecisionOverflowAndClientOwnedFields() throws Exception {
        for (String body : List.of("{}", "{\"openingBalance\":null}", "{\"openingBalance\":1.2}",
                "{\"openingBalance\":\"1.234\"}", "{\"openingBalance\":\"100000000000000000\"}")) {
            mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        for (String field : List.of("userId", "name", "currency", "createdTime", "lastFirstSaveOrder")) {
            mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("openingBalance", "0", field, "forged"))))
                    .andExpect(status().isBadRequest());
        }
        assertThat(accounts.findByUserId(userId)).isEmpty();
    }

    @Test void fixedCategoryTreeMatchesPrdAndCr001Exactly() throws Exception {
        var income = categoryList("INCOME", token);
        assertThat(names(income)).containsExactly("工资", "奖金", "其他收入", "利息");
        for (var root : income) {
            assertThat(root.path("type").asText()).isEqualTo("INCOME");
            assertThat(root.path("bookable").asBoolean()).isTrue();
            assertThat(root.path("children").size()).isZero();
        }
        var expense = categoryList("EXPENSE", token);
        assertThat(names(expense)).containsExactly("餐饮", "交通", "购物", "居住", "娱乐", "医疗", "其他支出");
        assertThat(names(expense.get(0).path("children"))).containsExactly("早餐", "午餐", "晚餐", "其他");
        assertThat(names(expense.get(1).path("children"))).containsExactly("公共交通", "打车", "其他");
        for (var root : expense) {
            assertThat(root.path("type").asText()).isEqualTo("EXPENSE");
            assertThat(root.path("bookable").asBoolean()).isEqualTo(root.path("children").isEmpty());
            for (var child : root.path("children")) {
                assertThat(child.path("bookable").asBoolean()).isTrue();
                assertThat(child.path("type").asText()).isEqualTo("EXPENSE");
                assertThat(child.path("children").size()).isZero();
                var stored = categories.findById(child.path("id").asLong()).orElseThrow();
                assertThat(stored.getParentId()).isEqualTo(root.path("id").asLong());
                assertThat(stored.getUserId()).isEqualTo(userId);
            }
        }
    }

    @Test void categoryPreparationIsIdempotentAndRepairsMissingFixedRows() {
        var original = categories.findAllByUserIdOrderById(userId);
        categoryBootstrap.prepare(userId);
        assertThat(categories.findAllByUserIdOrderById(userId)).extracting(CategoryEntity::getId)
                .containsExactlyElementsOf(original.stream().map(CategoryEntity::getId).toList());
        var missing = original.stream().filter(c -> c.getName().equals("早餐")).findFirst().orElseThrow();
        categories.deleteById(missing.getId());
        categoryBootstrap.prepare(userId);
        var restored = categories.findAllByUserIdOrderById(userId);
        assertThat(restored).hasSize(18);
        assertThat(restored).extracting(CategoryEntity::getId)
                .containsAll(original.stream().map(CategoryEntity::getId).filter(id -> !id.equals(missing.getId())).toList());
    }

    @Test void startupPreparesExistingPhase1UsersWithoutCreatingAccounts() {
        var legacy = users.saveAndFlush(new UserEntity("legacy_" + UUID.randomUUID(), "not-a-login-fixture", LocalDateTime.now()));
        fixtures.add(legacy.getId());
        assertThat(categories.findAllByUserIdOrderById(legacy.getId())).isEmpty();
        categoryBootstrap.run(new DefaultApplicationArguments());
        categoryBootstrap.run(new DefaultApplicationArguments());
        assertThat(categories.findAllByUserIdOrderById(legacy.getId())).hasSize(18);
        assertThat(accounts.findByUserId(legacy.getId())).isEmpty();
    }

    @Test void categoryIdsAndQueriesAreUserScoped() throws Exception {
        var other = newUser();
        Set<Long> mine = new HashSet<>(categories.findAllByUserIdOrderById(userId).stream().map(CategoryEntity::getId).toList());
        assertThat(categories.findAllByUserIdOrderById(other.id())).hasSize(18)
                .allSatisfy(c -> assertThat(mine).doesNotContain(c.getId()));
        var result = mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + other.token())
                        .param("type", "EXPENSE").param("userId", Long.toString(userId)))
                .andExpect(status().isOk()).andReturn();
        for (var root : json.readTree(result.getResponse().getContentAsString()).path("items")) {
            assertThat(mine).doesNotContain(root.path("id").asLong());
            for (var child : root.path("children")) assertThat(mine).doesNotContain(child.path("id").asLong());
        }
    }

    @Test void categoryDatabaseUniquenessSeparatesRootAndParentScopes() {
        var owned = categories.findAllByUserIdOrderById(userId);
        var parent = owned.stream().filter(c -> c.getName().equals("餐饮")).findFirst().orElseThrow();
        assertThatThrownBy(() -> categories.saveAndFlush(new CategoryEntity(userId, CategoryType.EXPENSE, "工资", null, LocalDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> categories.saveAndFlush(new CategoryEntity(userId, CategoryType.EXPENSE, "早餐", parent.getId(), LocalDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(owned.stream().filter(c -> c.getName().equals("其他")).toList()).hasSize(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM categories WHERE user_id = :id AND parent_id IS NULL AND parent_scope_id = 0")
                .param("id", userId).query(Long.class).single()).isEqualTo(11);
        assertThat(jdbc.sql("SELECT is_nullable FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'categories' AND column_name = 'parent_scope_id'")
                .query(String.class).single()).isEqualTo("NO");
    }

    @Test void categoryPreparationFailureRollsBackNewUserAndAllCategories() {
        String name = "rollback_" + UUID.randomUUID();
        var id = new java.util.concurrent.atomic.AtomicLong();
        UserPreparation failing = user -> {
            id.set(user);
            categoryBootstrap.prepare(user);
            throw new IllegalStateException("simulated preparation failure");
        };
        var encoder = new IdentityConfiguration().passwordEncoder();
        var failingBootstrap = new UserBootstrap(new org.springframework.mock.env.MockEnvironment(), users,
                encoder, Clock.systemUTC(), transactions, List.of(failing));
        assertThatThrownBy(() -> failingBootstrap.ensureUser(name, tokens.generate())).isInstanceOf(IllegalStateException.class);
        assertThat(users.findByUsername(name)).isEmpty();
        assertThat(categories.findAllByUserIdOrderById(id.get())).isEmpty();
    }

    @Test void concurrentCategoryPreparationRemainsIdempotent() throws Exception {
        var before = categories.findAllByUserIdOrderById(userId).stream().map(CategoryEntity::getId).toList();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> categoryBootstrap.prepare(userId));
            var second = executor.submit(() -> categoryBootstrap.prepare(userId));
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        assertThat(categories.findAllByUserIdOrderById(userId)).extracting(CategoryEntity::getId).containsExactlyElementsOf(before);
    }

    @Test void invalidCategoryTypesAndUnauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        for (String type : List.of("income", "UNKNOWN", "")) {
            mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + token).param("type", type))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(get("/api/v1/categories").param("type", "INCOME")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/accounts/initialize").contentType(MediaType.APPLICATION_JSON).content("{\"openingBalance\":\"0\"}"))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode initialize(String amount, String bearer) throws Exception {
        var result = mvc.perform(post("/api/v1/accounts/initialize").header("Authorization", "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("openingBalance", amount))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    private JsonNode categoryList(String type, String bearer) throws Exception {
        var result = mvc.perform(get("/api/v1/categories").header("Authorization", "Bearer " + bearer).param("type", type))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("items");
    }
    private List<String> names(JsonNode nodes) {
        var names = new ArrayList<String>(); nodes.forEach(n -> names.add(n.path("name").asText())); return names;
    }
}
