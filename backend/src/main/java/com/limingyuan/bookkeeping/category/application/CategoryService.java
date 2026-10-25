package com.limingyuan.bookkeeping.category.application;

import com.limingyuan.bookkeeping.category.domain.CategoryNames;
import com.limingyuan.bookkeeping.category.domain.CategoryType;
import com.limingyuan.bookkeeping.category.persistence.CategoryEntity;
import com.limingyuan.bookkeeping.category.persistence.CategoryRepository;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CategoryService {
    private static final int MAX_DEPTH = 3;
    private final CategoryRepository categories;
    private final TrustedUserContext context;
    private final Clock clock;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public CategoryService(CategoryRepository categories, TrustedUserContext context, Clock clock, JdbcClient jdbc,
            PlatformTransactionManager transactionManager) {
        this.categories = categories;
        this.context = context;
        this.clock = clock;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record CategoryView(long id, String name, CategoryType type, boolean bookable, List<CategoryView> children) { }
    public record BookableCategory(long id, String name) { }
    public record DeletionPreview(int directTransactionCount) { }

    @Transactional(readOnly = true)
    public List<CategoryView> list(String requestedType) {
        var type = type(requestedType);
        var owned = categories.findAllByUserIdAndTypeOrderById(context.userId(), type);
        var byParent = new HashMap<Long, List<CategoryEntity>>();
        for (var category : owned) {
            byParent.computeIfAbsent(category.getParentId(), key -> new ArrayList<>()).add(category);
        }
        return byParent.getOrDefault(null, List.of()).stream().map(root -> view(root, byParent)).toList();
    }

    public CategoryView create(String requestedType, String rawName, Long parentId) {
        long userId = context.userId();
        var type = type(requestedType);
        var name = name(rawName);
        try {
            return transaction.execute(status -> {
                if (categories.existsByUserIdAndTypeAndName(userId, type, name)) throw nameConflict();
                var parent = parent(userId, parentId, type);
                if (parent != null) {
                    rejectParentTransactions(userId, parent.getId());
                    var owned = categories.findAllByUserIdOrderById(userId);
                    if (depth(parent, index(owned)) + 1 > MAX_DEPTH) throw depthExceeded();
                }
                var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
                var created = categories.saveAndFlush(new CategoryEntity(userId, type, name, parentId, now));
                return new CategoryView(created.getId(), created.getName(), created.getType(), true, List.of());
            });
        } catch (DataIntegrityViolationException ex) {
            if (categories.existsByUserIdAndTypeAndName(userId, type, name)) throw nameConflict();
            throw ex;
        }
    }

    public CategoryView rename(long categoryId, String rawName) {
        long userId = context.userId();
        var name = name(rawName);
        try {
            return transaction.execute(status -> {
                var category = owned(categoryId);
                if (categories.existsByUserIdAndTypeAndName(userId, category.getType(), name)
                        && !category.getName().equals(name)) {
                    throw nameConflict();
                }
                category.rename(name);
                categories.saveAndFlush(category);
                return persistedView(category);
            });
        } catch (DataIntegrityViolationException ex) {
            var category = categories.findByIdAndUserId(categoryId, userId).orElse(null);
            if (category != null && categories.existsByUserIdAndTypeAndName(userId, category.getType(), name)) {
                throw nameConflict();
            }
            throw ex;
        }
    }

    public CategoryView move(long categoryId, Long parentId) {
        long userId = context.userId();
        return transaction.execute(status -> {
            var category = owned(categoryId);
            if (Objects.equals(category.getParentId(), parentId)) return persistedView(category);
            var owned = categories.findAllByUserIdOrderById(userId);
            var byId = index(owned);
            var parent = parentId == null ? null : parent(userId, parentId, category.getType());
            if (parent != null) {
                rejectParentTransactions(userId, parent.getId());
                if (createsCycle(category.getId(), parent, byId)) throw cycle();
            }
            int nextDepth = parent == null ? 1 : depth(parent, byId) + 1;
            if (nextDepth + descendantSpan(category.getId(), children(owned)) > MAX_DEPTH) throw depthExceeded();
            category.place(parentId);
            categories.saveAndFlush(category);
            return persistedView(category);
        });
    }

    @Transactional(readOnly = true)
    public DeletionPreview deletionPreview(long categoryId) {
        var category = owned(categoryId);
        if (categories.existsByUserIdAndParentId(context.userId(), category.getId())) throw hasChildren();
        return new DeletionPreview(directTransactions(context.userId(), category.getId()));
    }

    public void delete(long categoryId, int expectedDirectTransactionCount) {
        long userId = context.userId();
        transaction.executeWithoutResult(status -> {
            var category = owned(categoryId);
            if (categories.existsByUserIdAndParentId(userId, category.getId())) throw hasChildren();
            int actual = directTransactions(userId, category.getId());
            if (actual != expectedDirectTransactionCount) throw deleteConflict();
            jdbc.sql("UPDATE transactions SET category_id = NULL WHERE user_id = :userId AND category_id = :categoryId")
                    .param("userId", userId).param("categoryId", category.getId()).update();
            categories.delete(category);
            categories.flush();
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public BookableCategory requireBookable(long categoryId, String transactionType) {
        var category = owned(categoryId);
        CategoryType expected = CategoryType.valueOf(transactionType);
        if (category.getType() != expected) {
            throw new ApiFailure(409, "CATEGORY_TYPE_MISMATCH", "分类类型与交易类型不一致");
        }
        if (categories.existsByUserIdAndParentId(context.userId(), category.getId())) {
            throw new ApiFailure(409, "CATEGORY_NOT_BOOKABLE", "该分类不能直接记账");
        }
        return new BookableCategory(category.getId(), category.getName());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireVisible(long categoryId) {
        owned(categoryId);
    }

    private CategoryView view(CategoryEntity node, java.util.Map<Long, List<CategoryEntity>> byParent) {
        var children = byParent.getOrDefault(node.getId(), List.of()).stream()
                .map(child -> view(child, byParent)).toList();
        return new CategoryView(node.getId(), node.getName(), node.getType(), children.isEmpty(), children);
    }

    private CategoryView persistedView(CategoryEntity category) {
        var owned = categories.findAllByUserIdAndTypeOrderById(category.getUserId(), category.getType());
        var byParent = new HashMap<Long, List<CategoryEntity>>();
        for (var item : owned) byParent.computeIfAbsent(item.getParentId(), key -> new ArrayList<>()).add(item);
        return view(category, byParent);
    }

    private CategoryEntity parent(long userId, Long parentId, CategoryType type) {
        if (parentId == null) return null;
        var parent = categories.findByIdAndUserId(parentId, userId)
                .orElseThrow(() -> new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在"));
        if (parent.getType() != type) throw new ApiFailure(409, "CATEGORY_TYPE_MISMATCH", "分类不能跨收支类型移动或创建");
        return parent;
    }

    private CategoryEntity owned(long categoryId) {
        return categories.findByIdAndUserId(categoryId, context.userId())
                .orElseThrow(() -> new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在"));
    }

    private void rejectParentTransactions(long userId, long parentId) {
        if (directTransactions(userId, parentId) > 0) {
            throw new ApiFailure(409, "CATEGORY_PARENT_HAS_TRANSACTIONS", "已有直接交易的分类不能再作为父分类");
        }
    }

    private int directTransactions(long userId, long categoryId) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM transactions WHERE user_id = :userId AND category_id = :categoryId")
                .param("userId", userId).param("categoryId", categoryId).query(Long.class).single();
        return count.intValue();
    }

    private java.util.Map<Long, CategoryEntity> index(List<CategoryEntity> owned) {
        var byId = new HashMap<Long, CategoryEntity>();
        for (var category : owned) byId.put(category.getId(), category);
        return byId;
    }

    private java.util.Map<Long, List<CategoryEntity>> children(List<CategoryEntity> owned) {
        var byParent = new HashMap<Long, List<CategoryEntity>>();
        for (var category : owned) {
            if (category.getParentId() != null) {
                byParent.computeIfAbsent(category.getParentId(), key -> new ArrayList<>()).add(category);
            }
        }
        return byParent;
    }

    private int depth(CategoryEntity node, java.util.Map<Long, CategoryEntity> byId) {
        int depth = 1;
        var seen = new HashSet<Long>();
        seen.add(node.getId());
        Long parentId = node.getParentId();
        while (parentId != null) {
            if (!seen.add(parentId)) throw cycle();
            var parent = byId.get(parentId);
            if (parent == null) throw new ApiFailure(409, "CATEGORY_PARENT_INVALID", "分类父节点不存在");
            depth++;
            parentId = parent.getParentId();
        }
        return depth;
    }

    private int descendantSpan(long id, java.util.Map<Long, List<CategoryEntity>> children) {
        int span = 0;
        for (var child : children.getOrDefault(id, List.of())) {
            span = Math.max(span, 1 + descendantSpan(child.getId(), children));
        }
        return span;
    }

    private boolean createsCycle(long categoryId, CategoryEntity parent, java.util.Map<Long, CategoryEntity> byId) {
        var seen = new HashSet<Long>();
        CategoryEntity current = parent;
        while (current != null) {
            if (current.getId() == categoryId) return true;
            if (!seen.add(current.getId())) return true;
            current = current.getParentId() == null ? null : byId.get(current.getParentId());
        }
        return false;
    }

    private CategoryType type(String requestedType) {
        try {
            return CategoryType.valueOf(requestedType);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "分类类型必须为 INCOME 或 EXPENSE");
        }
    }

    private String name(String rawName) {
        try {
            return CategoryNames.required(rawName);
        } catch (IllegalArgumentException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "分类名称去除首尾空白后不能为空，且最长 100 个字符");
        }
    }

    private ApiFailure nameConflict() {
        return new ApiFailure(409, "CATEGORY_NAME_CONFLICT", "同一收支类型下分类名称已存在");
    }

    private ApiFailure hasChildren() {
        return new ApiFailure(409, "CATEGORY_HAS_CHILDREN", "有下级的分类不能删除");
    }

    private ApiFailure deleteConflict() {
        return new ApiFailure(409, "CATEGORY_DELETE_CONFLICT", "直接关联交易数量已变化，请重新确认");
    }

    private ApiFailure cycle() {
        return new ApiFailure(409, "CATEGORY_CYCLE", "分类不能移动到自己或自己的下级");
    }

    private ApiFailure depthExceeded() {
        return new ApiFailure(409, "CATEGORY_DEPTH", "分类最多三级");
    }
}
