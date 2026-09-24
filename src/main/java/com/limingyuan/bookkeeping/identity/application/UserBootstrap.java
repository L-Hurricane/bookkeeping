package com.limingyuan.bookkeeping.identity.application;

import com.limingyuan.bookkeeping.identity.persistence.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class UserBootstrap implements ApplicationRunner {
    private final Environment environment;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public UserBootstrap(Environment environment, UserRepository users, PasswordEncoder passwords,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.environment = environment;
        this.users = users;
        this.passwords = passwords;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (environment.getProperty("bookkeeping.identity.bootstrap.enabled", Boolean.class, false)) {
            ensureUser(environment.getProperty("bookkeeping.identity.bootstrap.username"),
                    environment.getProperty("bookkeeping.identity.bootstrap.password"));
        }
    }

    public void ensureUser(String username, String password) {
        if (username == null || username.trim().isEmpty() || username.trim().length() > 100
                || password == null || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            // Never include rejected configuration values or a binding exception containing a password.
            throw new IllegalArgumentException("Bootstrap requires a username (1-100 characters) and password (1-72 UTF-8 bytes)");
        }
        String normalized = username.trim();
        try {
            transaction.executeWithoutResult(status -> {
                if (!users.existsByUsername(normalized)) {
                    var now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
                    users.saveAndFlush(new UserEntity(normalized, passwords.encode(password), now));
                }
            });
        } catch (DataIntegrityViolationException ex) {
            // A concurrent bootstrap may have won the username unique constraint. Never overwrite it.
            if (!users.existsByUsername(normalized)) throw new IllegalStateException("User bootstrap could not complete");
        }
    }
}
