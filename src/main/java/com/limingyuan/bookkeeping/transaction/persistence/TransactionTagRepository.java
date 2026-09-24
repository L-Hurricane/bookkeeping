package com.limingyuan.bookkeeping.transaction.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionTagRepository extends JpaRepository<TransactionTagEntity, TransactionTagId> {
    List<TransactionTagEntity> findAllByTransactionIdOrderByTagId(long transactionId);
    void deleteByTransactionId(long transactionId);
}
