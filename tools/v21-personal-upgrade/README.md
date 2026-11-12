# V21 personal-upgrade safety scripts

Status: **coded under owner approval for script + synthetic/mock verification only.**  
**Not** authorization to operate real database `bk_personal_local` (or any other DB).

Authority text: `docs/implementation/history/bookkeeping-v1.2-personal-bookkeeping-v21-upgrade-op-package-v0.2.md`  
SHA-256 `92c8e91960d8472384975a244a2925887645f75c523f5a150e3453823f4fc503`.

Target-DB adaptation (2026-11-12): personal formal ledger name is **`bk_personal_local`**.  
Reviewed baseline scripts: `50fe905…`. This adaptation is a later commit on `v1.2/s1-m01`.

## Scripts

| File | Role |
|---|---|
| `preflight.sh` | Artifact SHA, DSN/port, **MySQL↔Spring instance identity**, Flyway=20, no `counterparty_name`; confirm `PREFLIGHT-OK` |
| `backup.sh` | Identity probe, logical dump (mock or live), SHA, encrypted copy, **`.manifest` binding**, confirm `BACKUP-OK` |
| `upgrade-run.sh` | Re-check identity + **backup/manifest SHA binding**; confirm `UPGRADE-BOOKKEEPING`; dry-stop unless `BK_V21_EXECUTE_UPGRADE=1`; live launch passes **`--spring.datasource.url=$DB_URL`** |
| `restore.sh` | Default dry-run; identity + manifest binding; refuses V21 overwrite without `BK_V21_ALLOW_BLANK_RESTORE=1` |
| `selftest.sh` | Mock reject/pass cases (incl. identity and manifest tamper) |
| `lib.sh` | Shared assertions |

## Fixed constants

- New business SHA: `824a3ce3ccc3bbb1223ded64c5cdfb04eb2f3b03`
- V21 file SHA-256: `525bf36b67633b1b4b5d9b77694195db4502dcab22ef02274c9c6eea8d45d5f9`
- Target DB name: **`bk_personal_local`** (exact match on JDBC path **and** `DATABASE()`, not substring; **not** env-overridable)
- Forbidden DSN substrings: **`bookkeeping`** applied to **JDBC `DB_URL` / backup schema text only** (covers legacy formal name `bookkeeping` and `bookkeeping_uat` / `_test` / `_migration_test` / `_v21_restore_drill`). **Not** applied to `DB_USERNAME`, `DB_PASSWORD`, or `JAVA_HOME` — a login name may contain `bookkeeping`.
- Forbidden ports: `18081`, `18082`, `18083`

NEW_WT must be an **independent** tree whose `HEAD` is RC-02. The docs-archive worktree HEAD is rejected.

## Instance identity (P1)

Scripts parse JDBC host/port/database, require Spring `application.yml` datasource `url: ${DB_URL}`, then probe MySQL (`@@port`, `@@server_uuid`, `DATABASE()` after connecting to `bk_personal_local`). Mismatch or missing evidence → **STOP**.

### `application-local.yml` residual risk

RC-02 `application-local.yml` still contains a default fallback `jdbc:mysql://localhost:3306/bookkeeping`. That profile default is **not** the approved personal target.  
`upgrade-run.sh` live path **must** export `DB_URL` and pass `--spring.datasource.url=${DB_URL}` so Spring does not fall back. Do not start the upgrade app by relying on the local-profile default alone.

## Backup manifest (P2)

`backup.sh` writes `*.sql.manifest` (mode 600) with target db, JDBC/MySQL ports, server uuid, basename, SHA-256.  
`upgrade-run.sh` / `restore.sh` require `--backup-file` (+ optional `--manifest`) and recompute SHA against the manifest and current instance identity.

## Mock self-test

```sh
./selftest.sh
```

Creates `/tmp/bookkeeping-v21-upgrade-script-test/{rc02-wt,old-wt}` worktrees if missing (RC-02 + pre-V21 `7281d1f…`).

Covered reject paths include: legacy `bookkeeping`, UAT/test/drill names, wrong Flyway/column, port/instance/uuid mismatches, tampered backup, manifest target mismatch, missing arming switches.

## Owner live use (future special approval only)

1. Private env file mode `600` outside Git; `DB_URL=…/bk_personal_local` exact.
2. `BK_V21_ALLOW_LIVE_MYSQL=1` and `BK_V21_MYSQL_DEFAULTS_FILE` (private).
3. Never source UAT/drill env files; never point at `bookkeeping`.
4. Blanking restore needs `BK_V21_ALLOW_BLANK_RESTORE=1` and `BK_V21_EXECUTE_RESTORE=1`.

Passwords must not appear in argv, logs, or commits.
