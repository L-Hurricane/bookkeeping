package com.limingyuan.bookkeeping.identity.api;

import com.limingyuan.bookkeeping.common.api.ApiErrors;
import com.limingyuan.bookkeeping.common.api.RequestIdFilter;

import com.limingyuan.bookkeeping.identity.application.AuthenticationFailed;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = AuthenticationController.class)
public class AuthenticationExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(AuthenticationExceptionHandler.class);
    private final ApiErrors errors;
    public AuthenticationExceptionHandler(ApiErrors errors) { this.errors = errors; }

    @ExceptionHandler(AuthenticationFailed.class)
    ResponseEntity<ApiErrors.ErrorResponse> loginFailed(HttpServletRequest request) {
        log.info("Authentication failed requestId={}", request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return response(request, 401, "AUTHENTICATION_FAILED", "用户名或密码错误");
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiErrors.ErrorResponse> sessionInvalid(HttpServletRequest request) {
        return response(request, 401, "SESSION_INVALID", "会话无效或已过期");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ApiErrors.ErrorResponse> invalidRequest(HttpServletRequest request) {
        // Never serialize rejected values, parser messages, or request DTOs.
        return response(request, 400, "VALIDATION_FAILED", "请求参数不合法");
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrors.ErrorResponse> unsupportedMediaType(HttpServletRequest request) {
        return response(request, 415, "VALIDATION_FAILED", "请求格式必须为 JSON");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrors.ErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
        // Exception messages/stack traces may contain SQL values or parser input.
        log.error("Request failed type={} requestId={}", exception.getClass().getSimpleName(),
                request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return response(request, 500, "INTERNAL_ERROR", "服务暂时不可用");
    }

    private ResponseEntity<ApiErrors.ErrorResponse> response(HttpServletRequest request, int status,
            String code, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(errors.body(request, code, message));
    }
}
