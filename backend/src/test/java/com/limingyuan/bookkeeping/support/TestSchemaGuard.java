package com.limingyuan.bookkeeping.support;

import java.util.Set;

/**
 * Stops ordinary integration tests before they use any schema except an allowlisted test one.
 * Fixed full names only — not a prefix or pattern match.
 */
public final class TestSchemaGuard {
    public static final String TEST_SCHEMA = "bookkeeping_test";
    /** S1-T03 dedicated ordinary test library; fixed name only. */
    public static final String S1_T03_TEST_SCHEMA = "bk_s1t03_6928fd20_test";

    private static final Set<String> ALLOWED_SCHEMAS = Set.of(
            TEST_SCHEMA,
            S1_T03_TEST_SCHEMA);

    private TestSchemaGuard() { }

    public static void requireAllowedSchema(String schema) {
        if (!ALLOWED_SCHEMAS.contains(schema)) {
            throw new IllegalStateException("test refused schema " + schema);
        }
    }

    public static String databaseName(String url) {
        if (url == null || !url.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException("test refused an unrecognized database url");
        }
        int slash = url.indexOf('/', "jdbc:mysql://".length());
        if (slash < 0 || slash == url.length() - 1) {
            throw new IllegalStateException("test refused a url without a schema name");
        }
        int query = url.indexOf('?', slash + 1);
        String name = query < 0 ? url.substring(slash + 1) : url.substring(slash + 1, query);
        if (name.isEmpty() || name.indexOf('/') >= 0) {
            throw new IllegalStateException("test refused a url without a schema name");
        }
        return name;
    }
}
