package com.limingyuan.bookkeeping.identity.persistence;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class UserEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String username;
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;
    @Column(name = "created_at_utc", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAtUtc;
    @Column(name = "updated_at_utc", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAtUtc;

    protected UserEntity() { }

    public UserEntity(String username, String passwordHash, LocalDateTime nowUtc) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.createdAtUtc = nowUtc;
        this.updatedAtUtc = nowUtc;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public LocalDateTime getCreatedAtUtc() { return createdAtUtc; }
    public LocalDateTime getUpdatedAtUtc() { return updatedAtUtc; }
}
