package com.limingyuan.bookkeeping.statistics.application;

import com.limingyuan.bookkeeping.account.application.CurrentBalance;
import com.limingyuan.bookkeeping.common.Money;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerStatisticsService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final JdbcClient jdbc;
    private final CurrentBalance balances;
    private final TrustedUserContext context;

    public LedgerStatisticsService(JdbcClient jdbc, CurrentBalance balances, TrustedUserContext context) {
        this.jdbc = jdbc;
        this.balances = balances;
        this.context = context;
    }

    public record AccountRef(long id, String name, String lifecycle) { }
    public record Point(String date, String amount) { }
    public record NetWorth(String opening, String closing, String change, List<Point> points) { }
    public record Slice(int transactionCount, String amount, String share) { }
    public record CategoryNode(long id, String name, int transactionCount, String amount, String share, List<CategoryNode> children) { }
    public record TagNode(long id, String name, int transactionCount, String amount, String share, List<TagNode> children) { }
    public record Flow(String net, List<CategoryNode> categories, List<TagNode> tags, Slice uncategorized, Slice untagged) { }
    public record Report(String preset, String from, String to, String accountScope, List<AccountRef> accounts,
            NetWorth netWorth, Flow income, Flow expense) { }
    public record CategoryRef(long id, String name) { }
    public record TagRef(long id, String name) { }
    public record TransactionItem(long id, long accountId, String accountCreatedTime, String type, String amount,
            String displayName, CategoryRef category, List<TagRef> tags, String businessTime) { }
    public record TagAnalysis(String scope, String amount, int transactionCount, String scopeNet, String coverage,
            List<CategoryNode> categories, Slice uncategorized, List<TransactionItem> items) { }

    @Transactional(readOnly = true)
    public Report report(String preset, String from, String to, String accountScope, String accountIds) {
        StatisticsPeriod.Range range = StatisticsPeriod.resolve(preset, from, to, Clock.systemUTC());
        Selection selection = selection(accountScope, accountIds);
        if (selection.accounts().isEmpty()) return report(range, selection.scope(), List.of(), List.of());
        return report(range, selection.scope(), selection.accounts(), load(selection.accounts(), range, null));
    }

    @Transactional(readOnly = true)
    public List<TransactionItem> transactions(String preset, String from, String to, String accountScope, String accountIds,
            String type, Long categoryId, Boolean uncategorized, Long tagId, String tagIdList, String tagMode, Boolean untagged) {
        String flow = flowType(type);
        Drill drill = drill(categoryId, uncategorized, tagId, tagIdList, tagMode, untagged);
        StatisticsPeriod.Range range = StatisticsPeriod.resolve(preset, from, to, Clock.systemUTC());
        Selection selection = selection(accountScope, accountIds);
        if (selection.accounts().isEmpty()) return List.of();
        List<Row> rows = load(selection.accounts(), range, flow);
        Map<Long, List<TagRef>> tags = tags(rows);
        List<CategoryRollup.Node> categories = categories(flow);
        Set<Long> knownCategories = categories.stream().map(CategoryRollup.Node::id).collect(Collectors.toSet());
        Set<Long> categoryIds = drill.categoryIds() == null ? null : categoryFilter(categories, drill);
        Set<Long> tagIds = drill.tagIds() == null ? null : tagFilter(drill);
        List<TransactionItem> items = new ArrayList<>();
        for (Row row : rows) {
            List<TagRef> rowTags = tags.getOrDefault(row.id(), List.of());
            if (!matches(row, rowTags, knownCategories, categoryIds, tagIds, drill)) continue;
            items.add(item(row, flow, categories, rowTags));
        }
        return List.copyOf(items);
    }

    @Transactional(readOnly = true)
    public TagAnalysis tagAnalysis(String preset, String from, String to, String accountScope, String accountIds,
            String type, long tagId, String tagMode, String tagAnd) {
        String flow = flowType(type);
        if (tagId <= 0) throw new ApiFailure(400, "VALIDATION_FAILED", "标签编号不正确");
        boolean intersection = tagAnd != null && !tagAnd.isBlank();
        String mode = tagMode == null || tagMode.isBlank() ? "TREE" : tagMode.trim();
        if (!mode.equals("TREE") && !mode.equals("DIRECT")) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "标签明细必须为标签树或直接关联");
        }
        if (intersection && !mode.equals("TREE")) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "同时包含只按标签及下级计算");
        }
        List<Long> extras = intersection ? parseIds(tagAnd) : List.of();
        if (extras.contains(tagId)) throw new ApiFailure(400, "VALIDATION_FAILED", "附加标签不能与当前标签重复");
        String scope = intersection ? "AND" : mode;
        StatisticsPeriod.Range range = StatisticsPeriod.resolve(preset, from, to, Clock.systemUTC());
        Selection selection = selection(accountScope, accountIds);
        List<TagRollup.Node> nodes = tagNodes();
        List<Set<Long>> subtrees = mode.equals("DIRECT") ? List.of() : subtrees(nodes, tagId, extras);
        if (mode.equals("DIRECT") && nodes.stream().noneMatch(tag -> tag.id() == tagId)) {
            throw new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在");
        }
        if (selection.accounts().isEmpty()) {
            return new TagAnalysis(scope, "0.00", 0, "0.00", null, List.of(), null, List.of());
        }
        List<Row> rows = load(selection.accounts(), range, flow);
        Map<Long, List<TagRef>> tags = tags(rows);
        List<CategoryRollup.Node> categoryTree = categories(flow);
        List<TransactionItem> items = new ArrayList<>();
        List<CategoryRollup.Fact> facts = new ArrayList<>();
        BigDecimal scopeNet = ZERO;
        for (Row row : rows) {
            scopeNet = scopeNet.add(signed(row));
            List<TagRef> rowTags = tags.getOrDefault(row.id(), List.of());
            if (!matchesTag(rowTags, tagId, mode, subtrees)) continue;
            facts.add(new CategoryRollup.Fact(row.categoryId(), signed(row)));
            items.add(item(row, flow, categoryTree, rowTags));
        }
        CategoryRollup.Tree tree = CategoryRollup.rollup(categoryTree, facts);
        return new TagAnalysis(scope, money(tree.net()), items.size(), money(scopeNet),
                StatisticsShare.of(tree.net(), scopeNet), categoryNodes(tree.roots(), tree.net()),
                slice(tree.uncategorized(), tree.net()), List.copyOf(items));
    }

    private List<Set<Long>> subtrees(List<TagRollup.Node> nodes, long tagId, List<Long> extras) {
        List<Long> roots = new ArrayList<>();
        roots.add(tagId);
        roots.addAll(extras);
        List<Set<Long>> subtrees = new ArrayList<>();
        for (long id : roots) {
            if (nodes.stream().noneMatch(tag -> tag.id() == id)) {
                throw new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在");
            }
            subtrees.add(TagRollup.subtreeIds(nodes, id));
        }
        return subtrees;
    }

    private static boolean matchesTag(List<TagRef> rowTags, long tagId, String mode, List<Set<Long>> subtrees) {
        if (mode.equals("DIRECT")) return rowTags.stream().anyMatch(tag -> tag.id() == tagId);
        Set<Long> explicit = rowTags.stream().map(TagRef::id).collect(Collectors.toSet());
        return TagIntersection.matchesAll(explicit, subtrees);
    }

    private TransactionItem item(Row row, String flow, List<CategoryRollup.Node> categories, List<TagRef> rowTags) {
        return new TransactionItem(row.id(), row.accountId(), beijing(row.accountCreatedTime()), flow,
                money(signed(row)), blankToNull(row.name()), categoryRef(categories, row.categoryId()), rowTags,
                beijing(row.businessTime()));
    }

    private Report report(StatisticsPeriod.Range range, String scope, List<AccountRow> accounts, List<Row> rows) {
        List<Row> incomeRows = rows.stream().filter(row -> "INCOME".equals(row.type())).toList();
        List<Row> expenseRows = rows.stream().filter(row -> "EXPENSE".equals(row.type())).toList();
        return new Report(range.preset(), range.from().toString(), range.to().toString(), scope, refs(accounts),
                netWorth(accounts, range), flow("INCOME", incomeRows), flow("EXPENSE", expenseRows));
    }

    private NetWorth netWorth(List<AccountRow> accounts, StatisticsPeriod.Range range) {
        BigDecimal opening = sum(accounts, range.openingCutoff());
        BigDecimal closing = sum(accounts, range.endInclusive());
        List<LocalDate> dates = StatisticsPeriod.samples(range.from(), range.to());
        List<LocalDateTime> cutoffs = new ArrayList<>();
        for (LocalDate date : dates) {
            cutoffs.add(date.equals(range.to()) ? range.endInclusive() : date.atTime(23, 59, 59));
        }
        List<BigDecimal> values = dailyValues(accounts, range.endInclusive(), cutoffs);
        List<Point> points = new ArrayList<>();
        for (int index = 0; index < dates.size(); index += 1) {
            points.add(new Point(dates.get(index).toString(), money(values.get(index))));
        }
        return new NetWorth(money(opening), money(closing), money(closing.subtract(opening)), List.copyOf(points));
    }

    private List<BigDecimal> dailyValues(List<AccountRow> accounts, LocalDateTime end, List<LocalDateTime> cutoffs) {
        if (accounts.isEmpty()) return cutoffs.stream().map(ignored -> ZERO).toList();
        List<Long> ids = accounts.stream().map(AccountRow::id).toList();
        BigDecimal openingBalances = jdbc.sql("""
                SELECT COALESCE(SUM(opening_balance), 0)
                FROM accounts WHERE user_id = :userId AND id IN (:accountIds)
                """).param("userId", context.userId()).param("accountIds", ids)
                .query(BigDecimal.class).single();
        List<NetWorthSeries.Movement> movements = jdbc.sql("""
                SELECT business_time AS event_time,
                       CASE WHEN type = 'INCOME' THEN amount ELSE -amount END AS delta
                FROM transactions
                WHERE user_id = :userId AND account_id IN (:accountIds) AND business_time <= :end
                UNION ALL
                SELECT business_time, adjustment_amount
                FROM balance_adjustments
                WHERE user_id = :userId AND account_id IN (:accountIds) AND business_time <= :end
                UNION ALL
                SELECT to_business_time, amount
                FROM transfers
                WHERE user_id = :userId AND to_account_id IN (:accountIds) AND to_business_time <= :end
                UNION ALL
                SELECT from_business_time, -amount
                FROM transfers
                WHERE user_id = :userId AND from_account_id IN (:accountIds) AND from_business_time <= :end
                """).param("userId", context.userId()).param("accountIds", ids).param("end", end)
                .query((rs, number) -> new NetWorthSeries.Movement(rs.getTimestamp("event_time").toLocalDateTime(), rs.getBigDecimal("delta")))
                .list();
        movements.sort(Comparator.comparing(NetWorthSeries.Movement::time));
        return NetWorthSeries.atCutoffs(openingBalances, movements, cutoffs);
    }

    private BigDecimal sum(List<AccountRow> accounts, LocalDateTime cutoff) {
        BigDecimal total = ZERO;
        for (AccountRow account : accounts) total = total.add(balances.asOf(account.id(), cutoff));
        return total;
    }

    private Flow flow(String type, List<Row> rows) {
        Map<Long, List<TagRef>> tags = tags(rows);
        List<CategoryRollup.Fact> categoryFacts = new ArrayList<>();
        List<TagRollup.Fact> tagFacts = new ArrayList<>();
        for (Row row : rows) {
            BigDecimal amount = signed(row);
            categoryFacts.add(new CategoryRollup.Fact(row.categoryId(), amount));
            tagFacts.add(new TagRollup.Fact(row.id(), amount, tags.getOrDefault(row.id(), List.of()).stream().map(TagRef::id).toList()));
        }
        CategoryRollup.Tree categories = CategoryRollup.rollup(categories(type), categoryFacts);
        TagRollup.Tree tagTree = TagRollup.rollup(tagNodes(), tagFacts);
        return new Flow(money(categories.net()), categoryNodes(categories.roots(), categories.net()),
                tagNodes(tagTree.roots(), tagTree.net()), slice(categories.uncategorized(), categories.net()),
                slice(tagTree.untagged(), tagTree.net()));
    }

    private List<CategoryNode> categoryNodes(List<CategoryRollup.NodeView> nodes, BigDecimal net) {
        List<CategoryNode> views = new ArrayList<>();
        for (CategoryRollup.NodeView node : nodes) {
            views.add(new CategoryNode(node.id(), node.name(), node.totals().transactionCount(),
                    money(node.totals().amount()), StatisticsShare.of(node.totals().amount(), net),
                    categoryNodes(node.children(), net)));
        }
        return List.copyOf(views);
    }

    private List<TagNode> tagNodes(List<TagRollup.NodeView> nodes, BigDecimal net) {
        List<TagNode> views = new ArrayList<>();
        for (TagRollup.NodeView node : nodes) {
            views.add(new TagNode(node.id(), node.name(), node.transactionCount(), money(node.amount()),
                    StatisticsShare.of(node.amount(), net), tagNodes(node.children(), net)));
        }
        return List.copyOf(views);
    }

    private static Slice slice(CategoryRollup.Totals totals, BigDecimal net) {
        if (totals.transactionCount() == 0) return null;
        return new Slice(totals.transactionCount(), money(totals.amount()), StatisticsShare.of(totals.amount(), net));
    }

    private Selection selection(String scopeRaw, String accountIds) {
        String scope = scopeRaw == null || scopeRaw.isBlank() ? "ACTIVE" : scopeRaw.trim();
        List<AccountRow> owned = jdbc.sql("""
                SELECT id, name, lifecycle, created_time
                FROM accounts WHERE user_id = :userId ORDER BY id
                """).param("userId", context.userId())
                .query(LedgerStatisticsService::account).list();
        List<AccountRow> chosen = switch (scope) {
            case "ACTIVE" -> owned.stream().filter(account -> "ACTIVE".equals(account.lifecycle())).toList();
            case "ALL" -> owned;
            case "SELECTED" -> selected(owned, accountIds);
            default -> throw new ApiFailure(400, "VALIDATION_FAILED", "账户范围必须为正常账户、全部账户或自选");
        };
        return new Selection(scope, chosen);
    }

    private List<AccountRow> selected(List<AccountRow> owned, String accountIds) {
        if (accountIds == null || accountIds.isBlank()) throw new ApiFailure(400, "VALIDATION_FAILED", "请选择账户");
        Map<Long, AccountRow> byId = new LinkedHashMap<>();
        for (AccountRow account : owned) byId.put(account.id(), account);
        List<AccountRow> chosen = new ArrayList<>();
        for (String part : accountIds.split(",")) {
            String token = part.trim();
            if (!token.matches("[1-9][0-9]*")) throw new ApiFailure(400, "VALIDATION_FAILED", "账户编号不正确");
            long id = Long.parseLong(token);
            AccountRow account = byId.get(id);
            if (account == null) throw new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在");
            if (chosen.stream().anyMatch(existing -> existing.id() == id)) {
                throw new ApiFailure(400, "VALIDATION_FAILED", "账户编号重复");
            }
            chosen.add(account);
        }
        return List.copyOf(chosen);
    }

    private List<Row> load(List<AccountRow> accounts, StatisticsPeriod.Range range, String type) {
        List<Long> ids = accounts.stream().map(AccountRow::id).toList();
        String typeClause = type == null ? "" : " AND t.type = :type";
        var query = jdbc.sql("""
                SELECT t.id, t.account_id, t.category_id, t.amount, t.type, t.name, t.business_time, a.created_time
                FROM transactions t
                JOIN accounts a ON a.id = t.account_id AND a.user_id = t.user_id
                WHERE t.user_id = :userId AND t.account_id IN (:accountIds)
                  AND t.business_time >= :start AND t.business_time <= :end
                """ + typeClause + """
                 ORDER BY t.business_time DESC, t.id DESC
                """).param("userId", context.userId()).param("accountIds", ids)
                .param("start", range.start()).param("end", range.endInclusive());
        if (type != null) query = query.param("type", type);
        return query.query(LedgerStatisticsService::row).list();
    }

    private Map<Long, List<TagRef>> tags(List<Row> rows) {
        Map<Long, List<TagRef>> tags = new LinkedHashMap<>();
        if (rows.isEmpty()) return tags;
        List<Long> ids = rows.stream().map(Row::id).toList();
        jdbc.sql("""
                SELECT tt.transaction_id, tg.id AS tag_id, tg.name
                FROM transaction_tags tt
                JOIN tags tg ON tg.id = tt.tag_id AND tg.user_id = :userId
                WHERE tt.transaction_id IN (:ids)
                ORDER BY tg.id
                """).param("userId", context.userId()).param("ids", ids)
                .query((rs, number) -> {
                    tags.computeIfAbsent(rs.getLong("transaction_id"), ignored -> new ArrayList<>())
                            .add(new TagRef(rs.getLong("tag_id"), rs.getString("name")));
                    return null;
                }).list();
        return tags;
    }

    private List<CategoryRollup.Node> categories(String type) {
        return jdbc.sql("""
                SELECT id, parent_id, name FROM categories
                WHERE user_id = :userId AND type = :type ORDER BY id
                """).param("userId", context.userId()).param("type", type)
                .query((rs, number) -> new CategoryRollup.Node(rs.getLong("id"), nullableLong(rs, "parent_id"), rs.getString("name")))
                .list();
    }

    private List<TagRollup.Node> tagNodes() {
        return jdbc.sql("""
                SELECT id, parent_id, name FROM tags WHERE user_id = :userId ORDER BY id
                """).param("userId", context.userId())
                .query((rs, number) -> new TagRollup.Node(rs.getLong("id"), nullableLong(rs, "parent_id"), rs.getString("name")))
                .list();
    }

    private Set<Long> categoryFilter(List<CategoryRollup.Node> categories, Drill drill) {
        if (drill.kind() != DrillKind.CATEGORY) return null;
        Set<Long> ids = CategoryRollup.subtreeIds(categories, drill.ids().get(0));
        if (ids.isEmpty()) throw new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在");
        return ids;
    }

    private Set<Long> tagFilter(Drill drill) {
        if (drill.kind() != DrillKind.TAG) return null;
        List<TagRollup.Node> tags = tagNodes();
        Set<Long> ids = new java.util.LinkedHashSet<>();
        for (long id : drill.ids()) {
            boolean known = tags.stream().anyMatch(tag -> tag.id() == id);
            if (!known) throw new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在");
            if (drill.direct()) ids.add(id);
            else ids.addAll(TagRollup.subtreeIds(tags, id));
        }
        if (ids.isEmpty()) throw new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在");
        return ids;
    }

    private static boolean matches(Row row, List<TagRef> tags, Set<Long> knownCategories, Set<Long> categoryIds,
            Set<Long> tagIds, Drill drill) {
        return switch (drill.kind()) {
            case CATEGORY -> row.categoryId() != null && categoryIds.contains(row.categoryId());
            case UNCATEGORIZED -> row.categoryId() == null || !knownCategories.contains(row.categoryId());
            case TAG -> tags.stream().anyMatch(tag -> tagIds.contains(tag.id()));
            case UNTAGGED -> tags.isEmpty();
        };
    }

    private static CategoryRef categoryRef(List<CategoryRollup.Node> categories, Long categoryId) {
        if (categoryId == null) return null;
        for (CategoryRollup.Node category : categories) {
            if (category.id() == categoryId) return new CategoryRef(category.id(), category.name());
        }
        return null;
    }

    private Drill drill(Long categoryId, Boolean uncategorized, Long tagId, String tagIdList, String tagMode, Boolean untagged) {
        boolean multiple = tagIdList != null && !tagIdList.isBlank();
        int selected = (categoryId != null ? 1 : 0) + (Boolean.TRUE.equals(uncategorized) ? 1 : 0)
                + (tagId != null ? 1 : 0) + (multiple ? 1 : 0) + (Boolean.TRUE.equals(untagged) ? 1 : 0);
        if (selected != 1) throw new ApiFailure(400, "VALIDATION_FAILED", "请选择一个分类或标签明细");
        if (categoryId != null) {
            if (categoryId <= 0) throw new ApiFailure(400, "VALIDATION_FAILED", "分类编号不正确");
            return new Drill(DrillKind.CATEGORY, List.of(categoryId), false);
        }
        if (Boolean.TRUE.equals(uncategorized)) return new Drill(DrillKind.UNCATEGORIZED, List.of(), false);
        if (Boolean.TRUE.equals(untagged)) return new Drill(DrillKind.UNTAGGED, List.of(), false);
        if (multiple) return new Drill(DrillKind.TAG, parseIds(tagIdList), false);
        if (tagId == null || tagId <= 0) throw new ApiFailure(400, "VALIDATION_FAILED", "标签编号不正确");
        String mode = tagMode == null || tagMode.isBlank() ? "TREE" : tagMode.trim();
        if (!mode.equals("TREE") && !mode.equals("DIRECT")) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "标签明细必须为标签树或直接关联");
        }
        return new Drill(DrillKind.TAG, List.of(tagId), mode.equals("DIRECT"));
    }

    private static List<Long> parseIds(String raw) {
        List<Long> ids = new ArrayList<>();
        for (String part : raw.split(",")) {
            String token = part.trim();
            if (!token.matches("[1-9][0-9]*")) throw new ApiFailure(400, "VALIDATION_FAILED", "标签编号不正确");
            long id = Long.parseLong(token);
            if (ids.contains(id)) throw new ApiFailure(400, "VALIDATION_FAILED", "标签编号重复");
            ids.add(id);
        }
        if (ids.isEmpty()) throw new ApiFailure(400, "VALIDATION_FAILED", "请选择标签");
        return List.copyOf(ids);
    }

    private static String flowType(String type) {
        if ("INCOME".equals(type) || "EXPENSE".equals(type)) return type;
        throw new ApiFailure(400, "VALIDATION_FAILED", "统计类型必须为收入或支出");
    }

    private static String money(BigDecimal value) {
        return Money.format(value.setScale(2, RoundingMode.UNNECESSARY));
    }

    /** The stored amount is already signed. Expense rows stay positive unless the transaction itself is negative. */
    private static BigDecimal signed(Row row) {
        return row.amount();
    }

    private static List<AccountRef> refs(List<AccountRow> accounts) {
        return accounts.stream().map(account -> new AccountRef(account.id(), account.name(), account.lifecycle())).toList();
    }

    private static String beijing(LocalDateTime time) {
        return TIME.format(time.atZone(StatisticsPeriod.BEIJING));
    }

    private static String blankToNull(String name) {
        if (name == null || name.isBlank()) return null;
        return name;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static AccountRow account(ResultSet rs, int number) throws SQLException {
        return new AccountRow(rs.getLong("id"), rs.getString("name"), rs.getString("lifecycle"),
                rs.getTimestamp("created_time").toLocalDateTime());
    }

    private static Row row(ResultSet rs, int number) throws SQLException {
        return new Row(rs.getLong("id"), rs.getLong("account_id"), nullableLong(rs, "category_id"),
                rs.getBigDecimal("amount"), rs.getString("type"), rs.getString("name"),
                rs.getTimestamp("business_time").toLocalDateTime(), rs.getTimestamp("created_time").toLocalDateTime());
    }

    private record AccountRow(long id, String name, String lifecycle, LocalDateTime createdTime) { }
    private record Row(long id, long accountId, Long categoryId, BigDecimal amount, String type, String name,
            LocalDateTime businessTime, LocalDateTime accountCreatedTime) { }
    private record Selection(String scope, List<AccountRow> accounts) { }
    private enum DrillKind { CATEGORY, UNCATEGORIZED, TAG, UNTAGGED }
    private record Drill(DrillKind kind, List<Long> ids, boolean direct) {
        private Long categoryIds() { return kind == DrillKind.CATEGORY ? ids.get(0) : null; }
        private Long tagIds() { return kind == DrillKind.TAG ? ids.get(0) : null; }
    }
}
