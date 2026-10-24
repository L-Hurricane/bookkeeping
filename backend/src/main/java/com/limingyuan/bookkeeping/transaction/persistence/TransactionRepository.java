package com.limingyuan.bookkeeping.transaction.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<TransactionEntity, Long> {
    Optional<TransactionEntity> findByIdAndAccountIdAndUserId(long id, long accountId, long userId);

    @Query("select t.id from TransactionEntity t where t.accountId = :accountId and t.userId = :userId order by t.id")
    List<Long> findAllIdsByAccountIdAndUserId(@Param("accountId") long accountId, @Param("userId") long userId);

    @Modifying(flushAutomatically = true)
    @Query("delete from TransactionEntity t where t.accountId = :accountId and t.userId = :userId and t.id in :ids")
    void deleteByAccountIdAndUserIdAndIdIn(@Param("accountId") long accountId, @Param("userId") long userId,
            @Param("ids") java.util.Collection<Long> ids);
}
