package com.limingyuan.bookkeeping.tag.persistence;

import com.limingyuan.bookkeeping.tag.domain.TagNames;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tags")
public class TagEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(nullable = false, length = 100) private String name;
    @Column(name = "parent_id") private Long parentId;
    @Column(name = "created_at_utc", nullable = false, columnDefinition = "DATETIME(6)") private LocalDateTime createdAtUtc;

    protected TagEntity() { }

    public TagEntity(long userId, String name, Long parentId, LocalDateTime createdAtUtc) {
        this.userId = userId;
        this.name = TagNames.required(name);
        this.parentId = parentId;
        this.createdAtUtc = createdAtUtc;
    }

    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public String getName() { return name; }
    public Long getParentId() { return parentId; }
    public LocalDateTime getCreatedAtUtc() { return createdAtUtc; }
    public void rename(String name) { this.name = name; }
    public void place(Long parentId) { this.parentId = parentId; }
}
