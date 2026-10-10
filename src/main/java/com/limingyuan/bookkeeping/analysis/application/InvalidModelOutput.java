package com.limingyuan.bookkeeping.analysis.application;

/** The model text failed the two-field contract. This is not a successful report. */
public final class InvalidModelOutput extends RuntimeException {
    public InvalidModelOutput(String message) {
        super(message);
    }
}
