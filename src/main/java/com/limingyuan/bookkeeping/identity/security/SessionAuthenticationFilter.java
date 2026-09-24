package com.limingyuan.bookkeeping.identity.security;

import com.limingyuan.bookkeeping.common.api.ApiErrors;
import com.limingyuan.bookkeeping.common.api.RequestIdFilter;
import com.limingyuan.bookkeeping.identity.application.AuthenticationService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionAuthenticationFilter extends OncePerRequestFilter {
    private static final Pattern BEARER = Pattern.compile("(?i:Bearer) ([A-Za-z0-9_-]{43})");
    private static final Logger log = LoggerFactory.getLogger(SessionAuthenticationFilter.class);
    private final AuthenticationService service;
    private final ApiErrors errors;

    public SessionAuthenticationFilter(AuthenticationService service, ApiErrors errors) {
        this.service = service;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return ("POST".equals(request.getMethod()) && "/api/v1/auth/login".equals(path))
                || ("GET".equals(request.getMethod()) && "/actuator/health".equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() != 1) { invalid(request, response); return; }
        var matcher = BEARER.matcher(headers.getFirst());
        if (!matcher.matches()) { invalid(request, response); return; }
        com.limingyuan.bookkeeping.identity.TrustedUserContext.Identity identity;
        try {
            identity = service.authenticate(matcher.group(1)).orElse(null);
        } catch (RuntimeException ex) {
            log.error("Session lookup failed type={} requestId={}", ex.getClass().getSimpleName(),
                    request.getAttribute(RequestIdFilter.ATTRIBUTE));
            errors.write(request, response, 500, "INTERNAL_ERROR", "服务暂时不可用");
            return;
        }
        if (identity == null) { invalid(request, response); return; }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of()));
        SecurityContextHolder.setContext(context);
        try { chain.doFilter(request, response); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private void invalid(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        log.info("Session rejected requestId={}", request.getAttribute(RequestIdFilter.ATTRIBUTE));
        errors.write(request, response, 401, "SESSION_INVALID", "会话无效或已过期");
    }
}
