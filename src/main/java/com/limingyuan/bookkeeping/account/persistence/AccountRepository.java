package com.limingyuan.bookkeeping.account.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {
    Optional<AccountEntity> findByUserId(long userId);
    boolean existsByUserId(long userId);
}
