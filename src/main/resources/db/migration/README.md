# Migrations through Phase 2

V1 creates users; V2 creates sessions. V3 creates the single per-user account;
V4 creates user-owned fixed categories, including the non-null generated parent
scope used for root/sibling name uniqueness. No credentials or fixture data are
included in SQL. Category seed data is prepared after a user exists by the application.

Use an empty schema or upgrade the existing Flyway history. Automatic baselining
and runtime clean remain disabled; Hibernate validates and never generates schema.
Only the disposable bookkeeping_migration_test schema permits clean in its test.
No tag, transaction, adjustment, ledger, statistics or idempotency tables are added.
