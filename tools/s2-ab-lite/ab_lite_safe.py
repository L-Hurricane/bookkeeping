#!/usr/bin/env python3
"""S2-T00R evidence confinement for the launch and summarize commands."""

import hashlib
import os
import re
import stat
import subprocess
import sys
from pathlib import Path

EXIT_OK = 0
EXIT_RECORDED_FAILURE = 1
EXIT_BLOCKED = 2

FORMAL_PLAN_SHA = "69ff73a1c0f549cccd125d5b89271996076ab8685a4fe70745c91beb26561be5"
REVIEW_PLAN_SHA = "629dd55f3f891b8ae10faebd87f40a55035f91c311719467dc3dc20b4617f9db"
BASELINE = "663e76d4b1e1ac6a7979211299fd7225dfb3236d"
PLAN_RELATIVE = Path("docs/implementation/bookkeeping-v1.0-stage-s2-plan-v0.1-approved.md")
TOOL_ROOT = Path("/Users/limingyuan/Documents/code/personal/bookkeeping-s2-ab-lite")
OUTPUT_ROOT = TOOL_ROOT / "evidence"
ALLOWED_WORKSPACE = Path("/Users/limingyuan/Documents/code/personal/bookkeeping-s2-t00")
FORBIDDEN_WORKSPACE = Path("/Users/limingyuan/Documents/code/personal/bookkeeping-qa-poc2-datetime")
CONTENT_FILES = ("manifest.txt", "command.log", "exit-code.txt", "git-status.txt", "git-diff-stat.txt")
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
# Best-effort minimization only. This pattern does not identify every secret.
SECRET_RE = re.compile(
    r"(gho_[A-Za-z0-9_]+|ghp_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|"
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----|"
    r"(?:DB_PASSWORD|PASSWORD|TOKEN|SECRET)=[^\s]+)",
    re.IGNORECASE,
)


class Blocked(Exception):
    pass


def block(message):
    print(f"BLOCKED: {message}", file=sys.stderr)
    raise SystemExit(EXIT_BLOCKED)


def minimize(text):
    return SECRET_RE.sub("[REDACTED]", text)


def _norm(path):
    return Path(os.path.normpath(os.path.abspath(os.fspath(path))))


def _reject_symlinks(path):
    path = _norm(path)
    current = Path(path.anchor)
    for part in path.parts[1:]:
        current = current / part
        if current.is_symlink():
            raise Blocked("symlink in path")


def output_root():
    root = _norm(OUTPUT_ROOT)
    if not root.is_dir() or root.is_symlink():
        raise Blocked("approved output root is missing or is a symlink")
    _reject_symlinks(root)
    if root != _norm(TOOL_ROOT / "evidence"):
        raise Blocked("approved output root escaped")
    return root


def _child(requested, must_exist):
    root = output_root()
    raw = Path(requested)
    if not raw.is_absolute():
        raise Blocked("evidence path must be absolute")
    candidate = _norm(raw)
    try:
        relative = candidate.relative_to(root)
    except ValueError as exc:
        raise Blocked("evidence path is outside the approved output root") from exc
    if len(relative.parts) != 1 or not NAME_RE.fullmatch(relative.parts[0]):
        raise Blocked("evidence path is not a direct child of the output root")
    parent = _norm(candidate.parent)
    if parent != root:
        raise Blocked("evidence parent is outside the approved output root")
    _reject_symlinks(parent)
    if candidate.is_symlink():
        raise Blocked("evidence path is a symlink")
    if must_exist:
        if not candidate.is_dir():
            raise Blocked("evidence path is not a real directory")
        _reject_symlinks(candidate)
    elif candidate.exists():
        raise Blocked("evidence directory already exists")
    return candidate


def evidence_dir(requested):
    return _child(requested, False)


def existing_evidence_dir(requested):
    return _child(requested, True)


def _open_directory(path):
    path = Path(path)
    if path.is_symlink():
        raise Blocked("refusing to follow a symlink")
    try:
        return os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    except OSError as exc:
        raise Blocked("unable to open a real directory without following a symlink") from exc


