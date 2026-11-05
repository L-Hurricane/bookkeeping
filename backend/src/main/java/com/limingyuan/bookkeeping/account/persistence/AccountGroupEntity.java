package com.limingyuan.bookkeeping.account.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "account_groups")
public class AccountGroupEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(nullable = false, length = 64) private String name;
    @Column(name = "display_order", nullable = false) private long displayOrder;

    protected AccountGroupEntity() { }

    public AccountGroupEntity(long userId, String name, long displayOrder) {
        this.userId = userId;
        this.name = name;
        this.displayOrder = displayOrder;
    }

    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public String getName() { return name; }
    public long getDisplayOrder() { return displayOrder; }

    public void rename(String name) { this.name = name; }
    public void setDisplayOrder(long displayOrder) { this.displayOrder = displayOrder; }
}
