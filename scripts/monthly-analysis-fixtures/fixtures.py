#!/usr/bin/env python3
"""Plan, apply, and verify the v0.2 monthly analysis development fixtures.

Credentials stay in $HOME/.config/bookkeeping/ai-acceptance.env.
The script does not call a model and does not delete personal ledger rows.
"""

import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from decimal import Decimal
from pathlib import Path

HOME = Path.home()
ROOT = Path(__file__).resolve().parents[2]
PRIVATE = HOME / ".config" / "bookkeeping" / "ai-acceptance.env"
STOP = HOME / ".config" / "bookkeeping" / "ai-acceptance.stop"
CLASSES = HOME / ".cache" / "bookkeeping-fixtures" / "classes"
PORT = 18080
BASE = f"http://127.0.0.1:{PORT}"
USERS = ("AI_ACCEPTANCE_A", "AI_ACCEPTANCE_B")
NAMESPACE = uuid.UUID("6f0c9a1e-5b24-4d1a-9c3e-1a2b3c4d5e6f")
BOOTSTRAP_JAVA = HOME / ".local/share/bookkeeping-bootstrap/zulu25.36.205-ca-jdk25.0.4.1-macosx_aarch64/Contents/Home"
BOOTSTRAP_MYSQL = HOME / ".local/share/bookkeeping-bootstrap/mysql-8.4.3-macos14-arm64/bin/mysql"
LOCAL_DATABASE_HOSTS = {"localhost", "127.0.0.1", "::1"}
TOOLS = {}

CASES = [
    {"id": "MA-01", "user": "AI_ACCEPTANCE_A", "month": "2026-01", "state": "NO_ANALYSIS",
     "income": "100.00", "expense": "40.00", "surplus": "60.00", "income_count": 1, "expense_count": 1,
     "rows": [("INCOME", "100.00", "基本工资", "工资", "2026-01-01T08:00:00+08:00"),
              ("EXPENSE", "40.00", "日用品", "购物", "2026-01-02T08:00:00+08:00")]},
    {"id": "MA-02", "user": "AI_ACCEPTANCE_A", "month": "2026-02", "state": "NO_ANALYSIS",
     "income": "50.00", "expense": "-20.00", "surplus": "70.00", "income_count": 1, "expense_count": 1,
     "rows": [("INCOME", "50.00", "基本工资", "工资", "2026-02-01T08:00:00+08:00"),
              ("EXPENSE", "-20.00", "购物退款", "购物", "2026-02-02T08:00:00+08:00")]},
    {"id": "MA-03", "user": "AI_ACCEPTANCE_A", "month": "2026-03", "state": "NO_ANALYSIS",
     "income": "50.00", "expense": "20.00", "surplus": "30.00", "income_count": 2, "expense_count": 1,
     "rows": [("INCOME", "150.00", "基本工资", "工资", "2026-03-01T08:00:00+08:00"),
              ("INCOME", "-100.00", "工资冲减", "工资", "2026-03-02T08:00:00+08:00"),
              ("EXPENSE", "20.00", "日用品", "购物", "2026-03-03T08:00:00+08:00")]},
    {"id": "MA-04", "user": "AI_ACCEPTANCE_B", "month": "2026-04", "state": "NO_ANALYSIS",
     "income": "12.00", "expense": "0.00", "surplus": "12.00", "income_count": 1, "expense_count": 0,
     "rows": [("INCOME", "12.00", "基本工资", "工资", "2026-04-01T08:00:00+08:00")]},
    {"id": "MA-05", "user": "AI_ACCEPTANCE_B", "month": "2026-05", "state": "NO_ANALYSIS",
     "income": "0.00", "expense": "40.00", "surplus": "-40.00", "income_count": 0, "expense_count": 1,
     "rows": [("EXPENSE", "40.00", "日用品", "购物", "2026-05-01T08:00:00+08:00")]},
    {"id": "MA-06", "user": "AI_ACCEPTANCE_B", "month": "2026-06", "state": "NO_ANALYSIS",
     "income": "100.00", "expense": "40.00", "surplus": "60.00", "income_count": 1, "expense_count": 1,
     "rows": [("INCOME", "100.00", "基本工资", "工资", "2026-06-01T08:00:00+08:00"),
              ("EXPENSE", "40.00", None, None, "2026-06-02T08:00:00+08:00")]},
    {"id": "MA-07", "user": "AI_ACCEPTANCE_A", "month": "2026-07", "state": "EMPTY_MONTH",
     "income": "0.00", "expense": "0.00", "surplus": "0.00", "income_count": 0, "expense_count": 0,
     "rows": [], "adjustment": ("10.00", "2026-07-15T08:00:00+08:00")},
    {"id": "MA-09", "user": "AI_ACCEPTANCE_A", "month": "2026-09", "state": "EMPTY_MONTH",
     "income": "0.00", "expense": "0.00", "surplus": "0.00", "income_count": 0, "expense_count": 0, "rows": []},
]


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in {"plan", "apply", "verify", "self-check"}:
        raise SystemExit("usage: fixtures.py plan|apply|verify")
    if sys.argv[1] == "self-check":
        self_check()
        return
    load_bootstrap()
    resolve_tools()
    require_dev_schema()
    command = sys.argv[1]
    if command == "plan":
        plan()
    elif command == "apply":
        apply()
    else:
        os.environ["FIXTURE_ENSURE_USERS"] = "false"
        assert_existing_users_are_owned()
        with server():
            sessions = login_all()
            for username in USERS:
                attach_account(sessions[username])
            verify(sessions)


