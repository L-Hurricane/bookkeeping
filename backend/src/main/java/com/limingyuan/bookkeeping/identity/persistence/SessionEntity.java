package com.limingyuan.bookkeeping.identity.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "sessions")
public class SessionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;
    @Column(name = "token_hash", nullable = false, columnDefinition = "BINARY(32)")
    private byte[] tokenHash;
    @Column(name = "created_at_utc", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAtUtc;
    @Column(name = "expires_at_utc", nullable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime expiresAtUtc;

    protected SessionEntity() { }

    public SessionEntity(UserEntity user, byte[] tokenHash, Instant now, Instant expiresAt) {
        this.user = user;
        this.tokenHash = tokenHash.clone();
        this.createdAtUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        this.expiresAtUtc = LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC);
    }

    public boolean isExpired(Instant now) {
        return !expiresAtUtc.toInstant(ZoneOffset.UTC).isAfter(now);
    }
    public Long getId() { return id; }
    public UserEntity getUser() { return user; }
    public byte[] getTokenHash() { return tokenHash.clone(); }
    public LocalDateTime getCreatedAtUtc() { return createdAtUtc; }
    public Instant getExpiresAt() { return expiresAtUtc.toInstant(ZoneOffset.UTC); }
}