def read_nofollow(path):
    path = Path(path)
    if path.is_symlink():
        raise Blocked("refusing to follow a symlink")
    parent_fd = _open_directory(path.parent)
    try:
        fd = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=parent_fd)
    except OSError as exc:
        raise Blocked("unable to read a regular file without following a symlink") from exc
    finally:
        os.close(parent_fd)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode):
            raise Blocked("evidence entry is not a regular file")
        return os.read(fd, 1_000_000)
    finally:
        os.close(fd)


def create_exclusive(path, data):
    path = Path(path)
    if path.parent.is_symlink() or path.is_symlink() or path.exists():
        raise Blocked("output already exists or is a symlink")
    parent_fd = _open_directory(path.parent)
    try:
        fd = os.open(
            path.name, os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_WRONLY, 0o600, dir_fd=parent_fd
        )
    except OSError as exc:
        raise Blocked("unable to create the output without following a symlink") from exc
    finally:
        os.close(parent_fd)
    try:
        os.write(fd, data)
    finally:
        os.close(fd)


def mkdir_exclusive(path):
    path = Path(path)
    if path.parent.is_symlink() or path.is_symlink() or path.exists():
        raise Blocked("evidence directory already exists or is a symlink")
    parent_fd = _open_directory(path.parent)
    try:
        os.mkdir(path.name, 0o700, dir_fd=parent_fd)
    except OSError as exc:
        raise Blocked("unable to create the evidence directory") from exc
    finally:
        os.close(parent_fd)


def verify_formal_plan(workspace, claimed):
    if claimed == REVIEW_PLAN_SHA:
        raise Blocked("review original SHA is not the formal plan file SHA")
    path = Path(workspace) / PLAN_RELATIVE
    _reject_symlinks(path)
    data = read_nofollow(path)
    digest = hashlib.sha256(data).hexdigest()
    if claimed != FORMAL_PLAN_SHA or digest != FORMAL_PLAN_SHA:
        raise Blocked("formal plan file SHA mismatch")
    return digest


def git(workspace, *args):
    return subprocess.run(
        ["/usr/bin/git", "-c", "core.fsmonitor=", "-c", "core.hooksPath=/dev/null", "-C", str(workspace), *args],
        check=False,
        capture_output=True,
        text=True,
    )


def require_workspace(workspace, baseline):
    current = _norm(workspace)
    if Path(workspace).is_symlink():
        raise Blocked("workspace is a symlink")
    if current == _norm(FORBIDDEN_WORKSPACE):
        raise Blocked("workspace is the dirty POC-2 worktree")
    if current != _norm(ALLOWED_WORKSPACE):
        raise Blocked("workspace is not the authorized task worktree")
    _reject_symlinks(current)
    if baseline != BASELINE:
        raise Blocked("baseline mismatch")
    head = git(current, "rev-parse", "HEAD")
    if head.returncode != 0:
        raise Blocked("workspace is not a usable git worktree")
    if git(current, "merge-base", "--is-ancestor", BASELINE, "HEAD").returncode != 0:
        raise Blocked("baseline is not an ancestor of workspace HEAD")
    status = git(current, "status", "--porcelain=v1")
    if status.returncode != 0 or status.stdout.strip():
        raise Blocked("workspace is dirty")
    return current, head.stdout.strip()


def record_command(directory, command):
    completed = subprocess.run(command, check=False, capture_output=True, text=True)
    create_exclusive(directory / "command.log", minimize(completed.stdout + completed.stderr).encode())
    create_exclusive(directory / "exit-code.txt", f"{completed.returncode}\n".encode())
    return completed.returncode


