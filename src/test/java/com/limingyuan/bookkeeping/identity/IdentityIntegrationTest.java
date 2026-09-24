package com.limingyuan.bookkeeping.identity;

import com.limingyuan.bookkeeping.identity.application.*;
import com.limingyuan.bookkeeping.identity.persistence.*;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@Import(IdentityIntegrationTest.ProbeConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class IdentityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserBootstrap bootstrap;
    @Autowired UserRepository users;
    @Autowired SessionRepository sessions;
    @Autowired OpaqueTokens tokens;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcClient jdbc;
    @Autowired TrustedUserContext context;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    String username;
    String password;
    long userId;
    List<Long> createdUsers = new ArrayList<>();

    @BeforeEach void createUser() {
        username = "test_" + UUID.randomUUID();
        password = tokens.generate();
        bootstrap.ensureUser(username, password);
        userId = users.findByUsername(username).orElseThrow().getId();
        createdUsers.add(userId);
    }

    @AfterEach void removeOnlyOwnedFixtures() {
        for (long id : createdUsers) {
            jdbc.sql("DELETE FROM sessions WHERE user_id = :id").param("id", id).update();
            users.deleteById(id);
        }
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void loginPersistsOnlyHashAndLogoutInvalidatesCurrentSession(CapturedOutput output) throws Exception {
        String token = login(username, password);
        var session = sessions.findWithUserByTokenHash(tokens.hash(token)).orElseThrow();
        assertThat(session.getUser().getId()).isEqualTo(userId);
        assertThat(session.getTokenHash()).hasSize(32).isEqualTo(tokens.hash(token));
        assertThat(session.getExpiresAt()).isAfter(Instant.now());
        assertThat(jdbc.sql("SELECT HEX(token_hash) FROM sessions WHERE id = :id")
                .param("id", session.getId()).query(String.class).single()).doesNotContain(token);
        assertThat(jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'sessions'")
                .query(String.class).list()).containsExactlyInAnyOrder("id", "user_id", "token_hash", "created_at_utc", "expires_at_utc");
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(sessions.findById(session.getId())).isEmpty();
        expectInvalidSession(token);
        assertThat(output.getAll()).doesNotContain(password, token);
    }

    @Test void wrongPasswordAndUnknownUsernameHaveIdenticalPublicError(CapturedOutput output) throws Exception {
        String wrong = tokens.generate();
        var first = loginFailure(username, wrong);
        var second = loginFailure("missing_" + UUID.randomUUID(), wrong);
        assertThat(first.path("error").path("code").asText()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(first.path("error").path("message").asText()).isEqualTo(second.path("error").path("message").asText());
        assertThat(second.path("error").path("code").asText()).isEqualTo("AUTHENTICATION_FAILED");
        assertThat(sessionsForUser()).isZero();
        assertThat(output.getAll()).doesNotContain(password, wrong, username);
    }

    @Test void malformedRequestsReturnSanitizedValidationErrors(CapturedOutput output) throws Exception {
        for (String body : List.of("", "{", "{}", "null", "[]", "{\"username\":\"x\"}",
                "{\"username\":42,\"password\":true}",
                json.writeValueAsString(Map.of("username", username, "password", password, "userId", 99)),
                json.writeValueAsString(Map.of("username", username, "password", "密".repeat(25))),
                json.writeValueAsString(Map.of("username", " ", "password", password)))) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        assertThat(output.getAll()).doesNotContain(password);
    }

    @Test void unsupportedContentTypeDoesNotBecomeAnInternalError() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content(password))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test void missingInvalidAndMalformedTokensAreRejected(CapturedOutput output) throws Exception {
        mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("SESSION_INVALID"));
        String invalid = tokens.generate();
        expectInvalidSession(invalid);
        for (String header : List.of("Bearer", "Basic " + invalid, "Bearer " + invalid + "=", "Bearer a,b")) {
            mvc.perform(post("/api/v1/auth/logout").header("Authorization", header))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("SESSION_INVALID"));
        }
        String valid = login(username, password);
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + valid, "Bearer " + valid))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("token", valid)).param("token", valid))
                .andExpect(status().isUnauthorized());
        assertThat(output.getAll()).doesNotContain(invalid, valid, password);
    }

    @Test void expiredSessionCannotAuthenticateOrLogout() throws Exception {
        String token = login(username, password);
        jdbc.sql("UPDATE sessions SET expires_at_utc = :expiry WHERE token_hash = :hash")
                .param("expiry", java.time.LocalDateTime.of(2000, 1, 1, 0, 0)).param("hash", tokens.hash(token)).update();
        expectInvalidSession(token);
        mvc.perform(get("/api/v1/test/identity").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test void contextUsesSessionOwnerAndNeverLeaksAcrossRequests() throws Exception {
        String otherName = "other_" + UUID.randomUUID();
        bootstrap.ensureUser(otherName, password);
        long otherId = users.findByUsername(otherName).orElseThrow().getId();
        createdUsers.add(otherId);
        String token = login(username, password);
        mvc.perform(get("/api/v1/test/identity").header("Authorization", "Bearer " + token)
                        .param("userId", Long.toString(otherId)).header("X-User-Id", otherId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(userId))
                .andExpect(cookie().doesNotExist("JSESSIONID"));
        assertThatThrownBy(context::userId).isInstanceOf(org.springframework.security.core.AuthenticationException.class);
        mvc.perform(get("/api/v1/test/identity").param("userId", Long.toString(userId)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/test/identity").header("Authorization", "Bearer " + login(otherName, password)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(otherId));
    }

    @Test void logoutLeavesOtherSessionsValid() throws Exception {
        String first = login(username, password);
        String second = login(username, password);
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + first)).andExpect(status().isNoContent());
        expectInvalidSession(first);
        mvc.perform(get("/api/v1/test/identity").header("Authorization", "Bearer " + second))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(userId));
        assertThat(sessionsForUser()).isEqualTo(1);
    }

    @Test void bootstrapIsIdempotentAndNeverResetsPassword() throws Exception {
        var original = users.findById(userId).orElseThrow();
        String hash = original.getPasswordHash();
        assertThat(hash).startsWith("{bcrypt}").doesNotContain(password);
        assertThat(passwords.matches(password, hash)).isTrue();
        bootstrap.ensureUser("  " + username.toUpperCase(Locale.ROOT) + "  ", tokens.generate());
        var repeated = users.findByUsername(username).orElseThrow();
        assertThat(repeated.getId()).isEqualTo(userId);
        assertThat(repeated.getPasswordHash()).isEqualTo(hash);
        assertThat(repeated.getCreatedAtUtc()).isEqualTo(original.getCreatedAtUtc());
        assertThat(repeated.getUpdatedAtUtc()).isEqualTo(original.getUpdatedAtUtc());
        login("  " + username + "  ", password);
    }

    @Test void mysqlEnforcesUsernameAndTokenHashUniqueness() throws Exception {
        assertThatThrownBy(() -> users.saveAndFlush(new UserEntity(username.toUpperCase(Locale.ROOT), passwords.encode(password),
                java.time.LocalDateTime.now(java.time.Clock.systemUTC())))).isInstanceOf(DataIntegrityViolationException.class);
        String token = login(username, password);
        var user = users.findById(userId).orElseThrow();
        assertThatThrownBy(() -> sessions.saveAndFlush(new SessionEntity(user, tokens.hash(token),
                Instant.now(), Instant.now().plusSeconds(60)))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(sessionsForUser()).isEqualTo(1);
    }

    @Test void bootstrapInvalidConfigurationDoesNotExposeSecret(CapturedOutput output) {
        String secret = tokens.generate();
        assertThatThrownBy(() -> bootstrap.ensureUser(" ", secret)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(secret);
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @Test void startupRunnerReadsExternalConfigurationAndIsRepeatable(CapturedOutput output) {
        String name = "startup_" + UUID.randomUUID();
        String secret = tokens.generate();
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("bookkeeping.identity.bootstrap.enabled", "true")
                .withProperty("bookkeeping.identity.bootstrap.username", " " + name + " ")
                .withProperty("bookkeeping.identity.bootstrap.password", secret);
        Instant now = Instant.parse("2026-09-24T03:00:00.123456Z");
        var runner = new UserBootstrap(environment, users, passwords,
                java.time.Clock.fixed(now, java.time.ZoneOffset.ofHours(-7)), transactionManager);
        runner.run(new org.springframework.boot.DefaultApplicationArguments());
        var created = users.findByUsername(name).orElseThrow();
        createdUsers.add(created.getId());
        runner.run(new org.springframework.boot.DefaultApplicationArguments());
        assertThat(users.findByUsername(name).orElseThrow().getId()).isEqualTo(created.getId());
        assertThat(passwords.matches(secret, created.getPasswordHash())).isTrue();
        assertThat(created.getCreatedAtUtc()).isEqualTo(java.time.LocalDateTime.parse("2026-09-24T03:00:00.123456"));
        assertThat(output.getAll()).doesNotContain(secret);
    }

    @Test void startupRunnerIsDisabledUnlessExplicitlyEnabled() {
        String name = "disabled_" + UUID.randomUUID();
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("bookkeeping.identity.bootstrap.username", name)
                .withProperty("bookkeeping.identity.bootstrap.password", password);
        new UserBootstrap(environment, users, passwords, java.time.Clock.systemUTC(), transactionManager)
                .run(new org.springframework.boot.DefaultApplicationArguments());
        assertThat(users.findByUsername(name)).isEmpty();
    }

    private long sessionsForUser() {
        return jdbc.sql("SELECT COUNT(*) FROM sessions WHERE user_id = :id").param("id", userId).query(Long.class).single();
    }

    private String login(String name, String secret) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", name, "password", secret))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(cookie().doesNotExist("JSESSIONID")).andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        var payload = json.readTree(result.getResponse().getContentAsString());
        assertThat(Instant.parse(payload.path("expiresAt").asText())).isAfter(Instant.now());
        String token = payload.path("token").asText();
        assertThat(tokens.isWellFormed(token)).isTrue();
        return token;
    }

    private tools.jackson.databind.JsonNode loginFailure(String name, String secret) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", name, "password", secret))))
                .andExpect(status().isUnauthorized()).andReturn();
        var payload = json.readTree(result.getResponse().getContentAsString());
        assertThat(payload.path("error").path("requestId").asText()).isEqualTo(result.getResponse().getHeader("X-Request-Id"));
        return payload;
    }

    private void expectInvalidSession(String token) throws Exception {
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("SESSION_INVALID"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean IdentityProbe identityProbe(TrustedUserContext context) { return new IdentityProbe(context); }
    }

    // Test-only endpoint: never packaged in the application or added to the API baseline.
    @RestController
    static class IdentityProbe {
        private final TrustedUserContext context;
        IdentityProbe(TrustedUserContext context) { this.context = context; }
        @GetMapping("/api/v1/test/identity")
        Map<String, Long> current() { return Map.of("userId", context.userId()); }
    }
}
