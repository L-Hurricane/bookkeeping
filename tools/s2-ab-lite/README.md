# S2-T00R A+B-lite

Two commands, plus a read-only hash check. No scheduler, no agent launch, no permission changes.

- `ab-lite-launch.sh` reads the approved plan file, checks its SHA-256, and records `/usr/bin/true` in a new evidence directory.
- `ab-lite-summarize.sh` writes `SUMMARY.md` inside that directory.
- `ab-lite-summarize.sh --verify` recomputes the five content hashes and does not write.

The only approved output root is `evidence/` under this directory. An evidence directory must be one direct child of that root. Absolute paths outside it, `..`, and symbolic links are refused before a file is created. New files use exclusive create and mode 0600. Existing evidence is not overwritten.

Exit codes:

- `0`: the recorded command succeeded.
- `1`: the command result was recorded, and the command itself failed; hash verification uses `1` for a mismatch.
- `2`: blocked before a harmful write.

`69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5` is the formal plan file SHA. `629dd55f3f891b8ae10faebd87f40a55035f91c311719467dc3dc20b4617f9db` is the review original and is not accepted as `--plan-sha`. Both the argument and the bytes on disk must match the formal SHA.

Redaction is best-effort minimization of a few known token shapes. It does not identify every secret. Raw logs and summaries use the same minimization, and new files are created mode 0600.

`ab-lite-selftest.sh` no longer deletes `selftest-run`. Use `ab-lite-selftest-t00r.sh` in a new evidence child. Do not delete historical evidence.

The recoverable copy of these files is `tools/s2-ab-lite/` on `task/s2-t00-t01`, with `HASHES.txt`. Rollback points stay in Git: document commit `f919c38751a52f74f09d3f0944fb63024c7f14c3`, parent `663e76d4b1e1ac6a7979211299fd7225dfb3236d`. Do not reset the task branch from this tool.

This tool is not approved for S2-T02 until an independent review accepts it.
