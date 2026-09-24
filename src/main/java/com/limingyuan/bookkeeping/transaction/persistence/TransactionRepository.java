package com.limingyuan.bookkeeping.transaction.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<TransactionEntity, Long> {
    Optional<TransactionEntity> findByIdAndAccountIdAndUserId(long id, long accountId, long userId);
}
