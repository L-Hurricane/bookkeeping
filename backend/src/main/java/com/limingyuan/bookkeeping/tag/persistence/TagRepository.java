package com.limingyuan.bookkeeping.tag.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<TagEntity, Long> {
    List<TagEntity> findAllByUserIdOrderById(long userId);
    boolean existsByUserIdAndName(long userId, String name);
    boolean existsByUserIdAndParentId(long userId, Long parentId);
    boolean existsByIdAndUserId(long id, long userId);
    java.util.Optional<TagEntity> findByIdAndUserId(long id, long userId);
    List<TagEntity> findAllByUserIdAndIdIn(long userId, Collection<Long> ids);
}
