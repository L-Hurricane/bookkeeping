package com.limingyuan.bookkeeping.identity;

import java.util.Set;

/** Stops the migration test before it can clean any schema except an allowlisted disposable one. */
final class MigrationSchemaGuard {
    static final String DISPOSABLE_SCHEMA = "bookkeeping_migration_test";
    /** S1-T03 dedicated migration library; fixed name only — not a pattern. */
    static final String S1_T03_DISPOSABLE_SCHEMA = "bk_s1t03_6928fd20_migration";

    private static final Set<String> DISPOSABLE_SCHEMAS = Set.of(
            DISPOSABLE_SCHEMA,
            S1_T03_DISPOSABLE_SCHEMA);

    private MigrationSchemaGuard() { }

    static void requireDisposableSchema(String url) {
        String name = databaseName(url);
        if (!DISPOSABLE_SCHEMAS.contains(name)) {
            throw new IllegalStateException("migration test refused to clean schema " + name);
        }
    }

    static String databaseName(String url) {
        if (url == null || !url.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException("migration test refused an unrecognized database url");
        }
        int slash = url.indexOf('/', "jdbc:mysql://".length());
        if (slash < 0 || slash == url.length() - 1) {
            throw new IllegalStateException("migration test refused a url without a schema name");
        }
        int query = url.indexOf('?', slash + 1);
        String name = query < 0 ? url.substring(slash + 1) : url.substring(slash + 1, query);
        if (name.isEmpty() || name.indexOf('/') >= 0) {
            throw new IllegalStateException("migration test refused a url without a schema name");
        }
        return name;
    }
}
