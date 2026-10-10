package com.limingyuan.bookkeeping.analysis.application;

/** A provider call did not produce a usable two-field result. The message does not carry the request or response body. */
public final class AnalysisGenerationFailed extends RuntimeException {
    public enum Kind {
        AUTHENTICATION,
        ACCESS_DENIED,
        RATE_LIMIT,
        PROVIDER_ERROR,
        TIMEOUT,
        NETWORK,
        EMPTY_RESPONSE,
        INVALID_RESPONSE,
        INVALID_OUTPUT,
        TRUNCATED
    }

    private final Kind kind;
    private final Integer httpStatus;

    public AnalysisGenerationFailed(Kind kind, Integer httpStatus) {
        super(kind.name());
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public Kind kind() { return kind; }

    public Integer httpStatus() { return httpStatus; }
}
