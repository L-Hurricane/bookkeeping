package com.limingyuan.bookkeeping.tag.domain;

/** Trim only. Case and compatibility forms stay as stored so MySQL collation owns uniqueness. */
public final class TagNames {
    public static final int MAX_LENGTH = 100;
    /** Response label for a stored blank name. required() refuses to persist this text. */
    public static final String UNNAMED_DISPLAY = "（未命名标签）";

    private TagNames() { }

    public static String required(String raw) {
        if (raw == null) throw new IllegalArgumentException("Tag name is required");
        String name = raw.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Tag name is required");
        if (name.length() > MAX_LENGTH) throw new IllegalArgumentException("Tag name is too long");
        if (UNNAMED_DISPLAY.equals(name)) throw new IllegalArgumentException("Tag display marker is not a stored name");
        return name;
    }

    /** A stored name the transaction response can repeat. Whitespace stays; null and "" cannot. */
    public static boolean reportable(String name) {
        return name != null && !name.isEmpty();
    }

    public static String display(String stored) {
        return reportable(stored) ? stored : UNNAMED_DISPLAY;
    }
}
