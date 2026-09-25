package com.limingyuan.bookkeeping.ledger.application;

import com.limingyuan.bookkeeping.account.application.AccountService;
import com.limingyuan.bookkeeping.account.application.CurrentBalance;
import com.limingyuan.bookkeeping.common.Money;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LedgerService {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");
    private final JdbcClient jdbc;
    private final AccountService accounts;
    private final CurrentBalance balances;
    private final TrustedUserContext context;
    private final TransactionTemplate transaction;

    public LedgerService(JdbcClient jdbc, AccountService accounts, CurrentBalance balances, TrustedUserContext context,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.balances = balances;
        this.context = context;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record CategoryRef(long id, String name) { }
    public record TagRef(long id, String name) { }
    public record LedgerItem(String kind, long id, String transactionType, String displayName, String amount,
            CategoryRef category, List<TagRef> tags, String businessTime) { }
    public record Summary(int transactionCount, String netIncome, String netExpense, String surplus) { }
    public record Ledger(long accountId, String month, String currentBalance, Summary summary, List<LedgerItem> items) { }

    public Ledger query(long accountId, String monthRaw) {
        var month = month(monthRaw);
        return transaction.execute(status -> {
            accounts.requireOwned(accountId);
            return new Ledger(accountId, month.toString(), Money.format(balances.of(accountId)), summary(accountId, month),
                    items(accountId, month));
        });
    }

    private List<LedgerItem> items(long accountId, YearMonth month) {
        var rows = jdbc.sql("""
                SELECT kind, id, transaction_type, amount, name, category_id, category_name, business_time, first_save_order
                FROM (
                    SELECT 'TRANSACTION' AS kind, t.id, t.type AS transaction_type, t.amount, t.name,
                           t.category_id, c.name AS category_name, t.business_time, t.first_save_order
                    FROM transactions t
                    LEFT JOIN categories c ON c.id = t.category_id
                    WHERE t.account_id = :accountId AND t.user_id = :userId
                      AND t.business_time >= :start AND t.business_time < :end
                    UNION ALL
                    SELECT 'BALANCE_ADJUSTMENT', b.id, NULL, b.adjustment_amount, NULL,
                           NULL, NULL, b.business_time, b.first_save_order
                    FROM balance_adjustments b
                    WHERE b.account_id = :accountId AND b.user_id = :userId
                      AND b.business_time >= :start AND b.business_time < :end
                ) entries
                ORDER BY business_time DESC, first_save_order DESC
                """).param("accountId", accountId).param("userId", context.userId())
                .param("start", month.atDay(1).atStartOfDay()).param("end", month.plusMonths(1).atDay(1).atStartOfDay())
                .query((rs, row) -> new Row(rs.getString("kind"), rs.getLong("id"), rs.getString("transaction_type"),
                        rs.getBigDecimal("amount"), rs.getString("name"),
                        rs.getObject("category_id") == null ? null : rs.getLong("category_id"), rs.getString("category_name"),
                        rs.getObject("business_time", LocalDateTime.class))).list();
        var tags = tagsByTransaction(rows);
        var items = new ArrayList<LedgerItem>();
        for (var row : rows) {
            if ("BALANCE_ADJUSTMENT".equals(row.kind())) {
                items.add(new LedgerItem(row.kind(), row.id(), null, "余额调整", Money.format(row.amount()), null, null,
                        TIME.format(row.businessTime().atZone(BEIJING))));
            } else {
                items.add(new LedgerItem(row.kind(), row.id(), row.transactionType(), displayName(row.name(), row.categoryName()),
                        Money.format(row.amount()),
                        row.categoryId() == null ? null : new CategoryRef(row.categoryId(), row.categoryName()),
                        tags.getOrDefault(row.id(), List.of()), TIME.format(row.businessTime().atZone(BEIJING))));
            }
        }
        return items;
    }

    private LinkedHashMap<Long, List<TagRef>> tagsByTransaction(List<Row> rows) {
        var ids = rows.stream().filter(row -> "TRANSACTION".equals(row.kind())).map(Row::id).toList();
        var grouped = new LinkedHashMap<Long, List<TagRef>>();
        if (ids.isEmpty()) return grouped;
        jdbc.sql("""
                SELECT tt.transaction_id, g.id, g.name
                FROM transaction_tags tt
                JOIN tags g ON g.id = tt.tag_id
                WHERE tt.transaction_id IN (:ids)
                ORDER BY tt.transaction_id, g.id
                """).param("ids", ids).query((rs, row) -> {
            grouped.computeIfAbsent(rs.getLong("transaction_id"), key -> new ArrayList<>())
                    .add(new TagRef(rs.getLong("id"), rs.getString("name")));
            return null;
        }).list();
        return grouped;
    }

    private Summary summary(long accountId, YearMonth month) {
        var totals = jdbc.sql("""
                SELECT COUNT(*) AS transaction_count,
                       COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount ELSE 0 END), 0) AS net_income,
                       COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amount ELSE 0 END), 0) AS net_expense
                FROM transactions
                WHERE account_id = :accountId AND user_id = :userId
                  AND business_time >= :start AND business_time < :end
                """).param("accountId", accountId).param("userId", context.userId())
                .param("start", month.atDay(1).atStartOfDay()).param("end", month.plusMonths(1).atDay(1).atStartOfDay())
                .query((rs, row) -> new BigDecimal[] { BigDecimal.valueOf(rs.getLong("transaction_count")),
                        rs.getBigDecimal("net_income"), rs.getBigDecimal("net_expense") }).single();
        var income = totals[1];
        var expense = totals[2];
        return new Summary(totals[0].intValue(), Money.format(income), Money.format(expense), Money.format(income.subtract(expense)));
    }

    private static String displayName(String name, String categoryName) {
        if (name != null && !name.isBlank()) return name;
        return categoryName == null || categoryName.isBlank() ? null : categoryName;
    }

    private static YearMonth month(String raw) {
        try { return YearMonth.parse(raw); }
        catch (DateTimeParseException | NullPointerException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "月份必须为 YYYY-MM");
        }
    }

    private record Row(String kind, long id, String transactionType, BigDecimal amount, String name, Long categoryId,
            String categoryName, LocalDateTime businessTime) { }
}
