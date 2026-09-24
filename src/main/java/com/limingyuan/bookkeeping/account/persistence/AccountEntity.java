package com.limingyuan.bookkeeping.account.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
public class AccountEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(nullable = false, length = 64) private String name;
    @Column(nullable = false, columnDefinition = "CHAR(3)") private String currency;
    @Column(name = "opening_balance", nullable = false, precision = 19, scale = 2) private BigDecimal openingBalance;
    @Column(name = "created_time", nullable = false, columnDefinition = "DATETIME(0)") private LocalDateTime createdTime;
    @Column(name = "last_first_save_order", nullable = false) private long lastFirstSaveOrder;
    @Column(name = "updated_at_utc", nullable = false, columnDefinition = "DATETIME(6)") private LocalDateTime updatedAtUtc;
    protected AccountEntity() { }
    public AccountEntity(long userId, BigDecimal openingBalance, LocalDateTime createdTime, LocalDateTime updatedAtUtc) {
        this.userId = userId;
        this.name = "微信";
        this.currency = "CNY";
        this.openingBalance = openingBalance;
        this.createdTime = createdTime;
        this.updatedAtUtc = updatedAtUtc;
        this.lastFirstSaveOrder = 0;
    }
    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public String getName() { return name; }
    public String getCurrency() { return currency; }
    public BigDecimal getOpeningBalance() { return openingBalance; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public long getLastFirstSaveOrder() { return lastFirstSaveOrder; }
    public LocalDateTime getUpdatedAtUtc() { return updatedAtUtc; }

    /** Next account-scoped order. Call only while this row is locked in the current transaction. */
    public long allocateNextFirstSaveOrder(LocalDateTime updatedAtUtc) {
        this.lastFirstSaveOrder++;
        this.updatedAtUtc = updatedAtUtc;
        return this.lastFirstSaveOrder;
    }
}
