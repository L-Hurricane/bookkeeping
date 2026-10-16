package com.limingyuan.bookkeeping.account.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {
    Optional<AccountEntity> findByUserId(long userId);
    boolean existsByUserId(long userId);
    boolean existsByIdAndUserId(long id, long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountEntity a where a.id = :id and a.userId = :userId")
    Optional<AccountEntity> lockByIdAndUserId(@Param("id") long id, @Param("userId") long userId);
}
