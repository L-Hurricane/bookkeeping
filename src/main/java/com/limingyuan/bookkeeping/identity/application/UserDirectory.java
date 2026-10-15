package com.limingyuan.bookkeeping.identity.application;

import com.limingyuan.bookkeeping.identity.persistence.UserRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/** Internal bootstrap contract, not an HTTP user-selection API. */
@Service
public class UserDirectory {
    private final UserRepository users;
    public UserDirectory(UserRepository users) { this.users = users; }
    @Transactional(readOnly = true)
    public List<Long> existingIds() { return users.findAllIds(); }

    @Transactional(readOnly = true)
    public Optional<String> username(long userId) {
        return users.findById(userId).map(user -> user.getUsername());
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForPreparation(long userId) {
        if (users.lockId(userId).isEmpty()) throw new IllegalStateException("User does not exist");
    }
}