def plan():
    print("schema", schema_name(os.environ.get("DB_URL", "")))
    print("flyway", flyway_summary())
    print("fixture_users", ",".join(existing_fixture_users()) or "none")
    print("pinned_ids", "yes" if pinned_ids() else "no")
    print("other_ledger", json.dumps(other_ledger(), sort_keys=True))
    stored = stored_rows()
    adjustments = stored_adjustments()
    for case in CASES:
        actual = stored.get((case["user"], case["month"]), [])
        line = (f"{case['id']} {case['user']} {case['month']} action={month_action(actual, expected_rows(case))} "
                f"existing={len(actual)} expected={len(case['rows'])}")
        if case.get("adjustment"):
            line += " adjustment=" + adjustment_action(adjustments.get((case["user"], case["month"]), []), case)
        elif adjustments.get((case["user"], case["month"])):
            line += " adjustment=stop"
        print(line)
    extra = extra_transaction_keys(stored)
    print("extra_transactions", "none" if not extra else ",".join(f"{user}:{month}" for user, month in extra))
    extra_adjustments = extra_adjustment_keys(adjustments)
    print("extra_adjustments", "none" if not extra_adjustments else ",".join(
        f"{user}:{month}" for user, month in extra_adjustments))
    print("transactions", sum(len(case["rows"]) for case in CASES))
    print("existing_transactions", sum(len(rows) for rows in stored.values()))
    print("adjustments", sum(1 for case in CASES if case.get("adjustment")))
    print("fixture_snapshots", fixture_snapshots())
    print("credentials", PRIVATE)
    print("mapping 工资薪酬/基本工资 -> 工资 + 名称基本工资")
    print("mapping 购物/日用品 -> 购物 + 名称日用品")


def apply():
    before = other_ledger()
    load_or_create_passwords(existing_fixture_users())
    assert_existing_users_are_owned()
    with server():
        sessions = login_all()
        after_boot = other_ledger()
        if after_boot != before:
            raise SystemExit("other ledger changed while starting the fixture server")
        ids = fixture_user_ids()
        require_pinned_ids(ids)
        for username in USERS:
            prepare_account(sessions[username], mutate_clock=False)
        actions = preflight(sessions)
        if any(tx_action == "create" or adj_action == "create" for tx_action, adj_action, _case in actions):
            for username in USERS:
                move_created_time(sessions[username])
        for tx_action, _adj_action, case in actions:
            if tx_action == "create":
                for index, row in enumerate(case["rows"]):
                    create_transaction(sessions[case["user"]], case, index, row)
                print(f"created {case['id']}")
            else:
                print(f"reuse {case['id']}")
            if case.get("adjustment"):
                ensure_adjustment(sessions[case["user"]], case)
        verify(sessions)
        remember_ids(ids)
        if other_ledger() != before:
            raise SystemExit("other ledger changed during apply")
    print("apply_ok")


def verify(sessions):
    require_pinned_ids(fixture_user_ids())
    extra = extra_transaction_keys(stored_rows())
    if extra:
        raise SystemExit("unexpected fixture transactions " + ",".join(f"{user}:{month}" for user, month in extra))
    require_no_extra_adjustments(stored_adjustments())
    for case in CASES:
        check_case(sessions[case["user"]], case)
    print("verify_ok")


def preflight(sessions):
    extra = extra_transaction_keys(stored_rows())
    if extra:
        raise SystemExit("unexpected fixture transactions " + ",".join(f"{user}:{month}" for user, month in extra))
    require_no_extra_adjustments(stored_adjustments())
    actions = []
    for case in CASES:
        actual = transaction_rows(sessions[case["user"]], case["month"])
        expected = expected_rows(case)
        tx_action = month_action(actual, expected)
        if tx_action == "stop":
            raise SystemExit(f"mismatch {case['id']}: actual={actual} expected={expected}")
        adj_action = None
        if case.get("adjustment"):
            items = adjustment_items(sessions[case["user"]], case["month"])
            if len(items) == 1 and adjustment_matches(sessions[case["user"]], items[0], case):
                adj_action = "reuse"
            elif not items:
                adj_action = "create"
            else:
                raise SystemExit(f"mismatch {case['id']} adjustment")
        elif adjustment_items(sessions[case["user"]], case["month"]):
            raise SystemExit(f"unexpected adjustment in {case['id']}")
        actions.append((tx_action, adj_action, case))
    return actions


