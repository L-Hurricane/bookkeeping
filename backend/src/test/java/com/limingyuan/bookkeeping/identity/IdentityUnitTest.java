package com.limingyuan.bookkeeping.identity;

import com.limingyuan.bookkeeping.identity.api.LoginRequest;
import com.limingyuan.bookkeeping.identity.application.*;
import com.limingyuan.bookkeeping.identity.persistence.SessionEntity;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import static org.assertj.core.api.Assertions.*;

class IdentityUnitTest {
    private final OpaqueTokens tokens = new OpaqueTokens();

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test void passwordUsesSaltedDelegatingBcryptAndVerifies() {
        var encoder = new IdentityConfiguration().passwordEncoder();
        String password = tokens.generate();
        String first = encoder.encode(password);
        assertThat(encoder).isInstanceOf(DelegatingPasswordEncoder.class);
        assertThat(first).startsWith("{bcrypt}$2a$12$").doesNotContain(password);
        assertThat(encoder.encode(password)).isNotEqualTo(first);
        assertThat(encoder.matches(password, first)).isTrue();
        assertThat(encoder.matches(password + "x", first)).isFalse();
    }

    @Test void tokensHave256RandomBitsAndCanonicalEncoding() {
        var generated = new HashSet<String>();
        for (int i = 0; i < 512; i++) {
            String token = tokens.generate();
            assertThat(tokens.isWellFormed(token)).isTrue();
            assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
            assertThat(generated.add(token)).isTrue();
        }
        assertThat(tokens.isWellFormed(null)).isFalse();
        assertThat(tokens.isWellFormed("x".repeat(44))).isFalse();
        assertThat(tokens.isWellFormed("!".repeat(43))).isFalse();
        assertThat(tokens.isWellFormed("A".repeat(42) + "B")).isFalse();
    }

    @Test void tokenHashIsDeterministicSha256() {
        assertThat(HexFormat.of().formatHex(tokens.hash("abc")))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        String token = tokens.generate();
        assertThat(tokens.hash(token)).hasSize(32).isEqualTo(tokens.hash(token))
                .isNotEqualTo(tokens.hash(tokens.generate()));
    }

    @Test void sessionExpiresExactlyAtDeadline() {
        Instant expiry = Instant.parse("2026-09-25T00:00:00Z");
        var session = new SessionEntity(null, new byte[32], expiry.minusSeconds(60), expiry);
        assertThat(session.isExpired(expiry.minusNanos(1))).isFalse();
        assertThat(session.isExpired(expiry)).isTrue();
        assertThat(session.isExpired(expiry.plusSeconds(1))).isTrue();
        assertThat(session.getCreatedAtUtc().toString()).isEqualTo("2026-09-24T23:59");
    }

    @Test void trustedContextRejectsMissingAndUntrustedPrincipals() {
        var context = new TrustedUserContext();
        assertThatThrownBy(context::userId).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated(new TrustedUserContext.Identity(7, 8), null));
        assertThatThrownBy(context::userId).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("request-supplied-user", null, java.util.List.of()));
        assertThatThrownBy(context::userId).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
    }

    @Test void credentialObjectsRedactToStringAndCheckUtf8Limit() {
        String secret = tokens.generate();
        assertThat(new LoginRequest(" user ", secret).username()).isEqualTo("user");
        assertThat(new LoginRequest("user", secret).toString()).doesNotContain(secret, "user");
        assertThat(new AuthenticationService.LoginResult(secret, Instant.now()).toString()).doesNotContain(secret);
        assertThat(new LoginRequest("user", "密".repeat(25)).isPasswordWithinBcryptLimit()).isFalse();
    }
}
