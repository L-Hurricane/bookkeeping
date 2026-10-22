package com.limingyuan.bookkeeping.account.domain;

/** Trim only. Case and compatibility forms stay as stored so MySQL collation owns uniqueness. */
public final class AccountNames {
    public static final int MAX_LENGTH = 64;

    private AccountNames() { }

    public static String required(String raw) {
        if (raw == null) throw new IllegalArgumentException("Account name is required");
        String name = raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Account name is required");
        if (name.length() > MAX_LENGTH) throw new IllegalArgumentException("Account name is too long");
        return name;
    }
}