def month_action(actual, expected):
    if actual == expected:
        return "reuse"
    if not actual:
        return "create"
    return "stop"


def adjustment_delta(case):
    before = Decimal("0.00")
    for earlier in CASES:
        if earlier["user"] != case["user"] or earlier["month"] >= case["month"]:
            continue
        for kind, amount, *_rest in earlier["rows"]:
            signed = Decimal(amount)
            before += signed if kind == "INCOME" else -signed
    return format(Decimal(case["adjustment"][0]) - before, "f")


def adjustment_action(actual, case):
    expected = (adjustment_delta(case), case["adjustment"][1], "验收余额调整")
    if not actual:
        return "create"
    if actual == [expected]:
        return "reuse"
    return "stop"


def extra_transaction_keys(stored):
    expected = {(case["user"], case["month"]) for case in CASES}
    return sorted(set(stored) - expected)


def extra_adjustment_keys(adjustments):
    expected = {(case["user"], case["month"]) for case in CASES if case.get("adjustment")}
    return sorted(set(adjustments) - expected)


def require_no_extra_adjustments(adjustments):
    extra = extra_adjustment_keys(adjustments)
    if extra:
        raise SystemExit("unexpected fixture adjustments " + ",".join(f"{user}:{month}" for user, month in extra))


def ensure_adjustment(session, case):
    target, when = case["adjustment"]
    items = adjustment_items(session, case["month"])
    if len(items) == 1 and adjustment_matches(session, items[0], case):
        print(f"reuse {case['id']} adjustment")
        return
    if items:
        raise SystemExit(f"mismatch {case['id']} adjustment")
    request("POST", f"/api/v1/accounts/{session['accountId']}/balance-adjustments", session["token"],
            {"targetBalance": target, "businessTime": when, "remark": "验收余额调整"},
            key(f"{case['user']}-{case['month']}-adjustment"), (200, 201))
    print(f"created {case['id']} adjustment")


def check_case(session, case):
    analysis = request("GET", f"/api/v1/accounts/{session['accountId']}/monthly-analysis?month={case['month']}",
                       session["token"])
    facts = analysis["facts"]
    if "snapshot" not in analysis and analysis["viewState"] != case["state"]:
        raise SystemExit(f"{case['id']} view {analysis.get('viewState')}")
    observed = (facts["netIncome"], facts["netExpense"], facts["surplus"],
                facts["incomeTransactionCount"], facts["expenseTransactionCount"])
    expected = (case["income"], case["expense"], case["surplus"], case["income_count"], case["expense_count"])
    if observed != expected:
        raise SystemExit(f"{case['id']} facts {observed} != {expected}")
    actual = transaction_rows(session, case["month"])
    if actual != expected_rows(case):
        raise SystemExit(f"{case['id']} ledger rows differ")
    if case.get("adjustment"):
        items = adjustment_items(session, case["month"])
        if len(items) != 1 or not adjustment_matches(session, items[0], case):
            raise SystemExit(f"{case['id']} adjustment mismatch")
    elif adjustment_items(session, case["month"]):
        raise SystemExit(f"{case['id']} has an adjustment")
    print(f"pass {case['id']} {case['month']} {analysis['viewState']} {case['income']} {case['expense']} {case['surplus']}")


def expected_rows(case):
    return sorted(case["rows"], key=row_key)


def transaction_rows(session, month):
    body = request("GET", f"/api/v1/accounts/{session['accountId']}/transactions?month={month}", session["token"])
    rows = []
    for item in body["items"]:
        category = None if item.get("category") is None else item["category"]["name"]
        rows.append((item["type"], item["amount"], item.get("displayName"), category, item["businessTime"]))
    return sorted(rows, key=row_key)


def row_key(row):
    return tuple("" if part is None else part for part in row)


def adjustment_items(session, month):
    body = request("GET", f"/api/v1/accounts/{session['accountId']}/ledger?month={month}", session["token"])
    return [item for item in body["items"] if item["kind"] == "BALANCE_ADJUSTMENT"]


def adjustment_matches(session, item, case):
    view = request("GET", f"/api/v1/accounts/{session['accountId']}/balance-adjustments/{item['id']}", session["token"])
    _target, when = case["adjustment"]
    return (view.get("remark") == "验收余额调整" and view.get("businessTime") == when
            and view.get("adjustmentAmount") == adjustment_delta(case))


def fixture_snapshots():
    return mysql(
        "SELECT COUNT(*) FROM analysis_snapshots s JOIN users u ON u.id = s.user_id "
        "WHERE u.username IN ('AI_ACCEPTANCE_A','AI_ACCEPTANCE_B')"
    )


