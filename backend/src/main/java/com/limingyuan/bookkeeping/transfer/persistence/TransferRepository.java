package com.limingyuan.bookkeeping.transfer.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRepository extends JpaRepository<TransferEntity, Long> {
    Optional<TransferEntity> findByIdAndUserId(long id, long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransferEntity t where t.id = :id and t.userId = :userId")
    Optional<TransferEntity> lockByIdAndUserId(@Param("id") long id, @Param("userId") long userId);

    @Query("""
            select t from TransferEntity t
            where t.userId = :userId and (t.fromAccountId = :accountId or t.toAccountId = :accountId)
            order by t.id
            """)
    List<TransferEntity> findAllTouchingAccount(@Param("userId") long userId, @Param("accountId") long accountId);

    @Modifying
    @Query("delete from TransferEntity t where t.userId = :userId and t.id in :ids")
    void deleteAllByUserIdAndIdIn(@Param("userId") long userId, @Param("ids") Collection<Long> ids);
}
