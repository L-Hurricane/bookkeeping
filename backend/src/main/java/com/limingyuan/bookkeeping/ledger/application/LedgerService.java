package com.limingyuan.bookkeeping.ledger.application;

import com.limingyuan.bookkeeping.account.application.AccountService;
import com.limingyuan.bookkeeping.account.application.CurrentBalance;
import com.limingyuan.bookkeeping.common.Money;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.math.BigDecimal;
import java.time.LocalDate;
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
    static final int PAGE_SIZE = 50;
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");
    private static final Summary NO_MONTH_SUMMARY = new Summary(0, "0.00", "0.00", "0.00");
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
    public record Ledger(long accountId, String month, String currentBalance, Summary summary, List<LedgerItem> items,
            String nextCursor) { }

    /** Month-complete read used by clients that do not declare paging. */
    public Ledger query(long accountId, String monthRaw) {
        return query(accountId, monthRaw, null, null, null, false);
    }

    public Ledger query(long accountId, String monthRaw, String fromRaw, String toRaw, String cursorRaw, boolean paged) {
        Window window = window(monthRaw, fromRaw, toRaw);
        AccountLedgerCursor cursor = AccountLedgerCursor.parse(cursorRaw);
        boolean legacyMonth = window.mode() == Mode.MONTH && !paged && cursor == null;
        return transaction.execute(status -> {
            accounts.requireOwned(accountId);
            if (cursor != null) cursor.requireBound(accountId, window.scope());
            String balance = Money.format(balances.of(accountId));
            Summary summary = window.mode() == Mode.MONTH ? summary(accountId, window.start(), window.end()) : NO_MONTH_SUMMARY;
            if (legacyMonth) {
                return new Ledger(accountId, window.label(), balance, summary, materialize(rows(accountId, window, null, null)), null);
            }
            List<Row> fetched = rows(accountId, window, cursor, PAGE_SIZE + 1);
            String next = null;
            if (fetched.size() > PAGE_SIZE) {
                Row last = fetched.get(PAGE_SIZE - 1);
                next = new AccountLedgerCursor(accountId, window.scope(), last.businessTime(), last.firstSaveOrder(),
                        last.firstSavedAt(), last.kind(), last.id()).encode();
                fetched = fetched.subList(0, PAGE_SIZE);
            }
            String month = window.mode() == Mode.MONTH ? window.label() : null;
            return new Ledger(accountId, month, balance, summary, materialize(fetched), next);
        });
    }

    private List<Row> rows(long accountId, Window window, AccountLedgerCursor cursor, Integer limit) {
        String txTime = window.start() == null ? "" : " AND t.business_time >= :start AND t.business_time < :end";
        String adjustmentTime = window.start() == null ? "" : " AND b.business_time >= :start AND b.business_time < :end";
        String fromTime = window.start() == null ? "" : " AND t.from_business_time >= :start AND t.from_business_time < :end";
        String toTime = window.start() == null ? "" : " AND t.to_business_time >= :start AND t.to_business_time < :end";
        var sql = new StringBuilder("""
                SELECT kind, id, transaction_type, amount, name, category_id, category_name, business_time,
                       first_save_order, first_saved_at_utc
                FROM (
                    SELECT 'TRANSACTION' AS kind, t.id, t.type AS transaction_type, t.amount, t.name,
                           t.category_id, c.name AS category_name, t.business_time, t.first_save_order,
                           t.first_saved_at_utc
                    FROM transactions t
                    LEFT JOIN categories c ON c.id = t.category_id
                    WHERE t.account_id = :accountId AND t.user_id = :userId%s
                    UNION ALL
                    SELECT 'BALANCE_ADJUSTMENT', b.id, NULL, b.adjustment_amount, NULL,
                           NULL, NULL, b.business_time, b.first_save_order, b.first_saved_at_utc
                    FROM balance_adjustments b
                    WHERE b.account_id = :accountId AND b.user_id = :userId%s
                    UNION ALL
                    SELECT 'TRANSFER', t.id, NULL,
                           CASE WHEN t.from_account_id = :accountId THEN -t.amount ELSE t.amount END,
                           NULL, NULL, NULL,
                           CASE WHEN t.from_account_id = :accountId THEN t.from_business_time ELSE t.to_business_time END,
                           t.first_save_order, t.first_saved_at_utc
                    FROM transfers t
                    WHERE t.user_id = :userId
                      AND (
                        (t.from_account_id = :accountId%s)
                        OR (t.to_account_id = :accountId%s)
                      )
                ) entries
                """.formatted(txTime, adjustmentTime, fromTime, toTime));
        if (cursor != null) {
            sql.append("""
                    WHERE business_time < :cursorTime
                       OR (business_time = :cursorTime AND first_save_order < :cursorOrder)
                       OR (business_time = :cursorTime AND first_save_order = :cursorOrder
                           AND first_saved_at_utc < :cursorSaved)
                       OR (business_time = :cursorTime AND first_save_order = :cursorOrder
                           AND first_saved_at_utc = :cursorSaved AND kind < :cursorKind)
                       OR (business_time = :cursorTime AND first_save_order = :cursorOrder
                           AND first_saved_at_utc = :cursorSaved AND kind = :cursorKind AND id < :cursorId)
                    """);
        }
        sql.append(" ORDER BY ").append(LedgerOrdering.LEDGER);
        if (limit != null) sql.append(" LIMIT ").append(limit);
        var query = jdbc.sql(sql.toString()).param("accountId", accountId).param("userId", context.userId());
        if (window.start() != null) query.param("start", window.start()).param("end", window.end());
        if (cursor != null) {
            query.param("cursorTime", cursor.businessTime()).param("cursorOrder", cursor.firstSaveOrder())
                    .param("cursorSaved", cursor.firstSavedAt()).param("cursorKind", cursor.kind()).param("cursorId", cursor.id());
        }
        return query.query((rs, row) -> new Row(rs.getString("kind"), rs.getLong("id"), rs.getString("transaction_type"),
                rs.getBigDecimal("amount"), rs.getString("name"),
                rs.getObject("category_id") == null ? null : rs.getLong("category_id"), rs.getString("category_name"),
                rs.getObject("business_time", LocalDateTime.class), rs.getLong("first_save_order"),
                rs.getObject("first_saved_at_utc", LocalDateTime.class))).list();
    }

    private List<LedgerItem> materialize(List<Row> rows) {
        var tags = tagsByTransaction(rows);
        var items = new ArrayList<LedgerItem>();
        for (var row : rows) {
            if ("BALANCE_ADJUSTMENT".equals(row.kind())) {
                items.add(new LedgerItem(row.kind(), row.id(), null, "余额调整", Money.format(row.amount()), null, null,
                        TIME.format(row.businessTime().atZone(BEIJING))));
            } else if ("TRANSFER".equals(row.kind())) {
                items.add(new LedgerItem(row.kind(), row.id(), null, "转账", Money.format(row.amount()), null, null,
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

    private Summary summary(long accountId, LocalDateTime start, LocalDateTime end) {
        var totals = jdbc.sql("""
                SELECT COUNT(*) AS transaction_count,
                       COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount ELSE 0 END), 0) AS net_income,
                       COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amount ELSE 0 END), 0) AS net_expense
                FROM transactions
                WHERE account_id = :accountId AND user_id = :userId
                  AND business_time >= :start AND business_time < :end
                """).param("accountId", accountId).param("userId", context.userId())
                .param("start", start).param("end", end)
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

    private static Window window(String monthRaw, String fromRaw, String toRaw) {
        if (monthRaw != null && monthRaw.isBlank()) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "月份必须为 YYYY-MM");
        }
        boolean month = monthRaw != null;
        boolean range = (fromRaw != null && !fromRaw.isBlank()) || (toRaw != null && !toRaw.isBlank());
        if (month && range) throw new ApiFailure(400, "VALIDATION_FAILED", "月份和起止日期只能选一种");
        if (!month && !range) return new Window(Mode.ALL, null, null, null, "ALL");
        if (month) {
            YearMonth parsed = month(monthRaw);
            return new Window(Mode.MONTH, parsed.atDay(1).atStartOfDay(), parsed.plusMonths(1).atDay(1).atStartOfDay(),
                    parsed.toString(), "MONTH:" + parsed);
        }
        if (fromRaw == null || toRaw == null || fromRaw.isBlank() || toRaw.isBlank()) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "起止日期需要同时填写");
        }
        try {
            LocalDate from = LocalDate.parse(fromRaw);
            LocalDate to = LocalDate.parse(toRaw);
            if (to.isBefore(from) || from.plusDays(3660).isBefore(to)) {
                throw new ApiFailure(400, "VALIDATION_FAILED", "日期范围无法使用");
            }
            return new Window(Mode.RANGE, from.atStartOfDay(), to.plusDays(1).atStartOfDay(), null,
                    "RANGE:" + from + ":" + to);
        } catch (DateTimeParseException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "日期范围无法使用");
        }
    }

    private static YearMonth month(String raw) {
        try { return YearMonth.parse(raw); }
        catch (DateTimeParseException | NullPointerException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "月份必须为 YYYY-MM");
        }
    }

    private enum Mode { ALL, MONTH, RANGE }

    private record Window(Mode mode, LocalDateTime start, LocalDateTime end, String label, String scope) { }

    private record Row(String kind, long id, String transactionType, BigDecimal amount, String name, Long categoryId,
            String categoryName, LocalDateTime businessTime, long firstSaveOrder, LocalDateTime firstSavedAt) { }
}