def create_transaction(session, case, index, row):
    kind, amount, name, category, when = row
    payload = {"type": kind, "amount": amount, "tagIds": [], "businessTime": when}
    if name is not None:
        payload["name"] = name
    if category is not None:
        payload["categoryId"] = session["categories"][category]
    request("POST", f"/api/v1/accounts/{session['accountId']}/transactions", session["token"], payload,
            key(f"{case['user']}-{case['month']}-{index}"), (200, 201))


def attach_account(session):
    accounts = request("GET", "/api/v1/accounts", session["token"])["items"]
    if len(accounts) != 1:
        raise SystemExit(f"{session['username']} does not have exactly one account")
    session["accountId"] = accounts[0]["id"]
    return accounts[0]


def prepare_account(session, mutate_clock):
    accounts = request("GET", "/api/v1/accounts", session["token"])["items"]
    if len(accounts) > 1:
        raise SystemExit(f"{session['username']} has more than one account")
    if not accounts:
        created = request("POST", "/api/v1/accounts/initialize", session["token"], {"openingBalance": "0.00"}, None, 201)
        session["accountId"] = created["id"]
        if created["openingBalance"] != "0.00":
            raise SystemExit("opening balance was not 0.00")
    else:
        session["accountId"] = accounts[0]["id"]
        if accounts[0]["openingBalance"] != "0.00":
            raise SystemExit(f"{session['username']} opening balance is {accounts[0]['openingBalance']}")
    if mutate_clock:
        move_created_time(session)
    categories = {}
    for kind, name in (("INCOME", "工资"), ("EXPENSE", "购物")):
        found = find_category(request("GET", f"/api/v1/categories?type={kind}", session["token"])["items"], name)
        if found is None or not found["bookable"]:
            raise SystemExit(f"missing bookable category {name}")
        categories[name] = found["id"]
    session["categories"] = categories


def find_category(items, name):
    for item in items:
        if item["name"] == name:
            return item
        found = find_category(item.get("children") or [], name)
        if found is not None:
            return found
    return None


def move_created_time(session):
    username = session["username"]
    account_id = int(session["accountId"])
    user_id = fixture_user_ids()[username]
    if username not in USERS or account_id <= 0 or user_id <= 0:
        raise SystemExit("refusing to move a non-fixture account")
    if created_time_is_ready(account_id, user_id, username):
        return
    owned = mysql(
        "SELECT COUNT(*) FROM accounts a JOIN users u ON u.id = a.user_id "
        f"WHERE a.id = {account_id} AND a.user_id = {user_id} AND u.username = '{username}'"
    )
    if owned != "1":
        raise SystemExit(f"refusing to move created_time for {username}")
    mysql(
        "UPDATE accounts a JOIN users u ON u.id = a.user_id "
        "SET a.created_time = '2020-01-01 00:00:00' "
        f"WHERE a.id = {account_id} AND a.user_id = {user_id} AND u.username = '{username}'"
    )
    if not created_time_is_ready(account_id, user_id, username):
        raise SystemExit(f"could not move created_time for {username}")


def created_time_is_ready(account_id, user_id, username):
    value = mysql(
        "SELECT a.created_time FROM accounts a JOIN users u ON u.id = a.user_id "
        f"WHERE a.id = {int(account_id)} AND a.user_id = {int(user_id)} AND u.username = '{username}'"
    )
    return value.startswith("2020-01-01")


def login_all():
    passwords = read_private()
    sessions = {}
    for username in USERS:
        result = request("POST", "/api/v1/auth/login", None, {"username": username, "password": passwords[username]})
        sessions[username] = {"username": username, "token": result["token"]}
    return sessions


def request(method, path, token, payload=None, idempotency=None, expected=200):
    data = None if payload is None else json.dumps(payload).encode()
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if data is not None:
        headers["Content-Type"] = "application/json"
    if idempotency:
        headers["Idempotency-Key"] = idempotency
    call = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(call, timeout=20) as response:
            status = response.status
            raw = response.read().decode()
    except urllib.error.HTTPError as error:
        status = error.code
        raw = error.read().decode()
    expected_statuses = expected if isinstance(expected, tuple) else (expected,)
    if status not in expected_statuses:
        body = "(body omitted)" if any(word in raw.lower() for word in ("token", "password", "authorization")) else raw[:300]
        raise SystemExit(f"{method} {path} -> {status} {body}")
    return json.loads(raw) if raw else {}


def key(name):
    return str(uuid.uuid5(NAMESPACE, name))


