package com.limingyuan.bookkeeping.adjustment.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BalanceAdjustmentRepository extends JpaRepository<BalanceAdjustmentEntity, Long> {
    Optional<BalanceAdjustmentEntity> findByIdAndAccountIdAndUserId(long id, long accountId, long userId);
}
