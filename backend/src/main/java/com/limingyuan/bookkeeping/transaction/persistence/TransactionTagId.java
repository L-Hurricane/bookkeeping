package com.limingyuan.bookkeeping.transaction.persistence;

import java.io.Serializable;
import java.util.Objects;

public class TransactionTagId implements Serializable {
    private long transactionId;
    private long tagId;

    public TransactionTagId() { }

    public TransactionTagId(long transactionId, long tagId) {
        this.transactionId = transactionId;
        this.tagId = tagId;
    }

    @Override public boolean equals(Object other) {
        return other instanceof TransactionTagId id && transactionId == id.transactionId && tagId == id.tagId;
    }

    @Override public int hashCode() { return Objects.hash(transactionId, tagId); }
}