class server:
    def __enter__(self):
        if port_open():
            raise SystemExit(f"port {PORT} is already in use")
        compile_server()
        self.log = HOME / ".config" / "bookkeeping" / "ai-acceptance-boot.log"
        self.log.write_text("")
        self.process = subprocess.Popen(java_command(), cwd=ROOT, env=server_env(),
                                        stdout=self.log.open("w"), stderr=subprocess.STDOUT)
        ready = False
        deadline = time.time() + 90
        while time.time() < deadline:
            text = self.log.read_text(errors="replace")
            if "Migrating schema" in text:
                self.process.terminate()
                raise SystemExit("fixture server attempted a migration")
            if "READY catalog=bookkeeping " in text:
                try:
                    require_loopback_listener()
                except SystemExit:
                    self.process.terminate()
                    raise
                ready = True
                break
            if "REFUSING" in text or self.process.poll() is not None:
                break
            time.sleep(0.4)
        if not ready:
            self.process.terminate()
            tail = "\n".join(self.log.read_text(errors="replace").splitlines()[-30:])
            raise SystemExit("fixture server did not become ready\n" + tail)
        return self

    def __exit__(self, exc_type, exc, tb):
        STOP.write_text("stop\n")
        try:
            self.process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            self.process.kill()
        STOP.unlink(missing_ok=True)


def compile_server():
    ensure_application_classes()
    CLASSES.mkdir(parents=True, exist_ok=True)
    classpath_file = CLASSES.parent / "classpath.txt"
    subprocess.check_call([
        "./mvnw", "-q", "dependency:build-classpath", "-DincludeScope=runtime",
        f"-Dmdep.outputFile={classpath_file}",
    ], cwd=ROOT, env=tool_env())
    classpath = classpath_file.read_text().strip()
    if "spring-boot" not in classpath:
        raise SystemExit("fixture compile classpath is missing Spring")
    subprocess.check_call([
        str(java_home() / "bin" / "javac"), "--release", "25", "-cp", f"target/classes:{classpath}",
        "-d", str(CLASSES), str(ROOT / "scripts/monthly-analysis-fixtures/FixtureServer.java"),
    ], cwd=ROOT, env=tool_env())
    os.environ["FIXTURE_CP"] = f"{CLASSES}:target/classes:{classpath}"


def ensure_application_classes():
    if application_classes_ready(ROOT):
        return
    subprocess.check_call(["./mvnw", "-q", "-DskipTests", "compile"], cwd=ROOT, env=tool_env())
    if not application_classes_ready(ROOT):
        raise SystemExit("application classes are missing after compile")


def application_classes_ready(root):
    classes = root / "target" / "classes"
    return (classes / "com/limingyuan/bookkeeping/BookkeepingApplication.class").is_file() and (
        classes / "db/migration/V1__create_users.sql").is_file()


def java_command():
    return [str(java_home() / "bin" / "java"), "-cp", os.environ["FIXTURE_CP"], "FixtureServer"]


def server_env():
    env = tool_env()
    env["BOOTSTRAP_USER_ENABLED"] = "false"
    env["SPRING_FLYWAY_ENABLED"] = "false"
    env.pop("DASHSCOPE_API_KEY", None)
    env.pop("DASHSCOPE_BASE_URL", None)
    private = read_private()
    for username in USERS:
        env[f"{username}_USERNAME"] = username
        env[f"{username}_PASSWORD"] = private[username]
    return env


def fixture_user_ids():
    rows = mysql(
        "SELECT username, id FROM users WHERE username IN ('AI_ACCEPTANCE_A','AI_ACCEPTANCE_B') ORDER BY username"
    )
    found = {}
    for line in rows.splitlines():
        username, user_id = line.split("\t")
        if username not in USERS:
            raise SystemExit("refusing unexpected fixture username")
        found[username] = int(user_id)
    return found


def assert_existing_users_are_owned():
    actual = fixture_user_ids()
    if pinned_ids():
        require_pinned_ids(actual)
        return
    if not actual:
        return
    if set(actual) != set(USERS) or not ledger_matches():
        raise SystemExit("existing AI_ACCEPTANCE user is not owned by this fixture tool")


def ledger_matches():
    stored = stored_rows()
    adjustments = stored_adjustments()
    if extra_transaction_keys(stored) or extra_adjustment_keys(adjustments):
        return False
    if any(stored.get((case["user"], case["month"]), []) != expected_rows(case) for case in CASES):
        return False
    case = next(item for item in CASES if item["id"] == "MA-07")
    amount, when, remark = (adjustment_delta(case), case["adjustment"][1], "验收余额调整")
    return adjustments.get((case["user"], case["month"]), []) == [(amount, when, remark)]


def require_pinned_ids(actual):
    pinned = pinned_ids()
    if not pinned:
        return
    if any(actual.get(username) != pinned[username] for username in USERS):
        raise SystemExit("fixture user id does not match the private ownership record")


def pinned_ids():
    if not PRIVATE.exists():
        return {}
    values = private_values()
    found = {}
    for username in USERS:
        raw = values.get(f"{username}_USER_ID", "")
        if raw:
            found[username] = int(raw)
    if found and set(found) != set(USERS):
        raise SystemExit("private ownership record is incomplete")
    return found


