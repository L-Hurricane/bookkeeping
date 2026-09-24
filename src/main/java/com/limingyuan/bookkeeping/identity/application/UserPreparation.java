package com.limingyuan.bookkeeping.identity.application;

/** Synchronous preparation hook inside the user bootstrap transaction; implementations must be idempotent. */
public interface UserPreparation {
    void prepare(long userId);
}
