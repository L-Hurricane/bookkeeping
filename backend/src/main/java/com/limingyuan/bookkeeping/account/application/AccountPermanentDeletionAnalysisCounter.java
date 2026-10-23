package com.limingyuan.bookkeeping.account.application;

import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class AccountPermanentDeletionAnalysisCounter {
    private final JdbcClient jdbc;
    private final TrustedUserContext context;

    public AccountPermanentDeletionAnalysisCounter(JdbcClient jdbc, TrustedUserContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    public int countForAccount(long accountId) {
        long userId = context.userId();
        Integer total = jdbc.sql("""
                SELECT
                    (SELECT COUNT(*) FROM analysis_snapshots WHERE user_id = :userId AND account_id = :accountId)
                  + (SELECT COUNT(*) FROM analysis_generations WHERE user_id = :userId AND account_id = :accountId)
                """).param("userId", userId).param("accountId", accountId).query(Integer.class).single();
        return total == null ? 0 : total;
    }
}
