package com.limingyuan.bookkeeping.account.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {
    List<AccountEntity> findAllByUserIdOrderByIdAsc(long userId);
    boolean existsByUserId(long userId);
    boolean existsByUserIdAndName(long userId, String name);
    boolean existsByUserIdAndNameAndIdNot(long userId, String name, long id);
    boolean existsByIdAndUserId(long id, long userId);
    Optional<AccountEntity> findByIdAndUserId(long id, long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id and a.userId = :userId")
    Optional<AccountEntity> lockByIdAndUserId(@Param("id") long id, @Param("userId") long userId);
}
