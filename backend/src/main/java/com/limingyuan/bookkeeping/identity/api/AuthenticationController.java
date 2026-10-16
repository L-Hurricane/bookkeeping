package com.limingyuan.bookkeeping.identity.api;

import com.limingyuan.bookkeeping.identity.application.AuthenticationService;
import com.limingyuan.bookkeeping.common.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {
    private static final Logger log = LoggerFactory.getLogger(AuthenticationController.class);
    private final AuthenticationService service;
    public AuthenticationController(AuthenticationService service) { this.service = service; }

    @PostMapping("/login")
    public ResponseEntity<AuthenticationService.LoginResult> login(@Valid @RequestBody LoginRequest input,
            HttpServletRequest request) {
        var result = service.login(input.username(), input.password());
        log.info("Login succeeded requestId={}", request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(result);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        service.logout();
        log.info("Logout succeeded requestId={}", request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return ResponseEntity.noContent().build();
    }
}
