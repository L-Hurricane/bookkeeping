package com.limingyuan.bookkeeping.transaction.persistence;

import com.limingyuan.bookkeeping.transaction.domain.TransactionType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
public class TransactionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(name = "account_id", nullable = false) private long accountId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16, columnDefinition = "VARCHAR(16)") private TransactionType type;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(length = 255) private String name;
    @Column(name = "category_id") private Long categoryId;
    @Column(name = "business_time", nullable = false, columnDefinition = "DATETIME(0)") private LocalDateTime businessTime;
    @Column(name = "first_saved_at_utc", nullable = false, columnDefinition = "DATETIME(6)") private LocalDateTime firstSavedAtUtc;
    @Column(name = "first_save_order", nullable = false) private long firstSaveOrder;
    @Column(name = "updated_at_utc", nullable = false, columnDefinition = "DATETIME(6)") private LocalDateTime updatedAtUtc;
    @Column(columnDefinition = "TEXT") private String remark;

    protected TransactionEntity() { }

    public TransactionEntity(long userId, long accountId, TransactionType type, BigDecimal amount, String name,
            Long categoryId, LocalDateTime businessTime, LocalDateTime firstSavedAtUtc, long firstSaveOrder, String remark) {
        this.userId = userId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.name = name;
        this.categoryId = categoryId;
        this.businessTime = businessTime;
        this.firstSavedAtUtc = firstSavedAtUtc;
        this.firstSaveOrder = firstSaveOrder;
        this.updatedAtUtc = firstSavedAtUtc;
        this.remark = remark;
    }

    public void edit(BigDecimal amount, String name, Long categoryId, LocalDateTime businessTime, String remark,
            LocalDateTime updatedAtUtc) {
        this.amount = amount;
        this.name = name;
        this.categoryId = categoryId;
        this.businessTime = businessTime;
        this.remark = remark;
        this.updatedAtUtc = updatedAtUtc;
    }

    /** Cross-account reassignment keeps type, first saved time, and first save order. */
    public void reassignAccount(long accountId) {
        this.accountId = accountId;
    }

    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public long getAccountId() { return accountId; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getName() { return name; }
    public Long getCategoryId() { return categoryId; }
    public LocalDateTime getBusinessTime() { return businessTime; }
    public LocalDateTime getFirstSavedAtUtc() { return firstSavedAtUtc; }
    public long getFirstSaveOrder() { return firstSaveOrder; }
    public LocalDateTime getUpdatedAtUtc() { return updatedAtUtc; }
    public String getRemark() { return remark; }
}