def remember_ids(ids):
    if set(ids) != set(USERS):
        raise SystemExit("refusing to record an incomplete fixture user set")
    if pinned_ids() == ids:
        return
    values = private_values()
    lines = []
    for username in USERS:
        lines.append(f"{username}_USERNAME={username}")
        lines.append(f"{username}_PASSWORD={values[f'{username}_PASSWORD']}")
        lines.append(f"{username}_USER_ID={ids[username]}")
    PRIVATE.write_text("".join(f"{line}\n" for line in lines))
    PRIVATE.chmod(0o600)


def stored_rows():
    text = mysql(
        "SELECT u.username, DATE_FORMAT(t.business_time, '%Y-%m'), t.type, t.amount, "
        "IFNULL(t.name, ''), IFNULL(c.name, ''), "
        "DATE_FORMAT(t.business_time, '%Y-%m-%dT%H:%i:%s+08:00') "
        "FROM transactions t JOIN users u ON u.id = t.user_id "
        "LEFT JOIN categories c ON c.id = t.category_id "
        "WHERE u.username IN ('AI_ACCEPTANCE_A','AI_ACCEPTANCE_B') "
        "ORDER BY u.username, t.business_time, t.id"
    )
    grouped = {}
    for line in text.splitlines():
        username, month, kind, amount, name, category, when = line.split("\t")
        grouped.setdefault((username, month), []).append(
            (kind, normalize_amount(amount), name or None, category or None, when))
    return {key: sorted(rows, key=row_key) for key, rows in grouped.items()}


def stored_adjustments():
    text = mysql(
        "SELECT u.username, DATE_FORMAT(b.business_time, '%Y-%m'), b.adjustment_amount, "
        "DATE_FORMAT(b.business_time, '%Y-%m-%dT%H:%i:%s+08:00'), IFNULL(b.remark, '') "
        "FROM balance_adjustments b JOIN users u ON u.id = b.user_id "
        "WHERE u.username IN ('AI_ACCEPTANCE_A','AI_ACCEPTANCE_B') "
        "ORDER BY u.username, b.business_time, b.id"
    )
    grouped = {}
    for line in text.splitlines():
        username, month, amount, when, remark = line.split("\t")
        grouped.setdefault((username, month), []).append((normalize_amount(amount), when, remark))
    return grouped


def normalize_amount(value):
    negative = value.startswith("-")
    whole, _, fraction = value.lstrip("-").partition(".")
    return f"{'-' if negative else ''}{int(whole)}.{(fraction + '00')[:2]}"


def self_check():
    sample = [("INCOME", "1.00", "基本工资", "工资", "2026-01-01T08:00:00+08:00")]
    assert month_action([], sample) == "create"
    assert month_action(sample, sample) == "reuse"
    assert month_action(sample, []) == "stop"
    assert month_action([("INCOME", "9.00", "基本工资", "工资", "2026-01-01T08:00:00+08:00")], sample) == "stop"
    assert month_action([("INCOME", "1.00", "基本工资", "工资", "2026-01-02T08:00:00+08:00")], sample) == "stop"
    later = sample + [("EXPENSE", "1.00", "日用品", "购物", "2026-01-03T08:00:00+08:00")]
    assert month_action(later, sample) == "stop"
    assert extra_transaction_keys({("AI_ACCEPTANCE_A", "2026-08"): sample}) == [("AI_ACCEPTANCE_A", "2026-08")]
    assert extra_transaction_keys({}) == []
    outside = {("AI_ACCEPTANCE_B", "2026-08"): [("-1.00", "2026-08-01T08:00:00+08:00", "其他")]}
    assert extra_adjustment_keys(outside) == [("AI_ACCEPTANCE_B", "2026-08")]
    try:
        require_no_extra_adjustments(outside)
    except SystemExit as error:
        assert "unexpected fixture adjustments" in str(error)
    else:
        raise AssertionError("extra adjustment was accepted")
    assert extra_adjustment_keys({("AI_ACCEPTANCE_A", "2026-07"): [expected_adjustment_later()]}) == []
    assert datasource_refusal("jdbc:mysql://db.example.com:3306/bookkeeping") == "refusing remote datasource"
    assert datasource_refusal("jdbc:mysql://10.1.2.3:3306/bookkeeping") == "refusing remote datasource"
    assert datasource_refusal("jdbc:mysql://127.0.0.1:3306/bookkeeping") is None
    assert datasource_refusal("jdbc:mysql://localhost:3306/bookkeeping") is None
    assert datasource_refusal("jdbc:mysql://localhost/bookkeeping") is None
    assert not application_classes_ready(Path("/tmp/bookkeeping-fixtures-missing"))
    case = next(item for item in CASES if item["id"] == "MA-07")
    assert case["adjustment"][0] == "10.00" and case["adjustment"][1] == "2026-07-15T08:00:00+08:00"
    assert adjustment_delta(case) == "-150.00"
    assert adjustment_delta(case) != case["adjustment"][0]
    expected_adjustment = (adjustment_delta(case), case["adjustment"][1], "验收余额调整")
    assert adjustment_action([], case) == "create"
    assert adjustment_action([expected_adjustment], case) == "reuse"
    assert adjustment_action([("10.00", case["adjustment"][1], "验收余额调整")], case) == "stop"
    assert sum(len(item["rows"]) for item in CASES) == 11
    assert sum(1 for item in CASES if item.get("adjustment")) == 1
    assert [item["id"] for item in CASES] == ["MA-01", "MA-02", "MA-03", "MA-04", "MA-05", "MA-06", "MA-07", "MA-09"]
    assert normalize_amount("-20.00") == "-20.00"
    assert pending_migrations({1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}) == [12]
    assert pending_migrations({1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}, {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}) == []
    migration_self_check()
    print("self_check_ok")


