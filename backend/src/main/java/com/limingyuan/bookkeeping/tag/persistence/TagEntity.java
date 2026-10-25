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
    @Column(name = "created_at_utc", nullable = false, columnDefinition = "DATETIME(6)") private LocalDateTime createdAtUtc;

    protected TagEntity() { }

    public TagEntity(long userId, String name, LocalDateTime createdAtUtc) {
        this.userId = userId;
        this.name = TagNames.required(name);
        this.createdAtUtc = createdAtUtc;
    }

    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public String getName() { return name; }
    public LocalDateTime getCreatedAtUtc() { return createdAtUtc; }
}
