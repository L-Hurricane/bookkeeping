package com.limingyuan.bookkeeping.tag.application;

import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.tag.domain.TagNames;
import com.limingyuan.bookkeeping.tag.domain.TagType;
import com.limingyuan.bookkeeping.tag.persistence.TagEntity;
import com.limingyuan.bookkeeping.tag.persistence.TagRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TagService {
    private static final int MAX_DEPTH = 3;
    private final TagRepository tags;
    private final TrustedUserContext context;
    private final Clock clock;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public TagService(TagRepository tags, TrustedUserContext context, Clock clock, JdbcClient jdbc,
            PlatformTransactionManager transactionManager) {
        this.tags = tags;
        this.context = context;
        this.clock = clock;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record TagView(long id, String name, TagType type, List<TagView> children) { }
    public record DeletionPreview(int directTransactionCount) { }

    @Transactional(readOnly = true)
    public List<TagView> list(String requestedType) {
        TagType type = type(requestedType);
        var owned = tags.findAllByUserIdOrderById(context.userId());
        var byParent = new HashMap<Long, List<TagEntity>>();
        for (var tag : owned) byParent.computeIfAbsent(tag.getParentId(), key -> new ArrayList<>()).add(tag);
        return byParent.getOrDefault(null, List.of()).stream().map(root -> view(root, type, byParent)).toList();
    }

    public TagView create(String requestedType, String rawName, Long parentId) {
        long userId = context.userId();
        TagType type = type(requestedType);
        String name = name(rawName);
        try {
            return transaction.execute(status -> {
                if (tags.existsByUserIdAndName(userId, name)) throw conflict();
                var parent = parent(userId, parentId);
                if (parent != null) {
                    var owned = tags.findAllByUserIdOrderById(userId);
                    if (depth(parent, index(owned)) + 1 > MAX_DEPTH) throw depthExceeded();
                }
                var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
                var created = tags.saveAndFlush(new TagEntity(userId, name, parentId, now));
                return new TagView(created.getId(), created.getName(), type, List.of());
            });
        } catch (DataIntegrityViolationException ex) {
            if (tags.existsByUserIdAndName(userId, name)) throw conflict();
            throw ex;
        }
    }

    public TagView rename(long tagId, String requestedType, String rawName) {
        long userId = context.userId();
        TagType type = type(requestedType);
        String name = name(rawName);
        try {
            return transaction.execute(status -> {
                var tag = owned(tagId);
                if (tags.existsByUserIdAndName(userId, name) && !tag.getName().equals(name)) throw conflict();
                tag.rename(name);
                tags.saveAndFlush(tag);
                return persistedView(tag, type);
            });
        } catch (DataIntegrityViolationException ex) {
            if (tags.existsByUserIdAndName(userId, name)) throw conflict();
            throw ex;
        }
    }

    public TagView move(long tagId, String requestedType, Long parentId) {
        long userId = context.userId();
        TagType type = type(requestedType);
        return transaction.execute(status -> {
            var tag = owned(tagId);
            if (Objects.equals(tag.getParentId(), parentId)) return persistedView(tag, type);
            var owned = tags.findAllByUserIdOrderById(userId);
            var byId = index(owned);
            var parent = parentId == null ? null : parent(userId, parentId);
            if (parent != null && createsCycle(tag.getId(), parent, byId)) throw cycle();
            int nextDepth = parent == null ? 1 : depth(parent, byId) + 1;
            if (nextDepth + descendantSpan(tag.getId(), children(owned)) > MAX_DEPTH) throw depthExceeded();
            tag.place(parentId);
            tags.saveAndFlush(tag);
            return persistedView(tag, type);
        });
    }

    @Transactional(readOnly = true)
    public DeletionPreview deletionPreview(long tagId) {
        var tag = owned(tagId);
        if (tags.existsByUserIdAndParentId(context.userId(), tag.getId())) throw hasChildren();
        return new DeletionPreview(directTransactions(context.userId(), tag.getId()));
    }

    public void delete(long tagId, int expectedDirectTransactionCount) {
        long userId = context.userId();
        transaction.executeWithoutResult(status -> {
            var tag = owned(tagId);
            if (tags.existsByUserIdAndParentId(userId, tag.getId())) throw hasChildren();
            int actual = directTransactions(userId, tag.getId());
            if (actual != expectedDirectTransactionCount) throw deleteConflict();
            jdbc.sql("""
                    DELETE tt FROM transaction_tags tt
                    INNER JOIN transactions t ON t.id = tt.transaction_id
                    WHERE tt.tag_id = :tagId AND t.user_id = :userId
                    """).param("tagId", tag.getId()).param("userId", userId).update();
            tags.delete(tag);
            tags.flush();
        });
    }

    private TagType type(String requestedType) {
        try {
            return TagType.valueOf(requestedType);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "标签类型必须为 INCOME 或 EXPENSE");
        }
    }

    private String name(String rawName) {
        try {
            return TagNames.required(rawName);
        } catch (IllegalArgumentException ex) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "标签名称去除首尾空白后不能为空，且最长 100 个字符");
        }
    }

    private ApiFailure conflict() {
        return new ApiFailure(409, "TAG_NAME_CONFLICT", "标签名称已存在");
    }

    private TagView view(TagEntity tag, TagType type, java.util.Map<Long, List<TagEntity>> byParent) {
        var children = byParent.getOrDefault(tag.getId(), List.of()).stream()
                .map(child -> view(child, type, byParent)).toList();
        return new TagView(tag.getId(), tag.getName(), type, children);
    }

    private TagView persistedView(TagEntity tag, TagType type) {
        var owned = tags.findAllByUserIdOrderById(tag.getUserId());
        var byParent = new HashMap<Long, List<TagEntity>>();
        for (var item : owned) byParent.computeIfAbsent(item.getParentId(), key -> new ArrayList<>()).add(item);
        return view(tag, type, byParent);
    }

    private TagEntity parent(long userId, Long parentId) {
        if (parentId == null) return null;
        return tags.findByIdAndUserId(parentId, userId)
                .orElseThrow(() -> new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在"));
    }

    private TagEntity owned(long tagId) {
        return tags.findByIdAndUserId(tagId, context.userId())
                .orElseThrow(() -> new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在"));
    }

    private int directTransactions(long userId, long tagId) {
        Long count = jdbc.sql("""
                SELECT COUNT(*) FROM transaction_tags tt
                INNER JOIN transactions t ON t.id = tt.transaction_id
                WHERE tt.tag_id = :tagId AND t.user_id = :userId
                """).param("tagId", tagId).param("userId", userId).query(Long.class).single();
        return count.intValue();
    }

    private java.util.Map<Long, TagEntity> index(List<TagEntity> owned) {
        var byId = new HashMap<Long, TagEntity>();
        for (var tag : owned) byId.put(tag.getId(), tag);
        return byId;
    }

    private java.util.Map<Long, List<TagEntity>> children(List<TagEntity> owned) {
        var byParent = new HashMap<Long, List<TagEntity>>();
        for (var tag : owned) {
            if (tag.getParentId() != null) byParent.computeIfAbsent(tag.getParentId(), key -> new ArrayList<>()).add(tag);
        }
        return byParent;
    }

    private int depth(TagEntity node, java.util.Map<Long, TagEntity> byId) {
        int depth = 1;
        var seen = new HashSet<Long>();
        seen.add(node.getId());
        Long parentId = node.getParentId();
        while (parentId != null) {
            if (!seen.add(parentId)) throw cycle();
            var parent = byId.get(parentId);
            if (parent == null) throw new ApiFailure(409, "TAG_PARENT_INVALID", "标签父节点不存在");
            depth++;
            parentId = parent.getParentId();
        }
        return depth;
    }

    private int descendantSpan(long id, java.util.Map<Long, List<TagEntity>> children) {
        int span = 0;
        for (var child : children.getOrDefault(id, List.of())) {
            span = Math.max(span, 1 + descendantSpan(child.getId(), children));
        }
        return span;
    }

    private boolean createsCycle(long tagId, TagEntity parent, java.util.Map<Long, TagEntity> byId) {
        var seen = new HashSet<Long>();
        TagEntity current = parent;
        while (current != null) {
            if (current.getId() == tagId) return true;
            if (!seen.add(current.getId())) return true;
            current = current.getParentId() == null ? null : byId.get(current.getParentId());
        }
        return false;
    }

    private ApiFailure hasChildren() {
        return new ApiFailure(409, "TAG_HAS_CHILDREN", "有下级的标签不能删除");
    }

    private ApiFailure deleteConflict() {
        return new ApiFailure(409, "TAG_DELETE_CONFLICT", "直接关联交易数量已变化，请重新确认");
    }

    private ApiFailure cycle() {
        return new ApiFailure(409, "TAG_CYCLE", "标签不能移动到自己或自己的下级");
    }

    private ApiFailure depthExceeded() {
        return new ApiFailure(409, "TAG_DEPTH", "标签最多三级");
    }

    public record OwnedTag(long id, String name) { }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<OwnedTag> requireOwned(List<Long> tagIds, String transactionType) {
        if (tagIds == null || tagIds.isEmpty()) return List.of();
        if (tagIds.stream().anyMatch(Objects::isNull) || new HashSet<>(tagIds).size() != tagIds.size()) {
            throw new ApiFailure(400, "VALIDATION_FAILED", "标签不能为空或重复");
        }
        var found = tags.findAllByUserIdAndIdIn(context.userId(), tagIds);
        if (found.size() != tagIds.size()) throw new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在");
        var byId = found.stream().collect(Collectors.toMap(TagEntity::getId, Function.identity()));
        return tagIds.stream().map(id -> new OwnedTag(id, byId.get(id).getName())).toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireVisible(long tagId) {
        if (!tags.existsByIdAndUserId(tagId, context.userId())) {
            throw new ApiFailure(404, "RESOURCE_NOT_FOUND", "资源不存在");
        }
    }
}
