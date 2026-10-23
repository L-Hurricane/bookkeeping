package com.limingyuan.bookkeeping.account.persistence;

import com.limingyuan.bookkeeping.account.domain.AccountKind;
import com.limingyuan.bookkeeping.account.domain.AccountLifecycle;
import com.limingyuan.bookkeeping.account.domain.AccountNames;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
public class AccountEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(nullable = false, length = 64) private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "VARCHAR(16)")
    private AccountKind kind;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "VARCHAR(16)")
    private AccountLifecycle lifecycle = AccountLifecycle.ACTIVE;
    @Column(nullable = false, columnDefinition = "CHAR(3)") private String currency;
    @Column(name = "opening_balance", nullable = false, precision = 19, scale = 2) private BigDecimal openingBalance;
    @Column(name = "created_time", nullable = false, columnDefinition = "DATETIME(0)") private LocalDateTime createdTime;
    @Column(name = "last_first_save_order", nullable = false) private long lastFirstSaveOrder;
    @Column(name = "updated_at_utc", nullable = false, columnDefinition = "DATETIME(6)") private LocalDateTime updatedAtUtc;
    protected AccountEntity() { }

    /** Legacy initialize path: fixed name 「微信」, kind NORMAL. */
    public AccountEntity(long userId, BigDecimal openingBalance, LocalDateTime createdTime, LocalDateTime updatedAtUtc) {
        this(userId, "微信", AccountKind.NORMAL, openingBalance, createdTime, updatedAtUtc);
    }

    public AccountEntity(long userId, String name, AccountKind kind, BigDecimal openingBalance,
            LocalDateTime createdTime, LocalDateTime updatedAtUtc) {
        this.userId = userId;
        this.name = AccountNames.required(name);
        this.kind = kind;
        this.currency = "CNY";
        this.openingBalance = openingBalance;
        this.createdTime = createdTime;
        this.updatedAtUtc = updatedAtUtc;
        this.lastFirstSaveOrder = 0;
    }

    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public String getName() { return name; }
    public AccountKind getKind() { return kind; }
    public AccountLifecycle getLifecycle() { return lifecycle; }
    public String getCurrency() { return currency; }
    public BigDecimal getOpeningBalance() { return openingBalance; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public long getLastFirstSaveOrder() { return lastFirstSaveOrder; }
    public LocalDateTime getUpdatedAtUtc() { return updatedAtUtc; }

    public void rename(String name, LocalDateTime updatedAtUtc) {
        this.name = AccountNames.required(name);
        this.updatedAtUtc = updatedAtUtc;
    }

    /** Archive keeps every stored amount and history row. */
    public void archive(LocalDateTime updatedAtUtc) {
        this.lifecycle = AccountLifecycle.ARCHIVED;
        this.updatedAtUtc = updatedAtUtc;
    }

    public void restore(LocalDateTime updatedAtUtc) {
        this.lifecycle = AccountLifecycle.ACTIVE;
        this.updatedAtUtc = updatedAtUtc;
    }

    /** Next account-scoped order. Call only while this row is locked in the current transaction. */
    public long allocateNextFirstSaveOrder(LocalDateTime updatedAtUtc) {
        this.lastFirstSaveOrder++;
        this.updatedAtUtc = updatedAtUtc;
        return this.lastFirstSaveOrder;
    }
}
