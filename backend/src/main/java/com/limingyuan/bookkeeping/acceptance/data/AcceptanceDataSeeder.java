package com.limingyuan.bookkeeping.acceptance.data;

import com.limingyuan.bookkeeping.account.application.AccountService;
import com.limingyuan.bookkeeping.adjustment.application.AdjustmentService;
import com.limingyuan.bookkeeping.category.application.CategoryService;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import com.limingyuan.bookkeeping.transaction.application.TransactionService;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Local acceptance fixtures. Reset deletes only the fixed usernames; seed uses the product services. */
@Component
public class AcceptanceDataSeeder {
    static final List<String> USERNAMES = List.of(
            "accept_empty", "accept_zero", "accept_positive", "accept_negative", "accept_ledger");
    /** Earlier than every fixture business time, so historical records pass the account-creation rule. */
    private static final String ACCOUNT_CREATED_TIME = "2026-08-01 00:00:00";

    private final UserBootstrap users;
    private final UserRepository userRepository;
    private final AccountService accounts;
    private final TransactionService transactions;
    private final AdjustmentService adjustments;
    private final CategoryService categories;
    private final JdbcClient jdbc;

    public AcceptanceDataSeeder(UserBootstrap users, UserRepository userRepository, AccountService accounts,
            TransactionService transactions, AdjustmentService adjustments, CategoryService categories, JdbcClient jdbc) {
        this.users = users;
        this.userRepository = userRepository;
        this.accounts = accounts;
        this.transactions = transactions;
        this.adjustments = adjustments;
        this.categories = categories;
        this.jdbc = jdbc;
    }

    public void reset() {
        for (String username : USERNAMES) {
            userRepository.findByUsername(username).ifPresent(user -> deleteUser(user.getId()));
        }
    }

    public void seed(String password) {
        if (USERNAMES.stream().anyMatch(userRepository::existsByUsername)) {
            throw new IllegalStateException("Acceptance fixtures already exist; run reset-and-seed");
        }
        users.ensureUser("accept_empty", password);
        account("accept_zero", password, "0");
        account("accept_positive", password, "50.00");
        account("accept_negative", password, "-100.00");
        ledger(password);
    }

    public void resetAndSeed(String password) {
        reset();
        seed(password);
    }

    private void account(String username, String password, String openingBalance) {
        users.ensureUser(username, password);
        long userId = userRepository.findByUsername(username).orElseThrow().getId();
        asUser(userId, () -> accounts.initialize(openingBalance));
        jdbc.sql("UPDATE accounts SET created_time = :created WHERE user_id = :userId")
                .param("created", ACCOUNT_CREATED_TIME).param("userId", userId).update();
    }

    private void ledger(String password) {
        account("accept_ledger", password, "1000.00");
        long userId = userRepository.findByUsername("accept_ledger").orElseThrow().getId();
        asUser(userId, () -> {
            long accountId = accounts.list().getFirst().id();
            long expense = bookable("EXPENSE", "餐饮", "其他");
            long income = bookable("INCOME", "其他收入", null);
            var tx = transactions;
            tx.create(accountId, "accept-ledger-tx-a", "EXPENSE",
                    new TransactionService.Draft("10.00", "八月支出", expense, List.of(), "2026-08-31T23:59:00+08:00", null));
            tx.create(accountId, "accept-ledger-tx-b", "EXPENSE",
                    new TransactionService.Draft("35.50", "九月支出", expense, List.of(), "2026-09-05T09:00:00+08:00", null));
            tx.create(accountId, "accept-ledger-tx-c", "INCOME",
                    new TransactionService.Draft("200.00", "九月收入", income, List.of(), "2026-09-05T09:00:00+08:00", null));
            tx.create(accountId, "accept-ledger-tx-d", "EXPENSE",
                    new TransactionService.Draft("-20.00", "未分类支出", null, List.of(), "2026-09-12T20:00:00+08:00", null));
            tx.create(accountId, "accept-ledger-tx-e", "INCOME",
                    new TransactionService.Draft("88.00", "十月收入", income, List.of(), "2026-10-01T00:00:00+08:00", null));
            adjustments.create(accountId, "accept-ledger-adj-f",
                    new AdjustmentService.Draft("1300.00", "2026-09-20T12:00:00+08:00", null));
        });
    }

    private long bookable(String type, String rootName, String childName) {
        return categories.list(type).stream()
                .filter(root -> root.name().equals(rootName))
                .findFirst()
                .map(root -> childName == null ? root.id()
                        : root.children().stream().filter(child -> child.name().equals(childName)).findFirst().orElseThrow().id())
                .orElseThrow();
    }

    private void deleteUser(long userId) {
        jdbc.sql("DELETE FROM idempotency_records WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM balance_adjustments WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM transaction_tags WHERE transaction_id IN (SELECT id FROM transactions WHERE user_id = :id)")
                .param("id", userId).update();
        jdbc.sql("DELETE FROM transactions WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM tags WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM accounts WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM categories WHERE user_id = :id AND parent_id IS NOT NULL").param("id", userId).update();
        jdbc.sql("DELETE FROM categories WHERE user_id = :id").param("id", userId).update();
        jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", userId).update();
        userRepository.deleteById(userId);
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
