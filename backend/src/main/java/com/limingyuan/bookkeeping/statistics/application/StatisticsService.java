package com.limingyuan.bookkeeping.statistics.application;

import com.limingyuan.bookkeeping.account.application.AccountService;
import com.limingyuan.bookkeeping.common.Money;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class StatisticsService {
    private final JdbcClient jdbc;
    private final AccountService accounts;
    private final TrustedUserContext context;
    private final TransactionTemplate transaction;

    public StatisticsService(JdbcClient jdbc, AccountService accounts, TrustedUserContext context,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.context = context;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record MonthlySummary(String month, int transactionCount, String netIncome, String netExpense, String surplus) { }
    public record CategoryChild(long categoryId, String categoryName, int transactionCount, String amount) { }
    public record CategoryItem(String kind, Long categoryId, String categoryName, int transactionCount, String amount,
            List<CategoryChild> children) { }
    public record CategoryStatistics(String month, String type, List<CategoryItem> items) { }
    public record TagItem(Long tagId, String tagName, int transactionCount, String amount) { }
    public record TagStatistics(String month, String type, boolean overlap, List<TagItem> items) { }

    public MonthlySummary monthly(long accountId, String monthRaw) {
        var month = month(monthRaw);
        return transaction.execute(status -> {
            accounts.requireOwned(accountId);
            var totals = totals(accountId, month);
            return new MonthlySummary(month.toString(), totals.count(), Money.format(totals.income()), Money.format(totals.expense()),
                    Money.format(totals.income().subtract(totals.expense())));
        });
    }

    public CategoryStatistics categories(long accountId, String monthRaw, String typeRaw) {
        var month = month(monthRaw);
        var type = type(typeRaw);
        return transaction.execute(status -> {
            accounts.requireOwned(accountId);
            return new CategoryStatistics(month.toString(), type, categoryItems(accountId, month, type));
        });
    }

    public TagStatistics tags(long accountId, String monthRaw, String typeRaw) {
        var month = month(monthRaw);
        var type = type(typeRaw);
        return transaction.execute(status -> {
            accounts.requireOwned(accountId);
            return new TagStatistics(month.toString(), type, true, tagItems(accountId, month, type));
        });
    }

    List<CategoryItem> categoryItems(long accountId, YearMonth month, String type) {
        var amounts = new LinkedHashMap<Long, Aggregate>();
        var uncategorized = new Aggregate[] { null };
        jdbc.sql("""
                SELECT category_id, COUNT(*) AS transaction_count, COALESCE(SUM(amount), 0) AS amount
                FROM transactions
                WHERE account_id = :accountId AND user_id = :userId AND type = :type
                  AND business_time >= :start AND business_time < :end
                GROUP BY category_id
                """).param("accountId", accountId).param("userId", context.userId()).param("type", type)
                .param("start", month.atDay(1).atStartOfDay()).param("end", month.plusMonths(1).atDay(1).atStartOfDay())
                .query((rs, row) -> {
                    var aggregate = new Aggregate(rs.getInt("transaction_count"), rs.getBigDecimal("amount"));
                    Long categoryId = rs.getObject("category_id", Long.class);
                    if (categoryId == null) uncategorized[0] = aggregate;
                    else amounts.put(categoryId, aggregate);
                    return null;
                }).list();
        var nodes = jdbc.sql("""
                SELECT id, parent_id, name FROM categories
                WHERE user_id = :userId AND type = :type
                ORDER BY id
                """).param("userId", context.userId()).param("type", type)
                .query((rs, row) -> new Node(rs.getLong("id"), rs.getObject("parent_id") == null ? null : rs.getLong("parent_id"),
                        rs.getString("name"))).list();
        var children = new LinkedHashMap<Long, List<Node>>();
        var roots = new ArrayList<Node>();
        for (var node : nodes) {
            if (node.parentId() == null) roots.add(node);
            else children.computeIfAbsent(node.parentId(), key -> new ArrayList<>()).add(node);
        }
        var items = new ArrayList<CategoryItem>();
        for (var root : roots) {
            var kids = children.getOrDefault(root.id(), List.of());
            if (kids.isEmpty()) {
                var own = amounts.get(root.id());
                if (own != null) items.add(new CategoryItem("CATEGORY", root.id(), root.name(), own.count(), Money.format(own.amount()), null));
            } else {
                var shown = new ArrayList<CategoryChild>();
                int count = 0;
                var amount = BigDecimal.ZERO.setScale(2);
                for (var child : kids) {
                    var own = amounts.get(child.id());
                    if (own == null) continue;
                    shown.add(new CategoryChild(child.id(), child.name(), own.count(), Money.format(own.amount())));
                    count += own.count();
                    amount = amount.add(own.amount());
                }
                if (!shown.isEmpty()) items.add(new CategoryItem("CATEGORY", root.id(), root.name(), count, Money.format(amount), shown));
            }
        }
        if (uncategorized[0] != null) {
            items.add(new CategoryItem("UNCATEGORIZED", null, "未分类", uncategorized[0].count(),
                    Money.format(uncategorized[0].amount()), List.of()));
        }
        return items;
    }

    private List<TagItem> tagItems(long accountId, YearMonth month, String type) {
        var items = new ArrayList<TagItem>();
        jdbc.sql("""
                SELECT g.id, g.name, COUNT(*) AS transaction_count, COALESCE(SUM(t.amount), 0) AS amount
                FROM transaction_tags tt
                JOIN tags g ON g.id = tt.tag_id AND g.user_id = :userId AND g.type = :type
                JOIN transactions t ON t.id = tt.transaction_id
                WHERE t.account_id = :accountId AND t.user_id = :userId AND t.type = :type
                  AND t.business_time >= :start AND t.business_time < :end
                GROUP BY g.id, g.name
                ORDER BY g.id
                """).param("accountId", accountId).param("userId", context.userId()).param("type", type)
                .param("start", month.atDay(1).atStartOfDay()).param("end", month.plusMonths(1).atDay(1).atStartOfDay())
                .query((rs, row) -> items.add(new TagItem(rs.getLong("id"), rs.getString("name"), rs.getInt("transaction_count"),
                        Money.format(rs.getBigDecimal("amount"))))).list();
        var untagged = jdbc.sql("""
                SELECT COUNT(*) AS transaction_count, COALESCE(SUM(amount), 0) AS amount
                FROM transactions t
                WHERE t.account_id = :accountId AND t.user_id = :userId AND t.type = :type
                  AND t.business_time >= :start AND t.business_time < :end
                  AND NOT EXISTS (SELECT 1 FROM transaction_tags tt WHERE tt.transaction_id = t.id)
                """).param("accountId", accountId).param("userId", context.userId()).param("type", type)
                .param("start", month.atDay(1).atStartOfDay()).param("end", month.plusMonths(1).atDay(1).atStartOfDay())
                .query((rs, row) -> new Aggregate(rs.getInt("transaction_count"), rs.getBigDecimal("amount"))).single();
        if (untagged.count() > 0) items.add(new TagItem(null, "无标签", untagged.count(), Money.format(untagged.amount())));
        return items;
    }

    private Totals totals(long accountId, YearMonth month) {
        return jdbc.sql("""
                SELECT COUNT(*) AS transaction_count,
                       COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount ELSE 0 END), 0) AS net_income,
                       COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amount ELSE 0 END), 0) AS net_expense
                FROM transactions
                WHERE account_id = :accountId AND user_id = :userId
                  AND business_time >= :start AND business_time < :end
                """).param("accountId", accountId).param("userId", context.userId())
                .param("start", month.atDay(1).atStartOfDay()).param("end", month.plusMonths(1).atDay(1).atStartOfDay())
                .query((rs, row) -> new Totals(rs.getInt("transaction_count"), rs.getBigDecimal("net_income"), rs.getBigDecimal("net_expense")))
                .single();
    }

    record SignedTotals(BigDecimal income, BigDecimal expense, int incomeCount, int expenseCount) { }

    SignedTotals signedTotals(long accountId, YearMonth month) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount ELSE 0 END), 0) AS net_income,
                       COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amount ELSE 0 END), 0) AS net_expense,
                       COUNT(CASE WHEN type = 'INCOME' THEN 1 END) AS income_count,
                       COUNT(CASE WHEN type = 'EXPENSE' THEN 1 END) AS expense_count
                FROM transactions
                WHERE account_id = :accountId AND user_id = :userId
                  AND business_time >= :start AND business_time < :end
                """).param("accountId", accountId).param("userId", context.userId())
                .param("start", month.atDay(1).atStartOfDay()).param("end", month.plusMonths(1).atDay(1).atStartOfDay())
                .query((rs, row) -> new SignedTotals(rs.getBigDecimal("net_income"), rs.getBigDecimal("net_expense"),
                        rs.getInt("income_count"), rs.getInt("expense_count")))
                .single();
    }

    static YearMonth month(String raw) {
        try { return YearMonth.parse(raw); }
        catch (DateTimeParseException | NullPointerException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "月份必须为 YYYY-MM");
        }
    }

    private static String type(String raw) {
        if (!"INCOME".equals(raw) && !"EXPENSE".equals(raw)) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "交易类型必须为 INCOME 或 EXPENSE");
        }
        return raw;
    }

    private record Aggregate(int count, BigDecimal amount) { }
    private record Totals(int count, BigDecimal income, BigDecimal expense) { }
    private record Node(long id, Long parentId, String name) { }
}
