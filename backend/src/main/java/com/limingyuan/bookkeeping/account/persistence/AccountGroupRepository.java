package com.limingyuan.bookkeeping.account.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountGroupRepository extends JpaRepository<AccountGroupEntity, Long> {
    List<AccountGroupEntity> findAllByUserIdOrderByDisplayOrderAscIdAsc(long userId);
    boolean existsByUserIdAndName(long userId, String name);
    boolean existsByUserIdAndNameAndIdNot(long userId, String name, long id);

    @Query("select coalesce(max(g.displayOrder), 0) from AccountGroupEntity g where g.userId = :userId")
    long maxDisplayOrder(@Param("userId") long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from AccountGroupEntity g where g.id = :id and g.userId = :userId")
    Optional<AccountGroupEntity> lockByIdAndUserId(@Param("id") long id, @Param("userId") long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from AccountGroupEntity g where g.userId = :userId order by g.id")
    List<AccountGroupEntity> lockAllByUserId(@Param("userId") long userId);
}
