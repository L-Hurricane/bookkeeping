package com.limingyuan.bookkeeping.statistics.application;

import com.limingyuan.bookkeeping.account.application.AccountService;
import com.limingyuan.bookkeeping.common.Money;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.ledger.application.LedgerOrdering;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFacts.CategorySlice;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFacts.KeyExpenseSlice;
import com.limingyuan.bookkeeping.statistics.application.MonthlyBookkeepingFacts.UncategorizedSlice;
import com.limingyuan.bookkeeping.statistics.application.StatisticsService.CategoryChild;
import com.limingyuan.bookkeeping.statistics.application.StatisticsService.CategoryItem;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class JdbcMonthlyBookkeepingFactsReader implements MonthlyBookkeepingFactsReader {
    private static final DateTimeFormatter BUSINESS_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneOffset BEIJING = ZoneOffset.ofHours(8);
    private static final UncategorizedSlice NONE = new UncategorizedSlice(0, Money.format(BigDecimal.ZERO.setScale(2)));

    private final JdbcClient jdbc;
    private final StatisticsService statistics;
    private final AccountService accounts;
    private final TrustedUserContext context;
    private final TransactionTemplate transaction;

    JdbcMonthlyBookkeepingFactsReader(JdbcClient jdbc, StatisticsService statistics, AccountService accounts,
            TrustedUserContext context, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.statistics = statistics;
        this.accounts = accounts;
        this.context = context;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transaction.setReadOnly(true);
    }

    /** The single read transaction used by {@link #read}. Tests use this to observe its snapshot. */
    TransactionTemplate readTransaction() {
        return transaction;
    }

    @Override
    public MonthlyBookkeepingFacts read(long accountId, String yearMonth) {
        YearMonth month = StatisticsService.month(yearMonth);
        return transaction.execute(status -> load(accountId, month));
    }

    private MonthlyBookkeepingFacts load(long accountId, YearMonth month) {
        accounts.requireOwned(accountId);
        var totals = statistics.signedTotals(accountId, month);
        var income = statistics.categoryItems(accountId, month, "INCOME");
        var expense = statistics.categoryItems(accountId, month, "EXPENSE");
        return new MonthlyBookkeepingFacts(accountId, month.toString(), Money.format(totals.income()), Money.format(totals.expense()),
                Money.format(totals.income().subtract(totals.expense())), totals.incomeCount(), totals.expenseCount(),
                categories(income), categories(expense), uncategorized(income), uncategorized(expense),
                keyExpenses(accountId, month));
    }

    private List<KeyExpenseSlice> keyExpenses(long accountId, YearMonth month) {
        var rows = new ArrayList<KeyExpenseSlice>();
        jdbc.sql("""
                SELECT t.type, t.amount, t.name, t.business_time, c.name AS category_name, p.name AS parent_name
                FROM transactions t
                LEFT JOIN categories c ON c.id = t.category_id AND c.user_id = t.user_id
                LEFT JOIN categories p ON p.id = c.parent_id AND p.user_id = t.user_id
                WHERE t.account_id = :accountId AND t.user_id = :userId
                  AND t.type = 'EXPENSE' AND t.amount <> 0
                  AND t.business_time >= :start AND t.business_time < :end
                """ + " ORDER BY ABS(t.amount) DESC, " + LedgerOrdering.KEY_EXPENSE_TIEBREAK + " LIMIT 5")
                .param("accountId", accountId).param("userId", context.userId())
                .param("start", month.atDay(1).atStartOfDay()).param("end", month.plusMonths(1).atDay(1).atStartOfDay())
                .query((rs, row) -> rows.add(new KeyExpenseSlice(rs.getString("type"),
                        categoryPath(rs.getString("parent_name"), rs.getString("category_name")),
                        text(rs.getString("name")), Money.format(rs.getBigDecimal("amount")),
                        businessTime(rs.getObject("business_time", LocalDateTime.class))))).list();
        return List.copyOf(rows);
    }

    private static List<CategorySlice> categories(List<CategoryItem> items) {
        var slices = new ArrayList<CategorySlice>();
        for (var item : items) {
            if (!"CATEGORY".equals(item.kind())) continue;
            slices.add(new CategorySlice(item.categoryId(), item.categoryName(), item.transactionCount(), item.amount(),
                    children(item.children())));
        }
        return List.copyOf(slices);
    }

    private static List<CategorySlice> children(List<CategoryChild> children) {
        if (children == null || children.isEmpty()) return List.of();
        var slices = new ArrayList<CategorySlice>();
        for (var child : children) {
            slices.add(new CategorySlice(child.categoryId(), child.categoryName(), child.transactionCount(), child.amount(), List.of()));
        }
        return List.copyOf(slices);
    }

    private static UncategorizedSlice uncategorized(List<CategoryItem> items) {
        for (var item : items) {
            if ("UNCATEGORIZED".equals(item.kind())) return new UncategorizedSlice(item.transactionCount(), item.amount());
        }
        return NONE;
    }

    private static String categoryPath(String parentName, String categoryName) {
        if (categoryName == null || categoryName.isBlank()) return null;
        if (parentName == null || parentName.isBlank()) return categoryName;
        return parentName + " > " + categoryName;
    }

    private static String businessTime(LocalDateTime value) {
        return OffsetDateTime.of(value, BEIJING).format(BUSINESS_TIME);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
