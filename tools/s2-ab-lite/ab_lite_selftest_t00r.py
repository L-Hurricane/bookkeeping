#!/usr/bin/env python3
"""Isolated T00R safety checks. Leaves selftest-run untouched."""

import hashlib
import os
import subprocess
import sys
import time
from pathlib import Path

import ab_lite_safe

TOOL = Path("/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite")
WS = Path("/Users/limingyuan/Documents/code/personal/bookkeeping-s2-t00")
POC = Path("/Users/limingyuan/Documents/code/personal/bookkeeping-qa-poc2-datetime")
PLAN = WS / "docs/implementation/bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md"
FORMAL = ab_lite_safe.FORMAL_PLAN_SHA
REVIEW = ab_lite_safe.REVIEW_PLAN_SHA
BASE = ab_lite_safe.BASELINE
ROOT = TOOL / "evidence"
RUN = TOOL / "selftest-t00r"
HIST = TOOL / "selftest-run"
STAMP = str(time.time_ns())
LOG = RUN / "selftest.log"


def fail(message):
    print(f"SELFTEST FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def run(args):
    return subprocess.run(args, check=False, capture_output=True, text=True)


def expect_block(name, args):
    completed = run(args)
    if completed.returncode != 2:
        fail(f"{name} rc={completed.returncode} stdout={completed.stdout!r} stderr={completed.stderr!r}")
    print(f"blocked {name} rc=2")


def main():
    if not HIST.is_dir():
        fail("historical selftest-run is missing; refusing to continue")
    historical = sorted(p.name for p in HIST.iterdir())
    old = run([str(TOOL / "ab-lite-selftest.sh")])
    if old.returncode != 2 or historical != sorted(p.name for p in HIST.iterdir()):
        fail("historical self-test removed or changed selftest-run")
    RUN.mkdir(mode=0o700, exist_ok=True)
    ROOT.mkdir(mode=0o700, exist_ok=True)
    launch = [str(TOOL / "ab-lite-launch.sh")]
    summarize = [str(TOOL / "ab-lite-summarize.sh")]
    happy = ROOT / f"t00r{STAMP[-8:]}ok"
    outside = Path(f"/tmp/ab-lite-t00r-outside-{STAMP}")
    escape = Path(f"/tmp/ab-lite-t00r-escape-{STAMP}")
    dangling_target = Path(f"/tmp/ab-lite-t00r-dangling-{STAMP}")
    if outside.exists() or escape.exists() or dangling_target.exists():
        fail("probe path already exists")

    expect_block("outside", launch + [
        "--task", "S2-T00", "--role", "coding", "--workspace", str(WS),
        "--plan-sha", FORMAL, "--baseline", BASE, "--evidence-dir", str(outside),
    ])
    if outside.exists():
        fail("outside path was created")
    traversal = ROOT / ".." / ".." / f"ab-lite-t00r-traversal-{STAMP}"
    expect_block("traversal", launch + [
        "--task", "S2-T00", "--role", "coding", "--workspace", str(WS),
        "--plan-sha", FORMAL, "--baseline", BASE, "--evidence-dir", str(traversal),
    ])
    link = ROOT / f"l{STAMP[-8:]}"
    os.symlink(escape, link)
    expect_block("symlink", launch + [
        "--task", "S2-T00", "--role", "coding", "--workspace", str(WS),
        "--plan-sha", FORMAL, "--baseline", BASE, "--evidence-dir", str(link),
    ])
    if escape.exists():
        fail("symlink target was created")
    expect_block("bad-sha", launch + [
        "--task", "S2-T00", "--role", "coding", "--workspace", str(WS),
        "--plan-sha", "0" * 64, "--baseline", BASE, "--evidence-dir", str(ROOT / f"b{STAMP[-8:]}"),
    ])
    expect_block("review-sha", launch + [
        "--task", "S2-T00", "--role", "coding", "--workspace", str(WS),
        "--plan-sha", REVIEW, "--baseline", BASE, "--evidence-dir", str(ROOT / f"r{STAMP[-8:]}"),
    ])
    expect_block("bad-workspace", launch + [
        "--task", "S2-T00", "--role", "coding", "--workspace", str(POC),
        "--plan-sha", FORMAL, "--baseline", BASE, "--evidence-dir", str(ROOT / f"p{STAMP[-8:]}"),
    ])

    ok = run(launch + [
        "--task", "S2-T00", "--role", "coding", "--workspace", str(WS),
        "--plan-sha", FORMAL, "--baseline", BASE, "--evidence-dir", str(happy),
    ])
    if ok.returncode != 0 or (happy / "exit-code.txt").read_text().strip() != "0":
        fail(f"happy path rc={ok.returncode} stderr={ok.stderr!r}")
    expect_block("existing", launch + [
        "--task", "S2-T00", "--role", "coding", "--workspace", str(WS),
        "--plan-sha", FORMAL, "--baseline", BASE, "--evidence-dir", str(happy),
    ])
    verified = run(summarize + ["--verify", str(happy)])
    if verified.returncode != 0:
        fail(f"verify rc={verified.returncode} stderr={verified.stderr!r}")
    summary = run(summarize + [str(happy)])
    if summary.returncode != 0 or not (happy / "SUMMARY.md").is_file():
        fail(f"summary rc={summary.returncode} stderr={summary.stderr!r}")
    expect_block("summary-exists", summarize + [str(happy)])

    secret_dir = ROOT / f"s{STAMP[-8:]}"
    os.mkdir(secret_dir, 0o700)
    for name in ("manifest.txt", "exit-code.txt", "git-status.txt", "git-diff-stat.txt", "hashes.txt"):
        os.close(os.open(secret_dir / name, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600))
    secret_fd = os.open(secret_dir / "command.log", os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.write(secret_fd, b"DB_PASSWORD=super-secret-value\n")
    os.close(secret_fd)
    secret_summary = run(summarize + [str(secret_dir)])
    text = (secret_dir / "SUMMARY.md").read_text()
    if secret_summary.returncode != 0 or "super-secret-value" in text or "super-secret-value" in secret_summary.stdout:
        fail("secret leaked")
    if "[REDACTED]" not in text:
        fail("secret was not minimized")

    dangling_dir = ROOT / f"d{STAMP[-8:]}"
    os.mkdir(dangling_dir, 0o700)
    for name in ab_lite_safe.CONTENT_FILES:
        fd = os.open(dangling_dir / name, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.write(fd, b"ok\n")
        os.close(fd)
    os.symlink(dangling_target, dangling_dir / "SUMMARY.md")
    expect_block("dangling-summary", summarize + [str(dangling_dir)])
    if dangling_target.exists():
        fail("dangling symlink was followed")

    failed = ROOT / f"f{STAMP[-8:]}"
    rc = ab_lite_safe.launch("S2-T00", "coding", str(WS), FORMAL, BASE, str(failed), ["/usr/bin/false"])
    recorded = (failed / "exit-code.txt").read_text().strip()
    if rc != 1 or recorded != "1":
        fail(f"non-zero record rc={rc} file={recorded!r}")

    fixture = RUN / "plan-fixture"
    fixture_plan = fixture / "docs/implementation/bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md"
    fixture_plan.parent.mkdir(parents=True, mode=0o700)
    fixture_plan.write_bytes(PLAN.read_bytes() + b"\nchanged\n")
    mutated = run(["/usr/bin/python3", str(TOOL / "ab_lite_safe.py"), "check-plan", str(fixture), FORMAL])
    if mutated.returncode != 2:
        fail(f"modified plan accepted old formal SHA rc={mutated.returncode}")
    new_digest = hashlib.sha256(fixture_plan.read_bytes()).hexdigest()
    mutated_new = run(["/usr/bin/python3", str(TOOL / "ab_lite_safe.py"), "check-plan", str(fixture), new_digest])
    if mutated_new.returncode != 2:
        fail("modified plan accepted its new SHA")

    real = run(["/usr/bin/python3", str(TOOL / "ab_lite_safe.py"), "check-plan", str(WS), FORMAL])
    if real.returncode != 0:
        fail(f"real plan check rc={real.returncode} stderr={real.stderr!r}")
    tampered = (happy / "command.log").read_bytes() + b"x"
    (happy / "command.log").write_bytes(tampered)
    mismatch = run(summarize + ["--verify", str(happy)])
    if mismatch.returncode != 1:
        fail(f"tampered evidence verify rc={mismatch.returncode}")
    if not HIST.is_dir() or historical != sorted(p.name for p in HIST.iterdir()):
        fail("historical selftest-run disappeared or changed")
    print("SELFTEST PASS")


if __name__ == "__main__":
    main()
