package com.limingyuan.bookkeeping.ledger.application;

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
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Read-only mixed activity across accounts. It does not attach a per-row balance. */
@Service
public class LedgerFeedService {
    private static final int PAGE_SIZE = 50;
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");

    private final JdbcClient jdbc;
    private final TrustedUserContext context;

    public LedgerFeedService(JdbcClient jdbc, TrustedUserContext context) {
        this.jdbc = jdbc;
        this.context = context;
    }

    public Page query(String monthRaw, String fromRaw, String toRaw, String nameRaw, String scopeRaw,
            String accountIdsRaw, String cursorRaw) {
        long userId = context.userId();
        Window window = window(monthRaw, fromRaw, toRaw);
        String keyword = nameRaw == null || nameRaw.isBlank() ? null : keyword(nameRaw);
        List<AccountRef> accounts = accounts(userId, scopeRaw, accountIdsRaw);
        LedgerFeedCursor cursor = LedgerFeedCursor.parse(cursorRaw);
        if (accounts.isEmpty()) return new Page(window.label(), null, List.of(), null);
        List<Long> ids = accounts.stream().map(AccountRef::id).toList();
        Summary summary = keyword == null ? summary(userId, ids, window) : null;
        List<Item> items = items(userId, ids, window, keyword, cursor);
        String next = null;
        if (items.size() > PAGE_SIZE) {
            Item last = items.get(PAGE_SIZE - 1);
            next = new LedgerFeedCursor(last.businessTime(), last.firstSaveOrder(), last.firstSavedAt(), last.kind(),
                    last.accountId(), last.id()).encode();
            items = items.subList(0, PAGE_SIZE);
        }
        return new Page(window.label(), summary, items.stream().map(Item::view).toList(), next);
    }

    private List<Item> items(long userId, List<Long> ids, Window window, String keyword, LedgerFeedCursor cursor) {
        String body = keyword == null ? mixed(window) : named(window);
        var sql = new StringBuilder("""
                SELECT feed.kind, feed.id, feed.transaction_type, feed.display_name, feed.category_name, feed.amount,
                       feed.business_time, feed.first_save_order, feed.first_saved_at_utc, feed.account_id,
                       a.name AS account_name, a.created_time
                FROM (
                """).append(body).append("""
                ) feed
                JOIN accounts a ON a.id = feed.account_id AND a.user_id = :userId
                """);
        if (cursor != null) {
            sql.append("""
                    WHERE feed.business_time < :cursorTime
                       OR (feed.business_time = :cursorTime AND feed.first_save_order < :cursorOrder)
                       OR (feed.business_time = :cursorTime AND feed.first_save_order = :cursorOrder
                           AND feed.first_saved_at_utc < :cursorSaved)
                       OR (feed.business_time = :cursorTime AND feed.first_save_order = :cursorOrder
                           AND feed.first_saved_at_utc = :cursorSaved AND feed.kind < :cursorKind)
                       OR (feed.business_time = :cursorTime AND feed.first_save_order = :cursorOrder
                           AND feed.first_saved_at_utc = :cursorSaved AND feed.kind = :cursorKind
                           AND feed.account_id < :cursorAccount)
                       OR (feed.business_time = :cursorTime AND feed.first_save_order = :cursorOrder
                           AND feed.first_saved_at_utc = :cursorSaved AND feed.kind = :cursorKind
                           AND feed.account_id = :cursorAccount AND feed.id < :cursorId)
                    """);
        }
        sql.append("""
                 ORDER BY feed.business_time DESC, feed.first_save_order DESC, feed.first_saved_at_utc DESC,
                          feed.kind DESC, feed.account_id DESC, feed.id DESC
                 LIMIT 
                """).append(PAGE_SIZE + 1);
        var query = jdbc.sql(sql.toString()).param("userId", userId).param("ids", ids);
        if (window.start() != null) query.param("start", window.start()).param("end", window.end());
        if (keyword != null) query.param("pattern", likeContains(keyword));
        if (cursor != null) {
            query.param("cursorTime", cursor.businessTime()).param("cursorOrder", cursor.firstSaveOrder())
                    .param("cursorSaved", cursor.firstSavedAt()).param("cursorKind", cursor.kind())
                    .param("cursorAccount", cursor.accountId()).param("cursorId", cursor.id());
        }
        return query.query((rs, number) -> new Item(rs.getString("kind"), rs.getLong("id"), rs.getString("transaction_type"),
                rs.getString("display_name"), rs.getString("category_name"), rs.getBigDecimal("amount"),
                rs.getObject("business_time", LocalDateTime.class), rs.getLong("first_save_order"),
                rs.getObject("first_saved_at_utc", LocalDateTime.class), rs.getLong("account_id"),
                rs.getString("account_name"), rs.getObject("created_time", LocalDateTime.class))).list();
    }

