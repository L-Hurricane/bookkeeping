package com.limingyuan.bookkeeping.common.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class CommonExceptionHandler {
    private final ApiErrors errors;
    public CommonExceptionHandler(ApiErrors errors) { this.errors = errors; }

    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<ApiErrors.ErrorResponse> failure(ApiFailure ex, HttpServletRequest request) {
        var details = ex.details().isEmpty() ? null : ex.details();
        var response = ResponseEntity.status(ex.status()).header("Cache-Control", "no-store");
        ex.headers().forEach(response::header);
        return response.body(errors.body(request, ex.code(), ex.getMessage(), details));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiErrors.ErrorResponse> invalid(HttpServletRequest request) {
        return response(request, 400, "VALIDATION_FAILED", "请求参数不合法");
    }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiErrors.ErrorResponse> media(HttpServletRequest request) {
        return response(request, 415, "VALIDATION_FAILED", "请求格式必须为 JSON");
    }
    private ResponseEntity<ApiErrors.ErrorResponse> response(HttpServletRequest request, int status, String code, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(errors.body(request, code, message));
    }
}