def pending_migrations(applied, scripts):
    return sorted(set(scripts) - set(applied))


def expected_adjustment_later():
    case = next(item for item in CASES if item["id"] == "MA-07")
    return (adjustment_delta(case), case["adjustment"][1], "验收余额调整")


def migration_self_check():
    resolve_tools()
    compile_server()
    env = tool_env()
    env.pop("DASHSCOPE_API_KEY", None)
    env.pop("DASHSCOPE_BASE_URL", None)
    result = subprocess.run(java_command() + ["--self-check"], cwd=ROOT, env=env, capture_output=True, text=True)
    output = result.stdout + result.stderr
    if result.returncode != 0 or "migration_self_check_ok" not in result.stdout or "datasource_self_check_ok" not in result.stdout:
        raise SystemExit(output[-500:])
    if "REFUSING pending migrations" not in output or "Migrating schema" in output or "REFUSING remote datasource" not in output:
        raise SystemExit("pending migration check did not stop before startup")
    print("migration_self_check_ok")


def load_or_create_passwords(existing):
    if PRIVATE.exists():
        return read_private()
    if existing:
        raise SystemExit("fixture users already exist and the private credential file is missing")
    values = {username: secrets.token_urlsafe(18) for username in USERS}
    PRIVATE.parent.mkdir(parents=True, exist_ok=True)
    PRIVATE.write_text("".join(f"{username}_USERNAME={username}\n{username}_PASSWORD={values[username]}\n" for username in USERS))
    PRIVATE.chmod(0o600)
    return values


def private_values():
    values = {}
    for line in PRIVATE.read_text().splitlines():
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        values[name] = value
    return values


def read_private():
    values = private_values()
    for username in USERS:
        if values.get(f"{username}_USERNAME") != username or not values.get(f"{username}_PASSWORD"):
            raise SystemExit("private credential file is incomplete")
    return {username: values[f"{username}_PASSWORD"] for username in USERS}


def existing_fixture_users():
    rows = mysql("SELECT username FROM users WHERE username IN ('AI_ACCEPTANCE_A','AI_ACCEPTANCE_B') ORDER BY username")
    return [] if not rows else rows.splitlines()


def other_ledger():
    row = mysql("""
        SELECT CONCAT_WS(',',
          (SELECT COUNT(*) FROM users WHERE username NOT IN ('AI_ACCEPTANCE_A','AI_ACCEPTANCE_B')),
          (SELECT COUNT(*) FROM transactions t JOIN users u ON u.id=t.user_id WHERE u.username NOT IN ('AI_ACCEPTANCE_A','AI_ACCEPTANCE_B')),
          (SELECT COUNT(*) FROM balance_adjustments b JOIN users u ON u.id=b.user_id WHERE u.username NOT IN ('AI_ACCEPTANCE_A','AI_ACCEPTANCE_B')),
          (SELECT COUNT(*) FROM accounts a JOIN users u ON u.id=a.user_id WHERE u.username NOT IN ('AI_ACCEPTANCE_A','AI_ACCEPTANCE_B')),
          (SELECT COUNT(*) FROM categories c JOIN users u ON u.id=c.user_id WHERE u.username NOT IN ('AI_ACCEPTANCE_A','AI_ACCEPTANCE_B')),
          (SELECT COALESCE(MAX(t.id),0) FROM transactions t JOIN users u ON u.id=t.user_id WHERE u.username NOT IN ('AI_ACCEPTANCE_A','AI_ACCEPTANCE_B')))
        """)
    return row


def flyway_summary():
    return mysql("SELECT GROUP_CONCAT(CONCAT(version, ':', success) ORDER BY installed_rank SEPARATOR ' ') "
                 "FROM flyway_schema_history WHERE version IS NOT NULL")


def require_dev_schema():
    url = os.environ.get("DB_URL", "")
    refusal = datasource_refusal(url)
    if refusal:
        raise SystemExit(refusal)
    versions = flyway_summary().split()
    expected = [f"{version}:1" for version in range(1, 12)]
    if versions != expected:
        raise SystemExit("flyway is not exactly V1-V11 successful: " + " ".join(versions))
    applied = {int(item.split(":", 1)[0]) for item in versions}
    pending = pending_migrations(applied, migration_script_versions())
    if pending:
        raise SystemExit("refusing pending migrations " + " ".join(str(version) for version in pending))


