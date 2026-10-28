package com.limingyuan.bookkeeping.category.persistence;

import com.limingyuan.bookkeeping.category.domain.CategoryType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<CategoryEntity, Long> {
    List<CategoryEntity> findAllByUserIdOrderById(long userId);
    List<CategoryEntity> findAllByUserIdAndTypeOrderById(long userId, CategoryType type);
    Optional<CategoryEntity> findByIdAndUserId(long id, long userId);
    boolean existsByUserIdAndParentId(long userId, Long parentId);
    boolean existsByUserIdAndTypeAndName(long userId, CategoryType type, String name);
}
