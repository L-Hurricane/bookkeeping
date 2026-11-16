package com.limingyuan.bookkeeping.account.persistence;

import com.limingyuan.bookkeeping.account.domain.AccountLifecycle;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {
    List<AccountEntity> findAllByUserIdOrderByIdAsc(long userId);
    List<AccountEntity> findAllByUserIdAndLifecycleOrderByIdAsc(long userId, AccountLifecycle lifecycle);

    @Query("""
            select a from AccountEntity a
            where a.userId = :userId and a.lifecycle = :lifecycle
            order by coalesce(a.displayOrder, a.id), a.id
            """)
    List<AccountEntity> findAllByUserIdAndLifecycleOrderByDisplay(
            @Param("userId") long userId, @Param("lifecycle") AccountLifecycle lifecycle);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from AccountEntity a
            where a.userId = :userId and (a.id = :accountId or a.groupId = :groupId)
            order by a.id
            """)
    List<AccountEntity> lockPartition(@Param("userId") long userId, @Param("accountId") long accountId,
            @Param("groupId") long groupId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from AccountEntity a
            where a.userId = :userId and (a.id = :accountId or a.groupId is null)
            order by a.id
            """)
    List<AccountEntity> lockUngroupedPartition(@Param("userId") long userId, @Param("accountId") long accountId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AccountEntity a
            set a.groupId = null, a.displayOrder = null, a.updatedAtUtc = :updatedAtUtc
            where a.userId = :userId and a.groupId = :groupId
            """)
    int clearGroup(@Param("userId") long userId, @Param("groupId") long groupId,
            @Param("updatedAtUtc") LocalDateTime updatedAtUtc);
    boolean existsByUserId(long userId);
    boolean existsByUserIdAndName(long userId, String name);
    boolean existsByUserIdAndNameAndIdNot(long userId, String name, long id);
    boolean existsByIdAndUserId(long id, long userId);
    Optional<AccountEntity> findByIdAndUserId(long id, long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id and a.userId = :userId")
    Optional<AccountEntity> lockByIdAndUserId(@Param("id") long id, @Param("userId") long userId);
}
