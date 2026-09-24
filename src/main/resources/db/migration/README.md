# Phase 0 migration directory

Intentionally contains no migrations. Flyway can initialize and validate an empty
MySQL schema without a marker migration; only its `flyway_schema_history` table
may be created. V1 remains available for the Phase 1 users migration.

Automatic baselining and Flyway clean are disabled. Use a new, empty database;
an existing non-empty schema must not be silently adopted. Hibernate validates
the schema and never creates or updates it. Business migrations require the
corresponding implementation phase and human review gate.
