package com.limingyuan.bookkeeping.adjustment.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BalanceAdjustmentRepository extends JpaRepository<BalanceAdjustmentEntity, Long> {
    Optional<BalanceAdjustmentEntity> findByIdAndAccountIdAndUserId(long id, long accountId, long userId);

    @Query("select a.id from BalanceAdjustmentEntity a where a.accountId = :accountId and a.userId = :userId order by a.id")
    List<Long> findAllIdsByAccountIdAndUserId(@Param("accountId") long accountId, @Param("userId") long userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from BalanceAdjustmentEntity a where a.accountId = :accountId and a.userId = :userId and a.id in :ids")
    void deleteByAccountIdAndUserIdAndIdIn(@Param("accountId") long accountId, @Param("userId") long userId,
            @Param("ids") java.util.Collection<Long> ids);
}