def launch(task, role, workspace, claimed_plan, baseline, requested_evidence, command):
    if task != "S2-T00":
        raise Blocked("task is not registered")
    if role != "coding":
        raise Blocked("role is not allowed for this task")
    workspace, head = require_workspace(workspace, baseline)
    digest = verify_formal_plan(workspace, claimed_plan)
    target = evidence_dir(requested_evidence)
    status = git(workspace, "status", "--porcelain=v1", "-b")
    diff = git(workspace, "diff", "--stat")
    manifest = "\n".join([
        f"TASK={task}",
        f"ROLE={role}",
        f"FORMAL_PLAN_SHA={digest}",
        f"REVIEW_PLAN_SHA_NOT_ACCEPTED={REVIEW_PLAN_SHA}",
        f"BASELINE={BASELINE}",
        f"WORKSPACE={workspace}",
        f"HEAD={head}",
        "",
    ])
    mkdir_exclusive(target)
    create_exclusive(target / "manifest.txt", minimize(manifest).encode())
    create_exclusive(target / "git-status.txt", minimize(status.stdout).encode())
    create_exclusive(target / "git-diff-stat.txt", minimize(diff.stdout).encode())
    rc = record_command(target, command)
    lines = []
    for name in CONTENT_FILES:
        file_digest = hashlib.sha256(read_nofollow(target / name)).hexdigest()
        lines.append(f"{file_digest}  {name}\n")
    create_exclusive(target / "hashes.txt", minimize("".join(lines)).encode())
    print(f"RECORDED rc={rc}")
    return EXIT_OK if rc == 0 else EXIT_RECORDED_FAILURE


def summary_text(directory):
    chunks = ["# S2-T00 evidence summary", ""]
    labels = [
        ("manifest.txt", "Manifest"),
        ("exit-code.txt", "Exit code"),
        ("git-status.txt", "Git status"),
        ("git-diff-stat.txt", "Git diff stat"),
        ("command.log", "Command log"),
        ("hashes.txt", "SHA-256"),
    ]
    for name, label in labels:
        path = directory / name
        if path.is_symlink():
            raise Blocked("evidence file is a symlink")
        shown = minimize(read_nofollow(path).decode(errors="replace")).strip()
        chunks.extend([f"## {label}", shown if shown else "(empty)", ""])
    chunks.append("Redaction is best-effort minimization, not a complete secret detector.")
    chunks.append("")
    return "\n".join(chunks)


def summarize(requested):
    directory = existing_evidence_dir(requested)
    summary = directory / "SUMMARY.md"
    if summary.is_symlink() or summary.exists():
        raise Blocked("summary already exists or is a symlink")
    create_exclusive(summary, minimize(summary_text(directory)).encode())
    print("RECORDED summary")
    return EXIT_OK


def verify(requested):
    directory = existing_evidence_dir(requested)
    seen = set()
    for line in read_nofollow(directory / "hashes.txt").decode().splitlines():
        if not line.strip():
            continue
        digest, name = line.split(None, 1)
        if name not in CONTENT_FILES or name in seen:
            raise Blocked("hash list contains an unexpected file")
        path = directory / name
        if path.is_symlink():
            raise Blocked("evidence file is a symlink")
        actual = hashlib.sha256(read_nofollow(path)).hexdigest()
        if actual != digest:
            print("FAIL: evidence hash mismatch", file=sys.stderr)
            return EXIT_RECORDED_FAILURE
        seen.add(name)
    if seen != set(CONTENT_FILES):
        raise Blocked("hash list is incomplete")
    print("PASS: evidence hashes match")
    return EXIT_OK


def main(argv):
    try:
        if len(argv) < 2:
            raise Blocked("missing command")
        command = argv[1]
        if command == "launch":
            if len(argv) != 8:
                raise Blocked("launch arguments are incomplete")
            _, _, task, role, workspace, claimed, baseline, evidence = argv
            return launch(task, role, workspace, claimed, baseline, evidence, ["/usr/bin/true"])
        if command == "summarize":
            if len(argv) != 3:
                raise Blocked("summarize arguments are incomplete")
            return summarize(argv[2])
        if command == "verify":
            if len(argv) != 3:
                raise Blocked("verify arguments are incomplete")
            return verify(argv[2])
        if command == "check-plan":
            if len(argv) != 4:
                raise Blocked("check-plan arguments are incomplete")
            verify_formal_plan(argv[2], argv[3])
            print("PASS: formal plan file SHA")
            return EXIT_OK
        raise Blocked("unknown command")
    except Blocked as exc:
        block(str(exc))
    except SystemExit:
        raise
    except Exception:
        print("FAIL: internal", file=sys.stderr)
        return EXIT_RECORDED_FAILURE


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