    private static String mixed(Window window) {
        String tx = window.start() == null ? "" : " AND t.business_time >= :start AND t.business_time < :end";
        String adj = window.start() == null ? "" : " AND b.business_time >= :start AND b.business_time < :end";
        String from = window.start() == null ? "" : " AND f.from_business_time >= :start AND f.from_business_time < :end";
        String to = window.start() == null ? "" : " AND n.to_business_time >= :start AND n.to_business_time < :end";
        return """
                SELECT 'TRANSACTION' AS kind, t.id, t.type AS transaction_type, t.name AS display_name, c.name AS category_name,
                       t.amount, t.business_time, t.first_save_order, t.first_saved_at_utc, t.account_id
                FROM transactions t
                LEFT JOIN categories c ON c.id = t.category_id AND c.user_id = :userId
                WHERE t.user_id = :userId AND t.account_id IN (:ids)%s
                UNION ALL
                SELECT 'BALANCE_ADJUSTMENT', b.id, NULL, NULL, NULL, b.adjustment_amount,
                       b.business_time, b.first_save_order, b.first_saved_at_utc, b.account_id
                FROM balance_adjustments b
                WHERE b.user_id = :userId AND b.account_id IN (:ids)%s
                UNION ALL
                SELECT 'TRANSFER', f.id, NULL, NULL, NULL, -f.amount,
                       f.from_business_time, f.first_save_order, f.first_saved_at_utc, f.from_account_id
                FROM transfers f
                WHERE f.user_id = :userId AND f.from_account_id IN (:ids)%s
                UNION ALL
                SELECT 'TRANSFER', n.id, NULL, NULL, NULL, n.amount,
                       n.to_business_time, n.first_save_order, n.first_saved_at_utc, n.to_account_id
                FROM transfers n
                WHERE n.user_id = :userId AND n.to_account_id IN (:ids)%s
                """.formatted(tx, adj, from, to);
    }

    private static String named(Window window) {
        String time = window.start() == null ? "" : " AND t.business_time >= :start AND t.business_time < :end";
        return """
                SELECT 'TRANSACTION' AS kind, t.id, t.type AS transaction_type, t.name AS display_name, c.name AS category_name,
                       t.amount, t.business_time, t.first_save_order, t.first_saved_at_utc, t.account_id
                FROM transactions t
                LEFT JOIN categories c ON c.id = t.category_id AND c.user_id = :userId
                WHERE t.user_id = :userId AND t.account_id IN (:ids)
                  AND t.name LIKE :pattern ESCAPE '!'%s
                """.formatted(time);
    }

