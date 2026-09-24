package com.limingyuan.bookkeeping.identity;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The sole current-user entry point for downstream modules; never populated from request userId. */
@Component
public class TrustedUserContext {
    /** Server-created principal, carrying no password or raw token. */
    public record Identity(long userId, long sessionId) { }

    public Identity requireIdentity() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Identity identity)) {
            throw new org.springframework.security.authentication.AuthenticationCredentialsNotFoundException(
                    "Trusted user context is unavailable");
        }
        return identity;
    }

    public long userId() { return requireIdentity().userId(); }
}
