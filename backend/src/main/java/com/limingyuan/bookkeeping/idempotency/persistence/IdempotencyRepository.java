package com.limingyuan.bookkeeping.idempotency.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {
    Optional<IdempotencyRecord> findByUserIdAndScopeAndOperationId(long userId, String scope, String operationId);
}