    private Summary summary(long userId, List<Long> ids, Window window) {
        String time = window.start() == null ? "" : " AND business_time >= :start AND business_time < :end";
        var query = jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN type = 'INCOME' THEN amount ELSE 0 END), 0) AS net_income,
                       COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN amount ELSE 0 END), 0) AS net_expense
                FROM transactions
                WHERE user_id = :userId AND account_id IN (:ids)%s
                """.formatted(time)).param("userId", userId).param("ids", ids);
        if (window.start() != null) query.param("start", window.start()).param("end", window.end());
        return query.query((rs, number) -> {
            BigDecimal income = rs.getBigDecimal("net_income");
            BigDecimal expense = rs.getBigDecimal("net_expense");
            return new Summary(Money.format(income), Money.format(expense), Money.format(income.subtract(expense)));
        }).single();
    }

    private List<AccountRef> accounts(long userId, String scopeRaw, String accountIdsRaw) {
        String scope = scopeRaw == null || scopeRaw.isBlank() ? "ACTIVE" : scopeRaw;
        return switch (scope) {
            case "ACTIVE" -> listed(userId, "AND lifecycle = 'ACTIVE'", List.of());
            case "ALL" -> listed(userId, "", List.of());
            case "SELECTED" -> selected(userId, accountIdsRaw);
            default -> throw new ApiFailure(400, "VALIDATION_FAILED", "账户范围无法识别");
        };
    }

    private List<AccountRef> selected(long userId, String raw) {
        if (raw == null || raw.isBlank()) throw new ApiFailure(400, "VALIDATION_FAILED", "请选择账户");
        var ids = new LinkedHashSet<Long>();
        for (String part : raw.split(",")) {
            try {
                long id = Long.parseLong(part.trim());
                if (id < 1) throw new NumberFormatException();
                ids.add(id);
            } catch (NumberFormatException ex) {
                throw new ApiFailure(400, "VALIDATION_FAILED", "账户无法识别");
            }
        }
        List<AccountRef> found = listed(userId, "AND id IN (:selected)", List.copyOf(ids));
        if (found.size() != ids.size()) throw new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在");
        return found;
    }

    private List<AccountRef> listed(long userId, String extra, List<Long> selected) {
        var query = jdbc.sql("""
                SELECT id FROM accounts
                WHERE user_id = :userId %s
                ORDER BY id
                """.formatted(extra)).param("userId", userId);
        if (!selected.isEmpty()) query.param("selected", selected);
        return query.query((rs, number) -> new AccountRef(rs.getLong("id"))).list();
    }

    private static Window window(String monthRaw, String fromRaw, String toRaw) {
        boolean month = monthRaw != null && !monthRaw.isBlank();
        boolean range = (fromRaw != null && !fromRaw.isBlank()) || (toRaw != null && !toRaw.isBlank());
        if (month && range) throw new ApiFailure(400, "VALIDATION_FAILED", "月份和起止日期只能选一种");
        if (!month && !range) return new Window(null, null, null);
        if (month) {
            YearMonth parsed = month(monthRaw);
            return new Window(parsed.atDay(1).atStartOfDay(), parsed.plusMonths(1).atDay(1).atStartOfDay(), monthRaw);
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
            return new Window(from.atStartOfDay(), to.plusDays(1).atStartOfDay(), from + " 至 " + to);
        } catch (DateTimeParseException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "日期范围无法使用");
        }
    }

    private static YearMonth month(String raw) {
        try {
            return YearMonth.parse(raw);
        } catch (DateTimeParseException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "月份必须为 YYYY-MM");
        }
    }

    private static String keyword(String raw) {
        String trimmed = raw.trim();
        if (trimmed.length() > 255) throw new ApiFailure(400, "VALIDATION_FAILED", "名称最长 255 个字符");
        return trimmed;
    }

    private static String likeContains(String keyword) {
        var escaped = new StringBuilder(keyword.length() * 2 + 2);
        escaped.append('%');
        for (int index = 0; index < keyword.length(); index++) {
            char character = keyword.charAt(index);
            if (character == '!' || character == '%' || character == '_' || character == '\\') escaped.append('!');
            escaped.append(character);
        }
        escaped.append('%');
        return escaped.toString();
    }

    private record Window(LocalDateTime start, LocalDateTime end, String label) { }

    private record AccountRef(long id) { }

    private record Item(String kind, long id, String transactionType, String displayName, String categoryName,
            BigDecimal amount, LocalDateTime businessTime, long firstSaveOrder, LocalDateTime firstSavedAt,
            long accountId, String accountName, LocalDateTime accountCreatedTime) {
        View view() {
            String title = displayName != null && !displayName.isBlank() ? displayName
                    : "TRANSACTION".equals(kind) ? categoryName : "TRANSFER".equals(kind) ? "转账" : "余额调整";
            return new View(kind, id, transactionType, title, Money.format(amount),
                    TIME.format(businessTime.atZone(BEIJING)), accountId, accountName,
                    TIME.format(accountCreatedTime.atZone(BEIJING)));
        }
    }

    public record Summary(String netIncome, String netExpense, String surplus) { }

    public record View(String kind, long id, String transactionType, String displayName, String amount,
            String businessTime, long accountId, String accountName, String accountCreatedTime) { }

    public record Page(String label, Summary summary, List<View> items, String nextCursor) { }
}
