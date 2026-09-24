package com.limingyuan.bookkeeping.category.application;

import com.limingyuan.bookkeeping.category.domain.CategoryType;
import com.limingyuan.bookkeeping.category.persistence.*;
import com.limingyuan.bookkeeping.common.api.ApiFailure;
import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService {
    private final CategoryRepository categories;
    private final TrustedUserContext context;
    public CategoryService(CategoryRepository categories, TrustedUserContext context) {
        this.categories = categories; this.context = context;
    }
    public record CategoryView(long id, String name, CategoryType type, boolean bookable, List<CategoryView> children) { }
    @Transactional(readOnly = true)
    public List<CategoryView> list(String requestedType) {
        CategoryType type;
        try { type = CategoryType.valueOf(requestedType); }
        catch (IllegalArgumentException ex) { throw new ApiFailure(400, "VALIDATION_FAILED", "分类类型必须为 INCOME 或 EXPENSE"); }
        var owned = categories.findAllByUserIdAndTypeOrderById(context.userId(), type);
        return owned.stream().filter(c -> c.getParentId() == null).map(root -> {
            var children = owned.stream().filter(c -> Objects.equals(c.getParentId(), root.getId()))
                    .map(c -> new CategoryView(c.getId(), c.getName(), c.getType(), true, List.of())).toList();
            return new CategoryView(root.getId(), root.getName(), root.getType(), children.isEmpty(), children);
        }).toList();
    }
}
