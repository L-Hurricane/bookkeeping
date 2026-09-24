package com.limingyuan.bookkeeping.identity.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    @org.springframework.data.jpa.repository.Query("select u.id from UserEntity u order by u.id")
    java.util.List<Long> findAllIds();

    @org.springframework.data.jpa.repository.Query(value = "SELECT id FROM users WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Long> lockId(@org.springframework.data.repository.query.Param("id") long id);
    Optional<UserEntity> findByUsername(String username);
    boolean existsByUsername(String username);
}
