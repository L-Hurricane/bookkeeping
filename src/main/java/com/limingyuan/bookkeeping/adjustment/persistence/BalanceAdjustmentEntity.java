package com.limingyuan.bookkeeping.adjustment.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "balance_adjustments")
public class BalanceAdjustmentEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(name = "account_id", nullable = false) private long accountId;
    @Column(name = "adjustment_amount", nullable = false, precision = 19, scale = 2) private BigDecimal adjustmentAmount;
    @Column(name = "business_time", nullable = false, columnDefinition = "DATETIME(0)") private LocalDateTime businessTime;
    @Column(name = "first_saved_at_utc", nullable = false, columnDefinition = "DATETIME(6)") private LocalDateTime firstSavedAtUtc;
    @Column(name = "first_save_order", nullable = false) private long firstSaveOrder;
    @Column(columnDefinition = "TEXT") private String remark;

    protected BalanceAdjustmentEntity() { }

    public BalanceAdjustmentEntity(long userId, long accountId, BigDecimal adjustmentAmount, LocalDateTime businessTime,
            LocalDateTime firstSavedAtUtc, long firstSaveOrder, String remark) {
        this.userId = userId;
        this.accountId = accountId;
        this.adjustmentAmount = adjustmentAmount;
        this.businessTime = businessTime;
        this.firstSavedAtUtc = firstSavedAtUtc;
        this.firstSaveOrder = firstSaveOrder;
        this.remark = remark;
    }

    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public long getAccountId() { return accountId; }
    public BigDecimal getAdjustmentAmount() { return adjustmentAmount; }
    public LocalDateTime getBusinessTime() { return businessTime; }
    public long getFirstSaveOrder() { return firstSaveOrder; }
    public String getRemark() { return remark; }
}
