package com.limingyuan.bookkeeping.identity.application;

public class AuthenticationFailed extends RuntimeException {
    public AuthenticationFailed() { super("Authentication failed"); }
}
