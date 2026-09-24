package com.limingyuan.bookkeeping.transaction.application;

import com.limingyuan.bookkeeping.account.application.AccountService;
import com.limingyuan.bookkeeping.category.application.CategoryService;
import com.limingyuan.bookkeeping.common.Money;
import com.limingyuan.bookkeeping.common.api.ApiErrors;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.tag.application.TagService;
import com.limingyuan.bookkeeping.transaction.domain.TransactionType;
import com.limingyuan.bookkeeping.transaction.persistence.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransactionService {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");
    private static final int NAME_LIMIT = 255;
    private final AccountService accounts;
    private final CategoryService categories;
    private final TagService tags;
    private final TransactionRepository transactions;
    private final TransactionTagRepository associations;
    private final TrustedUserContext context;
    private final Clock clock;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public TransactionService(AccountService accounts, CategoryService categories, TagService tags,
            TransactionRepository transactions, TransactionTagRepository associations, TrustedUserContext context,
            Clock clock, JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.categories = categories;
        this.tags = tags;
        this.transactions = transactions;
        this.associations = associations;
        this.context = context;
        this.clock = clock;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record CategoryRef(long id, String name) { }
    public record TagRef(long id, String name) { }
    public record TransactionView(long id, long accountId, TransactionType type, String amount, String name,
            String displayName, CategoryRef category, List<TagRef> tags, String businessTime, String remark) { }
    public record TransactionListItem(long id, TransactionType type, String amount, String displayName,
            CategoryRef category, List<TagRef> tags, String businessTime) { }
    public record TransactionList(String month, List<TransactionListItem> items) { }
    public record Draft(String amount, String name, long categoryId, List<Long> tagIds, String businessTime, String remark) { }

    public TransactionView create(long accountId, String typeRaw, Draft draft) {
        long userId = context.userId();
        TransactionType type = type(typeRaw);
        var fields = fields(draft);
        return transaction.execute(status -> {
            var allocation = accounts.allocateFirstSaveOrder(accountId);
            var category = categories.requireBookable(fields.categoryId(), type.name());
            var ownedTags = tags.requireOwned(fields.tagIds(), type.name());
            var businessTime = businessTime(fields.businessTime(), allocation.createdTime());
            var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            var saved = transactions.saveAndFlush(new TransactionEntity(userId, allocation.accountId(), type,
                    fields.amount(), blankToNull(fields.name(), NAME_LIMIT), category.id(), businessTime, now,
                    allocation.firstSaveOrder(), blankToNull(fields.remark(), Integer.MAX_VALUE)));
            replaceTags(saved.getId(), ownedTags);
            return detail(saved, category.name(), ownedTags);
        });
    }

    @Transactional(readOnly = true)
    public TransactionView get(long accountId, long transactionId) {
        return view(load(accountId, transactionId));
    }

    public TransactionView edit(long accountId, long transactionId, Draft draft) {
        var fields = fields(draft);
        return transaction.execute(status -> {
            var locked = accounts.lockForWrite(accountId);
            var current = load(accountId, transactionId);
            var category = categories.requireBookable(fields.categoryId(), current.getType().name());
            var ownedTags = tags.requireOwned(fields.tagIds(), current.getType().name());
            var businessTime = businessTime(fields.businessTime(), locked.createdTime());
            var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            current.edit(fields.amount(), blankToNull(fields.name(), NAME_LIMIT), category.id(), businessTime,
                    blankToNull(fields.remark(), Integer.MAX_VALUE), now);
            transactions.saveAndFlush(current);
            replaceTags(current.getId(), ownedTags);
            return detail(current, category.name(), ownedTags);
        });
    }

    public void delete(long accountId, long transactionId) {
        transaction.executeWithoutResult(status -> {
            accounts.lockForWrite(accountId);
            var current = load(accountId, transactionId);
            associations.deleteByTransactionId(current.getId());
            associations.flush();
            transactions.delete(current);
            transactions.flush();
        });
    }

    public TransactionList list(long accountId, String monthRaw, String typeRaw, Long categoryId, Long tagId) {
        var month = month(monthRaw);
        TransactionType type = typeRaw == null || typeRaw.isBlank() ? null : type(typeRaw);
        return transaction.execute(status -> {
            accounts.requireOwned(accountId);
            if (categoryId != null) categories.requireVisible(categoryId);
            if (tagId != null) tags.requireVisible(tagId);
            return query(accountId, month, type, categoryId, tagId);
        });
    }

    private TransactionList query(long accountId, YearMonth month, TransactionType type, Long categoryId, Long tagId) {
        var sql = new StringBuilder("""
                SELECT t.id, t.type, t.amount, t.name, t.category_id, c.name AS category_name, t.business_time
                FROM transactions t
                JOIN categories c ON c.id = t.category_id
                WHERE t.account_id = :accountId AND t.user_id = :userId
                  AND t.business_time >= :start AND t.business_time < :end
                """);
        if (type != null) sql.append(" AND t.type = :type");
        if (categoryId != null) sql.append(" AND t.category_id = :categoryId");
        if (tagId != null) sql.append("""
                 AND EXISTS (SELECT 1 FROM transaction_tags tt WHERE tt.transaction_id = t.id AND tt.tag_id = :tagId)
                """);
        sql.append(" ORDER BY t.business_time DESC, t.first_save_order DESC");
        var query = jdbc.sql(sql.toString())
                .param("accountId", accountId)
                .param("userId", context.userId())
                .param("start", month.atDay(1).atStartOfDay())
                .param("end", month.plusMonths(1).atDay(1).atStartOfDay());
        if (type != null) query.param("type", type.name());
        if (categoryId != null) query.param("categoryId", categoryId);
        if (tagId != null) query.param("tagId", tagId);
        var rows = query.query((rs, row) -> new TransactionListItem(rs.getLong("id"),
                TransactionType.valueOf(rs.getString("type")), Money.format(rs.getBigDecimal("amount")),
                displayName(rs.getString("name"), rs.getString("category_name")),
                new CategoryRef(rs.getLong("category_id"), rs.getString("category_name")),
                List.of(), TIME.format(rs.getObject("business_time", LocalDateTime.class).atZone(BEIJING)))).list();
        var items = new ArrayList<TransactionListItem>();
        // Phase 6 Ledger / read-model follow-up: tag names are loaded once per row. Leave this in Phase 4A.
        for (var row : rows) items.add(withTags(row));
        return new TransactionList(month.toString(), items);
    }

    private TransactionListItem withTags(TransactionListItem row) {
        return new TransactionListItem(row.id(), row.type(), row.amount(), row.displayName(), row.category(),
                tagRefs(row.id()), row.businessTime());
    }

    private TransactionEntity load(long accountId, long transactionId) {
        return transactions.findByIdAndAccountIdAndUserId(transactionId, accountId, context.userId())
                .orElseThrow(() -> new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在"));
    }

    private void replaceTags(long transactionId, List<TagService.OwnedTag> ownedTags) {
        associations.deleteByTransactionId(transactionId);
        associations.flush();
        for (var tag : ownedTags) associations.save(new TransactionTagEntity(transactionId, tag.id()));
        associations.flush();
    }

    private TransactionView view(TransactionEntity entity) {
        var category = categories.requireBookable(entity.getCategoryId(), entity.getType().name());
        return detail(entity, category.name(), tags.requireOwned(tagRefs(entity.getId()).stream().map(TagRef::id).toList(),
                entity.getType().name()));
    }

    private List<TagRef> tagRefs(long transactionId) {
        var ids = associations.findAllByTransactionIdOrderByTagId(transactionId).stream()
                .map(TransactionTagEntity::getTagId).toList();
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("""
                SELECT id, name FROM tags WHERE user_id = :userId AND id IN (:ids) ORDER BY id
                """).param("userId", context.userId()).param("ids", ids)
                .query((rs, row) -> new TagRef(rs.getLong("id"), rs.getString("name"))).list();
    }

    private TransactionView detail(TransactionEntity entity, String categoryName, List<TagService.OwnedTag> ownedTags) {
        var tagRefs = ownedTags.stream().map(tag -> new TagRef(tag.id(), tag.name())).toList();
        return new TransactionView(entity.getId(), entity.getAccountId(), entity.getType(), Money.format(entity.getAmount()),
                entity.getName(), displayName(entity.getName(), categoryName), new CategoryRef(entity.getCategoryId(), categoryName),
                tagRefs, TIME.format(entity.getBusinessTime().atZone(BEIJING)), entity.getRemark());
    }

    private static String displayName(String name, String categoryName) {
        return name == null || name.isBlank() ? categoryName : name;
    }

    private record Parsed(BigDecimal amount, String name, long categoryId, List<Long> tagIds, String businessTime, String remark) { }

    private Parsed fields(Draft draft) {
        var amount = Money.parse(draft.amount());
        if (amount.signum() == 0) throw new ApiFailure(400, "VALIDATION_FAILED", "请求参数不合法",
                List.of(new ApiErrors.Detail("amount", "MUST_NOT_BE_ZERO")));
        return new Parsed(amount, draft.name(), draft.categoryId(), draft.tagIds() == null ? List.of() : draft.tagIds(),
                draft.businessTime(), draft.remark());
    }

    private TransactionType type(String raw) {
        try { return TransactionType.valueOf(raw); }
        catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "交易类型必须为 INCOME 或 EXPENSE");
        }
    }

    private LocalDateTime businessTime(String raw, LocalDateTime accountCreated) {
        OffsetDateTime parsed;
        try { parsed = OffsetDateTime.parse(raw); }
        catch (DateTimeParseException | NullPointerException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "业务时间必须是带 +08:00 的秒级时间");
        }
        if (!parsed.getOffset().equals(ZoneOffset.ofHours(8)) || parsed.getNano() != 0) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "业务时间必须是带 +08:00 的秒级时间");
        }
        var local = parsed.toLocalDateTime();
        if (local.isBefore(accountCreated)) {
            throw new ApiFailure(409, "BUSINESS_RULE_VIOLATION", "交易时间不能早于资金账户创建时间",
                    List.of(new ApiErrors.Detail("businessTime", "BEFORE_ACCOUNT_CREATION")));
        }
        return local;
    }

    private YearMonth month(String raw) {
        try { return YearMonth.parse(raw); }
        catch (DateTimeParseException | NullPointerException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "月份必须为 YYYY-MM");
        }
    }

    private static String blankToNull(String raw, int limit) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > limit) throw new ApiFailure(400, "VALIDATION_FAILED", "名称最长 255 个字符");
        return trimmed;
    }
}
