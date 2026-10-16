package com.limingyuan.bookkeeping.acceptance.data;

import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import com.limingyuan.bookkeeping.account.application.AccountService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class AcceptanceDataSeederTest {
    private static final String PASSWORD = "acceptance-fixture-password";
    private static final String OTHER = "local_dev_not_acceptance";

    @Autowired AcceptanceDataSeeder seeder;
    @Autowired(required = false) AcceptanceDataRunner runner;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired AccountService accounts;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        seeder.reset();
        users.findByUsername(OTHER).ifPresent(user -> {
            long id = user.getId();
            jdbc.sql("DELETE FROM accounts WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", id).update();
            jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
            users.deleteById(id);
        });
    }

    @Test
    void seederIsAbsentUnlessExplicitlyEnabled() {
        assertThat(runner).isNull();
        assertThat(AcceptanceDataRunner.allows(false, "local")).isFalse();
        assertThat(AcceptanceDataRunner.allows(true, "prod")).isFalse();
        assertThat(AcceptanceDataRunner.allows(true, "production")).isFalse();
        assertThat(AcceptanceDataRunner.allows(true, "test")).isFalse();
        assertThat(AcceptanceDataRunner.allows(true, "local", "prod")).isFalse();
        assertThat(AcceptanceDataRunner.allows(true, "local")).isTrue();
    }

    @Test
    void resetAndSeedIsDeterministicAndLeavesOtherUsers() {
        bootstrap.ensureUser(OTHER, PASSWORD);
        long otherId = users.findByUsername(OTHER).orElseThrow().getId();
        asUser(otherId, () -> accounts.initialize("7.00"));

        seeder.resetAndSeed(PASSWORD);
        assertFixtures();
        long expenseOrder = sameSecondOrder("EXPENSE");
        long incomeOrder = sameSecondOrder("INCOME");
        BigDecimal adjustment = adjustmentAmount();

        seeder.resetAndSeed(PASSWORD);
        assertFixtures();
        assertThat(sameSecondOrder("EXPENSE")).isEqualTo(expenseOrder);
        assertThat(sameSecondOrder("INCOME")).isEqualTo(incomeOrder);
        assertThat(incomeOrder).isGreaterThan(expenseOrder);
        assertThat(adjustmentAmount()).isEqualByComparingTo(adjustment);
        assertThat(users.existsByUsername(OTHER)).isTrue();
        assertThat(opening(otherId)).isEqualByComparingTo("7.00");

        seeder.reset();
        for (String username : AcceptanceDataSeeder.USERNAMES) {
            assertThat(users.existsByUsername(username)).isFalse();
        }
        assertThat(users.existsByUsername(OTHER)).isTrue();
        assertThat(opening(otherId)).isEqualByComparingTo("7.00");
    }

    private void assertFixtures() {
        long empty = id("accept_empty");
        assertThat(count("accounts", empty)).isZero();
        assertOpening("accept_zero", "0.00");
        assertOpening("accept_positive", "50.00");
        assertOpening("accept_negative", "-100.00");
        for (String username : List.of("accept_zero", "accept_positive", "accept_negative")) {
            long userId = id(username);
            assertThat(count("transactions", userId)).isZero();
            assertThat(count("balance_adjustments", userId)).isZero();
        }
        long ledger = id("accept_ledger");
        assertOpening("accept_ledger", "1000.00");
        assertThat(count("transactions", ledger)).isEqualTo(5);
        assertThat(count("balance_adjustments", ledger)).isEqualTo(1);
        assertThat(uncategorizedExpenses(ledger)).isEqualTo(1);
        assertThat(categorizedTransactions(ledger)).isEqualTo(4);
        assertThat(currentBalance(ledger)).isEqualByComparingTo("1300.00");
        assertThat(adjustmentAmount()).isEqualByComparingTo("37.50");
    }

    private void assertOpening(String username, String amount) {
        assertThat(opening(id(username))).isEqualByComparingTo(amount);
    }

    private long id(String username) {
        return users.findByUsername(username).orElseThrow().getId();
    }

    private long count(String table, long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE user_id = :id").param("id", userId).query(Long.class).single();
    }

    private BigDecimal opening(long userId) {
        return jdbc.sql("SELECT opening_balance FROM accounts WHERE user_id = :id").param("id", userId).query(BigDecimal.class).single();
    }

    private BigDecimal currentBalance(long userId) {
        return jdbc.sql("""
                SELECT a.opening_balance
                    + COALESCE((SELECT SUM(CASE WHEN type = 'INCOME' THEN amount ELSE -amount END)
                        FROM transactions t WHERE t.user_id = a.user_id), 0)
                    + COALESCE((SELECT SUM(adjustment_amount) FROM balance_adjustments b WHERE b.user_id = a.user_id), 0)
                FROM accounts a WHERE a.user_id = :id
                """).param("id", userId).query(BigDecimal.class).single();
    }

    private BigDecimal adjustmentAmount() {
        return jdbc.sql("SELECT adjustment_amount FROM balance_adjustments WHERE user_id = :id")
                .param("id", id("accept_ledger")).query(BigDecimal.class).single();
    }

    private long sameSecondOrder(String type) {
        return jdbc.sql("""
                SELECT first_save_order FROM transactions
                WHERE user_id = :id AND business_time = '2026-09-05 09:00:00' AND type = :type
                """).param("id", id("accept_ledger")).param("type", type).query(Long.class).single();
    }

    private long uncategorizedExpenses(long userId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM transactions
                WHERE user_id = :id AND type = 'EXPENSE' AND category_id IS NULL AND amount = -20.00
                """).param("id", userId).query(Long.class).single();
    }

    private long categorizedTransactions(long userId) {
        return jdbc.sql("SELECT COUNT(*) FROM transactions WHERE user_id = :id AND category_id IS NOT NULL")
                .param("id", userId).query(Long.class).single();
    }

    private static void asUser(long userId, Runnable action) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new TrustedUserContext.Identity(userId, 0L), null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
