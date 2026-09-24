package com.limingyuan.bookkeeping.tag.domain;

/** Trim only. Case and compatibility forms stay as stored so MySQL collation owns uniqueness. */
public final class TagNames {
    public static final int MAX_LENGTH = 100;

    private TagNames() { }

    public static String required(String raw) {
        if (raw == null) throw new IllegalArgumentException("Tag name is required");
        String name = raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Tag name is required");
        if (name.length() > MAX_LENGTH) throw new IllegalArgumentException("Tag name is too long");
        return name;
    }
}
