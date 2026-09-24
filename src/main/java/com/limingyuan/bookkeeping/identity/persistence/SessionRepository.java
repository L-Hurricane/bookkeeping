package com.limingyuan.bookkeeping.identity.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<SessionEntity, Long> {
    @Query("select s from SessionEntity s join fetch s.user where s.tokenHash = :hash")
    Optional<SessionEntity> findWithUserByTokenHash(@Param("hash") byte[] hash);

    @Modifying
    @Query("delete from SessionEntity s where s.id = :id and s.user.id = :userId")
    int deleteCurrentSession(@Param("id") long id, @Param("userId") long userId);
}
