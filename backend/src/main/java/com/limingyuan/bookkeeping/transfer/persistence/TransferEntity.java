package com.limingyuan.bookkeeping.transfer.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transfers")
public class TransferEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(name = "from_account_id", nullable = false) private long fromAccountId;
    @Column(name = "to_account_id", nullable = false) private long toAccountId;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(name = "from_business_time", nullable = false, columnDefinition = "DATETIME(0)") private LocalDateTime fromBusinessTime;
    @Column(name = "to_business_time", nullable = false, columnDefinition = "DATETIME(0)") private LocalDateTime toBusinessTime;
    @Column(name = "first_saved_at_utc", nullable = false, columnDefinition = "DATETIME(6)") private LocalDateTime firstSavedAtUtc;
    @Column(name = "first_save_order", nullable = false) private long firstSaveOrder;
    @Column(name = "updated_at_utc", nullable = false, columnDefinition = "DATETIME(6)") private LocalDateTime updatedAtUtc;

    protected TransferEntity() { }

    public TransferEntity(long userId, long fromAccountId, long toAccountId, BigDecimal amount,
            LocalDateTime fromBusinessTime, LocalDateTime toBusinessTime, LocalDateTime firstSavedAtUtc, long firstSaveOrder) {
        this.userId = userId;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.fromBusinessTime = fromBusinessTime;
        this.toBusinessTime = toBusinessTime;
        this.firstSavedAtUtc = firstSavedAtUtc;
        this.firstSaveOrder = firstSaveOrder;
        this.updatedAtUtc = firstSavedAtUtc;
    }

    /** Amount, accounts, and both business times can change. Identity and first-save facts stay. */
    public void revise(long fromAccountId, long toAccountId, BigDecimal amount, LocalDateTime fromBusinessTime,
            LocalDateTime toBusinessTime, LocalDateTime updatedAtUtc) {
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.fromBusinessTime = fromBusinessTime;
        this.toBusinessTime = toBusinessTime;
        this.updatedAtUtc = updatedAtUtc;
    }

    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public long getFromAccountId() { return fromAccountId; }
    public long getToAccountId() { return toAccountId; }
    public BigDecimal getAmount() { return amount; }
    public LocalDateTime getFromBusinessTime() { return fromBusinessTime; }
    public LocalDateTime getToBusinessTime() { return toBusinessTime; }
    public LocalDateTime getFirstSavedAtUtc() { return firstSavedAtUtc; }
    public long getFirstSaveOrder() { return firstSaveOrder; }
    public LocalDateTime getUpdatedAtUtc() { return updatedAtUtc; }
}
