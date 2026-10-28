package com.limingyuan.bookkeeping.category.domain;

/** Trim only. Case and compatibility forms stay as stored so MySQL collation owns uniqueness. */
public final class CategoryNames {
    public static final int MAX_LENGTH = 100;

    private CategoryNames() { }

    public static String required(String raw) {
        if (raw == null) throw new IllegalArgumentException("Category name is required");
        String name = raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Category name is required");
        if (name.length() > MAX_LENGTH) throw new IllegalArgumentException("Category name is too long");
        return name;
    }
}
