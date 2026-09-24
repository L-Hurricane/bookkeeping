package com.limingyuan.bookkeeping.transaction.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "transaction_tags")
@IdClass(TransactionTagId.class)
public class TransactionTagEntity {
    @Id @Column(name = "transaction_id") private long transactionId;
    @Id @Column(name = "tag_id") private long tagId;

    protected TransactionTagEntity() { }

    public TransactionTagEntity(long transactionId, long tagId) {
        this.transactionId = transactionId;
        this.tagId = tagId;
    }

    public long getTransactionId() { return transactionId; }
    public long getTagId() { return tagId; }
}
