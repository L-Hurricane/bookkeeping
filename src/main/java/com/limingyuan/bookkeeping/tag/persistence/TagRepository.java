package com.limingyuan.bookkeeping.tag.persistence;

import com.limingyuan.bookkeeping.tag.domain.TagType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<TagEntity, Long> {
    List<TagEntity> findAllByUserIdAndTypeOrderById(long userId, TagType type);
    boolean existsByUserIdAndName(long userId, String name);
    boolean existsByIdAndUserId(long id, long userId);
    List<TagEntity> findAllByUserIdAndIdIn(long userId, Collection<Long> ids);
}
