package com.limingyuan.bookkeeping.identity.application;

import com.limingyuan.bookkeeping.identity.TrustedUserContext;
import com.limingyuan.bookkeeping.identity.persistence.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {
    private final UserRepository users;
    private final SessionRepository sessions;
    private final PasswordEncoder passwords;
    private final OpaqueTokens tokens;
    private final Clock clock;
    private final Duration ttl;
    private final TrustedUserContext context;
    private final String dummyPasswordHash;

    public AuthenticationService(UserRepository users, SessionRepository sessions, PasswordEncoder passwords,
            OpaqueTokens tokens, Clock clock, Duration sessionTtl, TrustedUserContext context) {
        this.users = users;
        this.sessions = sessions;
        this.passwords = passwords;
        this.tokens = tokens;
        this.clock = clock;
        this.ttl = sessionTtl;
        this.context = context;
        this.dummyPasswordHash = passwords.encode(tokens.generate());
    }

    public record LoginResult(String token, Instant expiresAt) {
        @Override public String toString() { return "LoginResult[token=REDACTED]"; }
    }

    @Transactional
    public LoginResult login(String username, String password) {
        var user = users.findByUsername(username.trim());
        // Unknown users still incur the same BCrypt work as an incorrect password.
        boolean matches = passwords.matches(password, user.map(UserEntity::getPasswordHash).orElse(dummyPasswordHash));
        if (user.isEmpty() || !matches) throw new AuthenticationFailed();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant expiresAt = now.plus(ttl).truncatedTo(ChronoUnit.MICROS);
        String token = tokens.generate();
        sessions.saveAndFlush(new SessionEntity(user.orElseThrow(), tokens.hash(token), now, expiresAt));
        return new LoginResult(token, expiresAt);
    }

    @Transactional(readOnly = true)
    public Optional<TrustedUserContext.Identity> authenticate(String token) {
        if (!tokens.isWellFormed(token)) return Optional.empty();
        return sessions.findWithUserByTokenHash(tokens.hash(token))
                .filter(session -> !session.isExpired(clock.instant()))
                .map(session -> new TrustedUserContext.Identity(session.getUser().getId(), session.getId()));
    }

    @Transactional
    public void logout() {
        var identity = context.requireIdentity();
        sessions.deleteCurrentSession(identity.sessionId(), identity.userId());
    }
}
