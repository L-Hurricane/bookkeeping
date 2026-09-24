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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TagService {
    private final TagRepository tags;
    private final TrustedUserContext context;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public TagService(TagRepository tags, TrustedUserContext context, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.tags = tags;
        this.context = context;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public record TagView(long id, String name, TagType type) { }

    @Transactional(readOnly = true)
    public List<TagView> list(String requestedType) {
        TagType type = type(requestedType);
        return tags.findAllByUserIdAndTypeOrderById(context.userId(), type).stream().map(this::view).toList();
    }

    public TagView create(String requestedType, String rawName) {
        long userId = context.userId();
        TagType type = type(requestedType);
        String name = name(rawName);
        try {
            return transaction.execute(status -> {
                if (tags.existsByUserIdAndName(userId, name)) throw conflict();
                var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
                return view(tags.saveAndFlush(new TagEntity(userId, type, name, now)));
            });
        } catch (DataIntegrityViolationException ex) {
            if (tags.existsByUserIdAndName(userId, name)) throw conflict();
            throw ex;
        }
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

    private TagView view(TagEntity tag) {
        return new TagView(tag.getId(), tag.getName(), tag.getType());
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
        var expected = TagType.valueOf(transactionType);
        if (found.stream().anyMatch(tag -> tag.getType() != expected)) {
            throw new ApiFailure(409, "TAG_TYPE_MISMATCH", "标签类型与交易类型不一致");
        }
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
