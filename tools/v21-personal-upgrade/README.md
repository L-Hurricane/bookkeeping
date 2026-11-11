# V21 personal-upgrade safety scripts

Status: **coded under 2026-11-11 owner approval for script + synthetic/mock verification only.**  
**Not** authorization to operate real database `bookkeeping`.

Authority text: `docs/implementation/history/bookkeeping-v1.2-personal-bookkeeping-v21-upgrade-op-package-v0.2.md`  
SHA-256 `92c8e91960d8472384975a244a2925887645f75c523f5a150e3453823f4fc503`.

## Scripts

| File | Role |
|---|---|
| `preflight.sh` | Artifact SHA, DSN/port, Flyway=20, no `counterparty_name`; confirm `PREFLIGHT-OK` |
| `backup.sh` | Logical dump (mock or live), SHA, independent encrypted copy, decrypt feasibility; confirm `BACKUP-OK` |
| `upgrade-run.sh` | Re-check gates; confirm `UPGRADE-BOOKKEEPING`; default dry-stop unless `BK_V21_EXECUTE_UPGRADE=1` |
| `restore.sh` | Default dry-run; refuses V21 overwrite without `BK_V21_ALLOW_BLANK_RESTORE=1`; execute needs extra arming |
| `selftest.sh` | Mock reject/pass cases |
| `lib.sh` | Shared assertions |

## Fixed constants

- New business SHA: `824a3ce3ccc3bbb1223ded64c5cdfb04eb2f3b03`
- V21 file SHA-256: `525bf36b67633b1b4b5d9b77694195db4502dcab22ef02274c9c6eea8d45d5f9`
- Target DB name: `bookkeeping`
- Forbidden DSN substrings: `bookkeeping_uat`, `bookkeeping_test`, `bookkeeping_migration_test`, `bookkeeping_v21_restore_drill`, `bk_personal_local`
- Forbidden ports: `18081`, `18082`, `18083`

NEW_WT must be an **independent** tree whose `HEAD` is RC-02. The docs-archive worktree HEAD `186af14…` is rejected.

## Mock self-test

```sh
# one-time test trees under /tmp (not personal run dir)
./selftest.sh
```

Requires `/tmp/bookkeeping-v21-upgrade-script-test/{rc02-wt,old-wt}` prepared as in Coding verification.

## Owner live use (future special approval only)

1. Copy or run from this directory; keep private env file mode `600` outside Git.
2. Set `BK_V21_ALLOW_LIVE_MYSQL=1` and `BK_V21_MYSQL_DEFAULTS_FILE` (private).
3. Never source UAT/drill env files.
4. Blanking restore additionally needs `BK_V21_ALLOW_BLANK_RESTORE=1` and `BK_V21_EXECUTE_RESTORE=1`.

Passwords must not appear in argv, logs, or commits.
