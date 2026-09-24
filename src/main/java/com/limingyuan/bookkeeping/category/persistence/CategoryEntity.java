package com.limingyuan.bookkeeping.category.persistence;

import com.limingyuan.bookkeeping.category.domain.CategoryType;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "categories")
public class CategoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16, columnDefinition = "VARCHAR(16)") private CategoryType type;
    @Column(nullable = false, length = 100) private String name;
    @Column(name = "parent_id") private Long parentId;
    @Column(name = "created_at_utc", nullable = false, columnDefinition = "DATETIME(6)") private LocalDateTime createdAtUtc;
    protected CategoryEntity() { }
    public CategoryEntity(long userId, CategoryType type, String name, Long parentId, LocalDateTime now) {
        this.userId = userId; this.type = type; this.name = name.trim();
        if (this.name.isEmpty()) throw new IllegalArgumentException("Category name is required");
        this.parentId = parentId; this.createdAtUtc = now;
    }
    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public CategoryType getType() { return type; }
    public String getName() { return name; }
    public Long getParentId() { return parentId; }
}
