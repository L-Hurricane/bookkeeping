package com.limingyuan.bookkeeping.account.application;

import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Query-time balance. Income amounts add, expense amounts subtract, adjustments add as stored. */
@Component
public class CurrentBalance {
    private final JdbcClient jdbc;
    private final TrustedUserContext context;

    public CurrentBalance(JdbcClient jdbc, TrustedUserContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    public BigDecimal of(long accountId) {
        return jdbc.sql("""
                SELECT opening_balance
                    + COALESCE((SELECT SUM(CASE WHEN type = 'INCOME' THEN amount ELSE -amount END)
                        FROM transactions WHERE account_id = :accountId AND user_id = :userId), 0)
                    + COALESCE((SELECT SUM(adjustment_amount)
                        FROM balance_adjustments WHERE account_id = :accountId AND user_id = :userId), 0)
                    + COALESCE((SELECT SUM(amount)
                        FROM transfers WHERE to_account_id = :accountId AND user_id = :userId), 0)
                    - COALESCE((SELECT SUM(amount)
                        FROM transfers WHERE from_account_id = :accountId AND user_id = :userId), 0)
                FROM accounts
                WHERE id = :accountId AND user_id = :userId
                """).param("accountId", accountId).param("userId", context.userId()).query(BigDecimal.class).single();
    }

    /**
     * Same formula as {@link #of(long)}, read with {@code FOR UPDATE} after the caller holds the
     * account row. A plain SUM stays on the transaction's first REPEATABLE READ snapshot.
     */
    public BigDecimal ofLocked(long accountId) {
        long userId = context.userId();
        BigDecimal opening = jdbc.sql("""
                SELECT opening_balance FROM accounts
                WHERE id = :accountId AND user_id = :userId
                FOR UPDATE
                """).param("accountId", accountId).param("userId", userId).query(BigDecimal.class).single();
        BigDecimal transactions = sumLocked("""
                SELECT COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount ELSE -amount END), 0)
                FROM transactions
                WHERE account_id = :accountId AND user_id = :userId
                FOR UPDATE
                """, accountId, userId);
        BigDecimal adjustments = sumLocked("""
                SELECT COALESCE(SUM(adjustment_amount), 0)
                FROM balance_adjustments
                WHERE account_id = :accountId AND user_id = :userId
                FOR UPDATE
                """, accountId, userId);
        BigDecimal inbound = sumLocked("""
                SELECT COALESCE(SUM(amount), 0)
                FROM transfers
                WHERE to_account_id = :accountId AND user_id = :userId
                FOR UPDATE
                """, accountId, userId);
        BigDecimal outbound = sumLocked("""
                SELECT COALESCE(SUM(amount), 0)
                FROM transfers
                WHERE from_account_id = :accountId AND user_id = :userId
                FOR UPDATE
                """, accountId, userId);
        return opening.add(transactions).add(adjustments).add(inbound).subtract(outbound);
    }

    private BigDecimal sumLocked(String sql, long accountId, long userId) {
        return jdbc.sql(sql).param("accountId", accountId).param("userId", userId).query(BigDecimal.class).single();
    }

    /**
     * Balance using each record's own business time, inclusive of {@code cutoff}.
     * A transfer leaves on {@code from_business_time} and arrives on {@code to_business_time}.
     * This is not an HTTP statistics endpoint.
     */
    public BigDecimal asOf(long accountId, LocalDateTime cutoff) {
        return jdbc.sql("""
                SELECT opening_balance
                    + COALESCE((SELECT SUM(CASE WHEN type = 'INCOME' THEN amount ELSE -amount END)
                        FROM transactions WHERE account_id = :accountId AND user_id = :userId
                          AND business_time <= :cutoff), 0)
                    + COALESCE((SELECT SUM(adjustment_amount)
                        FROM balance_adjustments WHERE account_id = :accountId AND user_id = :userId
                          AND business_time <= :cutoff), 0)
                    + COALESCE((SELECT SUM(amount)
                        FROM transfers WHERE to_account_id = :accountId AND user_id = :userId
                          AND to_business_time <= :cutoff), 0)
                    - COALESCE((SELECT SUM(amount)
                        FROM transfers WHERE from_account_id = :accountId AND user_id = :userId
                          AND from_business_time <= :cutoff), 0)
                FROM accounts
                WHERE id = :accountId AND user_id = :userId
                """).param("accountId", accountId).param("userId", context.userId()).param("cutoff", cutoff)
                .query(BigDecimal.class).single();
    }
}
