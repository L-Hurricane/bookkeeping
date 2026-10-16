package com.limingyuan.bookkeeping.statistics.application;

import com.limingyuan.bookkeeping.account.application.AccountService;
import com.limingyuan.bookkeeping.analysis.application.MonthlyAnalysisFactsService;
import com.limingyuan.bookkeeping.category.application.CategoryService;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.identity.application.OpaqueTokens;
import com.limingyuan.bookkeeping.identity.application.UserBootstrap;
import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import com.limingyuan.bookkeeping.transaction.application.TransactionService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class MonthlyBookkeepingFactsConsistencyTest {
    @Autowired JdbcMonthlyBookkeepingFactsReader reader;
    @Autowired MonthlyAnalysisFactsService facts;
    @Autowired AccountService accounts;
    @Autowired CategoryService categories;
    @Autowired TransactionService transactions;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcClient jdbc;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired OpaqueTokens tokens;
    long userId;
    long accountId;
    long shopping;
    List<Long> fixtures = new ArrayList<>();

    @BeforeEach void prepare() {
        String name = "facts_snapshot_" + UUID.randomUUID();
        bootstrap.ensureUser(name, tokens.generate());
        userId = users.findByUsername(name).orElseThrow().getId();
        fixtures.add(userId);
        asUser(() -> accountId = accounts.initialize("100.00").id());
        jdbc.sql("UPDATE accounts SET created_time = '2020-01-01 00:00:00' WHERE id = :id").param("id", accountId).update();
        asUser(() -> {
            shopping = categories.list("EXPENSE").stream().filter(category -> category.name().equals("购物"))
                    .findFirst().orElseThrow().id();
        });
    }

    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
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

    @Test void readUsesItsOwnRepeatableReadSnapshot() {
        var template = reader.readTransaction();
        assertThat(template.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(template.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(template.isReadOnly()).isTrue();
        String isolation = template.execute(status -> jdbc.sql("SELECT @@transaction_isolation").query(String.class).single());
        assertThat(isolation).isEqualTo("REPEATABLE-READ");

        template.executeWithoutResult(status -> {
            long before = expenseCount();
            commitExpense();
            assertThat(expenseCount()).isEqualTo(before);
        });
        assertThat(expenseCount()).isEqualTo(1);
        var seen = asUser(() -> facts.build(accountId, "2026-09"));
        assertThat(seen.expenseTransactionCount()).isEqualTo(1);
        assertThat(seen.netExpense()).isEqualTo("1.00");
    }

    @Test void readDoesNotJoinAnOuterTransaction() {
        var outer = new TransactionTemplate(transactionManager);
        outer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(status -> {
            asUser(() -> transactions.create(accountId, UUID.randomUUID().toString(), "EXPENSE",
                    new TransactionService.Draft("9.00", "未提交", shopping, List.of(), "2026-09-16T08:00:00+08:00", null)));
            var seen = asUser(() -> facts.build(accountId, "2026-09"));
            assertThat(seen.expenseTransactionCount()).isZero();
            assertThat(seen.netExpense()).isEqualTo("0.00");
            status.setRollbackOnly();
        });
        assertThat(expenseCount()).isZero();
    }

    private void commitExpense() {
        var writer = new TransactionTemplate(transactionManager);
        writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writer.executeWithoutResult(status -> asUser(() -> transactions.create(accountId, UUID.randomUUID().toString(), "EXPENSE",
                new TransactionService.Draft("1.00", "已提交", shopping, List.of(), "2026-09-15T08:00:00+08:00", null))));
    }

    private long expenseCount() {
        return jdbc.sql("""
                SELECT COUNT(*) FROM transactions
                WHERE account_id = :accountId AND user_id = :userId AND type = 'EXPENSE'
                  AND business_time >= '2026-09-01 00:00:00' AND business_time < '2026-10-01 00:00:00'
                """).param("accountId", accountId).param("userId", userId).query(Long.class).single();
    }

    private <T> T asUser(java.util.function.Supplier<T> action) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new TrustedUserContext.Identity(userId, 0L), null, List.of()));
        try {
            return action.get();
        } finally {
            if (previous == null) SecurityContextHolder.clearContext();
            else SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }

    private void asUser(Runnable action) {
        asUser(() -> {
            action.run();
            return null;
        });
    }
}
