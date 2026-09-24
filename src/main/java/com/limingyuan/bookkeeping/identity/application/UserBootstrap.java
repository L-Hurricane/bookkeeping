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
@org.springframework.core.annotation.Order(0)
public class UserBootstrap implements ApplicationRunner {
    private final Environment environment;
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final java.util.List<UserPreparation> preparations;

    public UserBootstrap(Environment environment, UserRepository users, PasswordEncoder passwords,
            Clock clock, PlatformTransactionManager transactionManager, java.util.List<UserPreparation> preparations) {
        this.environment = environment;
        this.users = users;
        this.passwords = passwords;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.preparations = java.util.List.copyOf(preparations);
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
                long userId = users.findByUsername(normalized).orElseThrow().getId();
                preparations.forEach(preparation -> preparation.prepare(userId));
            });
        } catch (DataIntegrityViolationException ex) {
            // A concurrent bootstrap may have won the username unique constraint. Never overwrite it.
            if (!users.existsByUsername(normalized)) throw new IllegalStateException("User bootstrap could not complete");
            // Retry preparation in a fresh transaction; never swallow preparation constraint failures.
            transaction.executeWithoutResult(status -> {
                long userId = users.findByUsername(normalized).orElseThrow().getId();
                preparations.forEach(preparation -> preparation.prepare(userId));
            });
        }
    }
}
