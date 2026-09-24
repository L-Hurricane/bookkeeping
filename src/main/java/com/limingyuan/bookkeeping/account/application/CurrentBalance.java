package com.limingyuan.bookkeeping.account.application;

import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.math.BigDecimal;
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
                FROM accounts
                WHERE id = :accountId AND user_id = :userId
                """).param("accountId", accountId).param("userId", context.userId()).query(BigDecimal.class).single();
    }
}