def migration_script_versions():
    versions = set()
    for path in (ROOT / "src/main/resources/db/migration").glob("V*__*.sql"):
        versions.add(int(path.name.split("__", 1)[0][1:]))
    return versions


def datasource_refusal(url):
    schema = schema_name(url)
    if schema != "bookkeeping":
        return f"refusing schema {schema}"
    if database_host(url) not in LOCAL_DATABASE_HOSTS:
        return "refusing remote datasource"
    return None


def database_authority(url):
    if not url.startswith("jdbc:mysql://"):
        return ""
    rest = url.split("://", 1)[1].split("/", 1)[0].split("?", 1)[0]
    if "@" in rest:
        return ""
    return rest


def database_host(url):
    authority = database_authority(url)
    if authority.startswith("[") and "]" in authority:
        return authority[1:authority.index("]")]
    return authority.split(":", 1)[0]


def database_port(url):
    authority = database_authority(url)
    if authority.startswith("[") and "]" in authority:
        rest = authority[authority.index("]") + 1:]
        return rest[1:] if rest.startswith(":") and rest[1:] else "3306"
    if ":" in authority:
        return authority.split(":", 1)[1] or "3306"
    return "3306"


def schema_name(url):
    if not url.startswith("jdbc:mysql://"):
        return "missing"
    rest = url.split("://", 1)[1]
    name = rest.split("/", 1)[1].split("?", 1)[0] if "/" in rest else ""
    return name or "missing"


def resolve_tools():
    TOOLS["java_home"] = require_java_home()
    TOOLS["mysql"] = require_mysql_client()


def java_home():
    if "java_home" not in TOOLS:
        resolve_tools()
    return TOOLS["java_home"]


def require_java_home():
    candidates = []
    configured = os.environ.get("JAVA_HOME", "").strip()
    if configured:
        candidates.append(Path(configured))
    if BOOTSTRAP_JAVA not in candidates:
        candidates.append(BOOTSTRAP_JAVA)
    for home in candidates:
        if java_major(home) == 25:
            return home
    raise SystemExit("Java 25 is required. Set JAVA_HOME to a Java 25 installation.")


def java_major(home):
    java = home / "bin" / "java"
    if not java.is_file():
        return None
    result = subprocess.run([str(java), "-version"], capture_output=True, text=True)
    match = re.search(r'version "(\d+)', result.stderr + result.stdout)
    return int(match.group(1)) if match else None


def require_mysql_client():
    candidates = []
    found = shutil.which("mysql")
    if found:
        candidates.append(Path(found))
    if BOOTSTRAP_MYSQL not in candidates:
        candidates.append(BOOTSTRAP_MYSQL)
    for path in candidates:
        if mysql_compatible(path):
            return path
    raise SystemExit("A MySQL 8 client is required.")


def mysql_compatible(path):
    if not Path(path).is_file():
        return False
    result = subprocess.run([str(path), "--version"], capture_output=True, text=True)
    match = re.search(r"\bVer (\d+)\.", result.stdout + result.stderr)
    return match is not None and int(match.group(1)) >= 8


def tool_env():
    env = os.environ.copy()
    home = str(java_home())
    env["JAVA_HOME"] = home
    env["PATH"] = str(Path(home) / "bin") + os.pathsep + env.get("PATH", "")
    return env


def load_bootstrap():
    path = HOME / ".config" / "bookkeeping" / "bootstrap.env"
    for line in path.read_text().splitlines():
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        os.environ.setdefault(name, value.strip().strip('"').strip("'"))


def mysql(sql):
    url = os.environ["DB_URL"]
    refusal = datasource_refusal(url)
    if refusal:
        raise SystemExit(refusal)
    env = os.environ.copy()
    env["MYSQL_PWD"] = os.environ["DB_PASSWORD"]
    result = subprocess.run([
        str(TOOLS["mysql"]), "--protocol=TCP", "-h", database_host(url), "-P", database_port(url),
        "-u", os.environ["DB_USERNAME"], "-D", "bookkeeping", "-N", "-e", sql,
    ], check=True, capture_output=True, text=True, env=env)
    return result.stdout.rstrip("\r\n")


def require_loopback_listener():
    result = subprocess.run(["lsof", "-nP", f"-iTCP:{PORT}", "-sTCP:LISTEN"], capture_output=True, text=True)
    listeners = [line for line in result.stdout.splitlines() if "LISTEN" in line]
    if len(listeners) != 1 or f"127.0.0.1:{PORT}" not in listeners[0]:
        raise SystemExit("fixture server is not bound to 127.0.0.1")


def port_open():
    import socket
    with socket.socket() as sock:
        sock.settimeout(0.3)
        return sock.connect_ex(("127.0.0.1", PORT)) == 0


if __name__ == "__main__":
    main()
