# Identity migrations (Phase 1)

V1 creates users; V2 creates sessions with its user foreign key, unique token hash,
and lookup/expiry indexes. No credentials or fixture data are included.

Use a new empty schema or the existing Phase 0 Flyway history. Automatic baselining
and runtime clean remain disabled; Hibernate validates and never generates schema.
Only the disposable bookkeeping_migration_test schema permits clean in its test.
No account, category, tag, transaction, adjustment or idempotency tables exist yet.
