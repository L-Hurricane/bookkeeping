package com.limingyuan.bookkeeping.common.api;

public class ApiFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final java.util.List<ApiErrors.Detail> details;
    public ApiFailure(int status, String code, String message) {
        this(status, code, message, java.util.List.of());
    }
    public ApiFailure(int status, String code, String message, java.util.List<ApiErrors.Detail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }
    public int status() { return status; }
    public String code() { return code; }
    public java.util.List<ApiErrors.Detail> details() { return details; }
}
