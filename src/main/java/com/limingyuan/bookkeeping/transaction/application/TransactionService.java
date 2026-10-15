package com.limingyuan.bookkeeping.transaction.application;

import com.limingyuan.bookkeeping.account.application.AccountService;
import com.limingyuan.bookkeeping.category.application.CategoryService;
import com.limingyuan.bookkeeping.idempotency.persistence.IdempotencyRecord;
import com.limingyuan.bookkeeping.idempotency.persistence.IdempotencyRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
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
    static final String CREATE_SCOPE = "CREATE_TRANSACTION";
    private final AccountService accounts;
    private final IdempotencyRepository idempotency;
    private final CategoryService categories;
    private final TagService tags;
    private final TransactionRepository transactions;
    private final TransactionTagRepository associations;
    private final TrustedUserContext context;
    private final Clock clock;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public TransactionService(AccountService accounts, IdempotencyRepository idempotency, CategoryService categories, TagService tags,
            TransactionRepository transactions, TransactionTagRepository associations, TrustedUserContext context,
            Clock clock, JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.idempotency = idempotency;
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
    public record TransactionSearchResult(List<TransactionListItem> items) { }
    public record Draft(String amount, String name, Long categoryId, List<Long> tagIds, String businessTime, String remark) { }
    public record CreateOutcome(int status, TransactionView body, boolean replay) { }

    public CreateOutcome create(long accountId, String operationId, String typeRaw, Draft draft) {
        long userId = context.userId();
        String key = operationKey(operationId);
        TransactionType type = type(typeRaw);
        var fields = fields(draft);
        byte[] hash = requestHash(accountId, type, fields);
        try {
            return transaction.execute(status -> createOnce(userId, accountId, key, type, fields, hash));
        } catch (DataIntegrityViolationException ex) {
            var winner = idempotency.findByUserIdAndScopeAndOperationId(userId, CREATE_SCOPE, key).orElse(null);
            if (winner == null) throw ex;
            return transaction.execute(status -> outcome(winner, hash));
        }
    }

    private CreateOutcome createOnce(long userId, long accountId, String key, TransactionType type, Parsed fields, byte[] hash) {
        // Lock the account before touching idempotency. A pessimistic lock on a missing
        // idempotency row takes an InnoDB gap lock and deadlocks with the account row lock.
        accounts.lockForWrite(accountId);
        var existing = idempotency.findByUserIdAndScopeAndOperationId(userId, CREATE_SCOPE, key).orElse(null);
        if (existing != null) return outcome(existing, hash);
        var allocation = accounts.allocateFirstSaveOrder(accountId);
        var category = bookable(fields.categoryId(), type.name());
        var ownedTags = tags.requireOwned(fields.tagIds(), type.name());
        var businessTime = businessTime(fields.businessTime(), allocation.createdTime());
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var saved = transactions.saveAndFlush(new TransactionEntity(userId, allocation.accountId(), type,
                fields.amount(), blankToNull(fields.name(), NAME_LIMIT), category == null ? null : category.id(), businessTime, now,
                allocation.firstSaveOrder(), blankToNull(fields.remark(), Integer.MAX_VALUE)));
        replaceTags(saved.getId(), ownedTags);
        idempotency.saveAndFlush(new IdempotencyRecord(userId, CREATE_SCOPE, key, hash, "CREATED", saved.getId(), now));
        return new CreateOutcome(201, detail(saved, category == null ? null : category.name(), ownedTags), false);
    }

    private CreateOutcome outcome(IdempotencyRecord record, byte[] hash) {
        if (!record.sameRequest(hash)) {
            throw new ApiFailure(409, "IDEMPOTENCY_CONFLICT", "同一个 Idempotency-Key 已用于不同的创建请求");
        }
        if (record.getResourceId() == null) {
            throw new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在").header("Idempotent-Replay", "true");
        }
        var saved = transactions.findById(record.getResourceId()).filter(item -> item.getUserId() == context.userId()).orElse(null);
        if (saved == null) {
            throw new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在").header("Idempotent-Replay", "true");
        }
        return new CreateOutcome(200, view(saved), true);
    }

    private byte[] requestHash(long accountId, TransactionType type, Parsed fields) {
        var tags = new ArrayList<>(fields.tagIds());
        tags.sort(null);
        var time = parseBusinessTime(fields.businessTime());
        return TransactionRequestHash.sha256(accountId, type.name(), Money.format(fields.amount()), fields.categoryId(),
                tags, TIME.format(time.atZone(BEIJING)), text(fields.name()), text(fields.remark()));
    }

    private static String operationKey(String raw) {
        if (raw == null || raw.trim().isEmpty() || raw.trim().length() > 64) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "Idempotency-Key 不能为空且最长 64 个字符");
        }
        return raw.trim();
    }

    private static String text(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? "" : trimmed;
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
            var category = bookable(fields.categoryId(), current.getType().name());
            var ownedTags = tags.requireOwned(fields.tagIds(), current.getType().name());
            var businessTime = businessTime(fields.businessTime(), locked.createdTime());
            var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            current.edit(fields.amount(), blankToNull(fields.name(), NAME_LIMIT), category == null ? null : category.id(), businessTime,
                    blankToNull(fields.remark(), Integer.MAX_VALUE), now);
            transactions.saveAndFlush(current);
            replaceTags(current.getId(), ownedTags);
            return detail(current, category == null ? null : category.name(), ownedTags);
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

    public TransactionSearchResult searchByName(long accountId, String nameRaw) {
        String keyword = searchKeyword(nameRaw);
        String pattern = likeContainsPattern(keyword);
        return transaction.execute(status -> {
            accounts.requireOwned(accountId);
            return searchQuery(accountId, pattern);
        });
    }

    public TransactionList list(long accountId, String monthRaw, String typeRaw, Long categoryId, Boolean uncategorizedOnly,
            Long tagId, Boolean untaggedOnly) {
        var month = month(monthRaw);
        TransactionType type = typeRaw == null || typeRaw.isBlank() ? null : type(typeRaw);
        boolean onlyUncategorized = Boolean.TRUE.equals(uncategorizedOnly);
        boolean onlyUntagged = Boolean.TRUE.equals(untaggedOnly);
        if (onlyUncategorized && categoryId != null) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "categoryId 与 uncategorizedOnly 不能同时使用");
        }
        if (onlyUntagged && tagId != null) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "tagId 与 untaggedOnly 不能同时使用");
        }
        return transaction.execute(status -> {
            accounts.requireOwned(accountId);
            if (categoryId != null) categories.requireVisible(categoryId);
            if (tagId != null) tags.requireVisible(tagId);
            return query(accountId, month, type, categoryId, onlyUncategorized, tagId, onlyUntagged);
        });
    }

    private TransactionSearchResult searchQuery(long accountId, String pattern) {
        var rows = jdbc.sql("""
                SELECT t.id, t.type, t.amount, t.name, t.category_id, c.name AS category_name, t.business_time
                FROM transactions t
                LEFT JOIN categories c ON c.id = t.category_id
                WHERE t.account_id = :accountId AND t.user_id = :userId
                  AND t.name IS NOT NULL AND t.name <> ''
                  AND t.name LIKE :pattern ESCAPE '\\'
                ORDER BY t.business_time DESC, t.first_save_order DESC
                """).param("accountId", accountId).param("userId", context.userId()).param("pattern", pattern)
                .query((rs, row) -> {
                    Long storedCategoryId = rs.getObject("category_id", Long.class);
                    String categoryName = rs.getString("category_name");
                    return new TransactionListItem(rs.getLong("id"),
                            TransactionType.valueOf(rs.getString("type")), Money.format(rs.getBigDecimal("amount")),
                            displayName(rs.getString("name"), categoryName),
                            storedCategoryId == null ? null : new CategoryRef(storedCategoryId, categoryName),
                            List.of(), TIME.format(rs.getObject("business_time", LocalDateTime.class).atZone(BEIJING)));
                }).list();
        var items = new ArrayList<TransactionListItem>();
        for (var row : rows) items.add(withTags(row));
        return new TransactionSearchResult(items);
    }

    private TransactionList query(long accountId, YearMonth month, TransactionType type, Long categoryId,
            boolean uncategorizedOnly, Long tagId, boolean untaggedOnly) {
        var sql = new StringBuilder("""
                SELECT t.id, t.type, t.amount, t.name, t.category_id, c.name AS category_name, t.business_time
                FROM transactions t
                LEFT JOIN categories c ON c.id = t.category_id
                WHERE t.account_id = :accountId AND t.user_id = :userId
                  AND t.business_time >= :start AND t.business_time < :end
                """);
        if (type != null) sql.append(" AND t.type = :type");
        if (uncategorizedOnly) sql.append(" AND t.category_id IS NULL");
        else if (categoryId != null) sql.append(" AND t.category_id = :categoryId");
        if (tagId != null) sql.append("""
                 AND EXISTS (SELECT 1 FROM transaction_tags tt WHERE tt.transaction_id = t.id AND tt.tag_id = :tagId)
                """);
        else if (untaggedOnly) sql.append("""
                 AND NOT EXISTS (SELECT 1 FROM transaction_tags tt WHERE tt.transaction_id = t.id)
                """);
        sql.append(" ORDER BY t.business_time DESC, t.first_save_order DESC");
        var query = jdbc.sql(sql.toString())
                .param("accountId", accountId)
                .param("userId", context.userId())
                .param("start", month.atDay(1).atStartOfDay())
                .param("end", month.plusMonths(1).atDay(1).atStartOfDay());
        if (type != null) query.param("type", type.name());
        if (!uncategorizedOnly && categoryId != null) query.param("categoryId", categoryId);
        if (tagId != null) query.param("tagId", tagId);
        var rows = query.query((rs, row) -> {
            Long storedCategoryId = rs.getObject("category_id", Long.class);
            String categoryName = rs.getString("category_name");
            return new TransactionListItem(rs.getLong("id"),
                TransactionType.valueOf(rs.getString("type")), Money.format(rs.getBigDecimal("amount")),
                displayName(rs.getString("name"), categoryName),
                storedCategoryId == null ? null : new CategoryRef(storedCategoryId, categoryName),
                List.of(), TIME.format(rs.getObject("business_time", LocalDateTime.class).atZone(BEIJING)));
        }).list();
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
        var category = bookable(entity.getCategoryId(), entity.getType().name());
        return detail(entity, category == null ? null : category.name(), tags.requireOwned(tagRefs(entity.getId()).stream().map(TagRef::id).toList(),
                entity.getType().name()));
    }

    private CategoryService.BookableCategory bookable(Long categoryId, String transactionType) {
        return categoryId == null ? null : categories.requireBookable(categoryId, transactionType);
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
                entity.getName(), displayName(entity.getName(), categoryName),
                entity.getCategoryId() == null ? null : new CategoryRef(entity.getCategoryId(), categoryName),
                tagRefs, TIME.format(entity.getBusinessTime().atZone(BEIJING)), entity.getRemark());
    }

    private static String displayName(String name, String categoryName) {
        if (name != null && !name.isBlank()) return name;
        return categoryName == null || categoryName.isBlank() ? null : categoryName;
    }

    private record Parsed(BigDecimal amount, String name, Long categoryId, List<Long> tagIds, String businessTime, String remark) { }

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

    private LocalDateTime parseBusinessTime(String raw) {
        OffsetDateTime parsed;
        try { parsed = OffsetDateTime.parse(raw); }
        catch (DateTimeParseException | NullPointerException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "业务时间必须是带 +08:00 的秒级时间");
        }
        if (!parsed.getOffset().equals(ZoneOffset.ofHours(8)) || parsed.getNano() != 0) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "业务时间必须是带 +08:00 的秒级时间");
        }
        return parsed.toLocalDateTime();
    }

    private LocalDateTime businessTime(String raw, LocalDateTime accountCreated) {
        var local = parseBusinessTime(raw);
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

    private static String searchKeyword(String raw) {
        if (raw == null) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "名称关键词不能为空");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "名称关键词不能为空");
        }
        if (trimmed.length() > NAME_LIMIT) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "名称最长 255 个字符");
        }
        return trimmed;
    }

    static String likeContainsPattern(String keyword) {
        var escaped = new StringBuilder(keyword.length() * 2 + 2);
        escaped.append('%');
        for (int index = 0; index < keyword.length(); index++) {
            char character = keyword.charAt(index);
            if (character == '%' || character == '_' || character == '\\') {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        escaped.append('%');
        return escaped.toString();
    }
}
