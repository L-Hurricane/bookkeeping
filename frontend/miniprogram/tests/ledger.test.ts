import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { beginLoad, beginLogout, createLoadGuard, endLogout } from "../miniprogram/account/load-guard";
import { ApiError, createHttpClient, type HttpTransportRequest } from "../miniprogram/http/client";
import {
  canApplyLedgerResult,
  formatBusinessTime,
  ledgerPath,
  ledgerRows,
  parseLedger,
} from "../miniprogram/ledger/ledger";
import { currentBeijingMonth, nextMonth, previousMonth } from "../miniprogram/ledger/month";
import { saveSession } from "../miniprogram/session/session";
import { createLedgerApi } from "../miniprogram/services/ledger";
import type { KeyValueStorage } from "../miniprogram/storage/key-value";

const summary = {
  transactionCount: 3,
  netIncome: "200.00",
  netExpense: "15.50",
  surplus: "184.50",
};

function ledgerBody(items: unknown[]) {
  return {
    accountId: 1001,
    month: "2026-09",
    currentBalance: "1300.00",
    summary,
    items,
  };
}

const transaction = {
  kind: "TRANSACTION",
  id: 2,
  transactionType: "EXPENSE",
  displayName: "未分类支出",
  amount: "-20.00",
  category: null,
  tags: [{ id: 9, name: "外卖" }],
  businessTime: "2026-09-12T20:00:00+08:00",
};

const adjustment = {
  kind: "BALANCE_ADJUSTMENT",
  id: 8,
  displayName: "余额调整",
  amount: "37.50",
  businessTime: "2026-09-20T12:00:00+08:00",
};

describe("beijing month", () => {
  it("uses the Beijing calendar month across the month boundary", () => {
    assert.equal(currentBeijingMonth(new Date("2026-09-30T15:59:59Z")), "2026-09");
    assert.equal(currentBeijingMonth(new Date("2026-09-30T16:00:00Z")), "2026-10");
  });

  it("moves across years", () => {
    assert.equal(previousMonth("2026-01"), "2025-12");
    assert.equal(nextMonth("2026-12"), "2027-01");
    assert.equal(previousMonth("2026-09"), "2026-08");
    assert.equal(nextMonth("2026-09"), "2026-10");
  });
});

describe("ledger contract", () => {
  it("parses a transaction, an adjustment, and a null category without recalculating summary", () => {
    const ledger = parseLedger(ledgerBody([adjustment, transaction]));
    assert.equal(ledger?.summary.netExpense, "15.50");
    assert.equal(ledger?.summary.surplus, "184.50");
    assert.equal(ledger?.items[1]?.kind, "TRANSACTION");
    if (ledger?.items[1]?.kind === "TRANSACTION") {
      assert.equal(ledger.items[1].category, null);
      assert.equal(ledger.items[1].amount, "-20.00");
      assert.equal(ledger.items[1].transactionType, "EXPENSE");
    }
    const rows = ledgerRows(ledger?.items ?? []);
    assert.equal(rows[0]?.typeLabel, "调整");
    assert.equal(rows[0]?.amountLabel, "¥37.50");
    assert.equal(rows[1]?.typeLabel, "支出");
    assert.equal(rows[1]?.amountLabel, "¥-20.00");
    assert.equal(rows[1]?.detail, "未分类 外卖");
    assert.equal(formatBusinessTime("2026-09-05T09:00:00+08:00"), "09-05 09:00");
    assert.equal(ledger?.summary.transactionCount, 3);
    assert.equal(ledger?.summary.netIncome, "200.00");
    assert.equal(ledger?.summary.netExpense, "15.50");
    assert.equal(ledger?.summary.surplus, "184.50");
  });

  it("shows a nameless uncategorized transaction without treating the placeholder as data", () => {
    const ledger = parseLedger(ledgerBody([{
      kind: "TRANSACTION",
      id: 3,
      transactionType: "EXPENSE",
      amount: "10.00",
      tags: [],
      businessTime: "2026-08-31T23:59:00+08:00",
    }]));
    assert.equal(ledger?.items[0]?.kind, "TRANSACTION");
    if (ledger?.items[0]?.kind === "TRANSACTION") {
      assert.equal(ledger.items[0].displayName, null);
      assert.equal(ledger.items[0].category, null);
    }
    const row = ledgerRows(ledger?.items ?? [])[0];
    assert.equal(row?.title, "未命名交易");
    assert.equal(row?.detail.includes("未分类"), true);
  });

  it("requires tags to be an array", () => {
    const { tags, ...missingTags } = transaction;
    assert.equal(parseLedger(ledgerBody([missingTags])), null);
    assert.equal(parseLedger(ledgerBody([{ ...transaction, tags: null }])), null);
    assert.notEqual(parseLedger(ledgerBody([{ ...transaction, tags: [] }])), null);
    assert.equal(tags.length, 1);
  });

  it("rejects invalid money, kind, summary, accountId, and month", () => {
    assert.equal(parseLedger(ledgerBody([{ ...transaction, amount: "20" }])), null);
    assert.equal(parseLedger(ledgerBody([{ ...transaction, kind: "NOTE" }])), null);
    assert.equal(parseLedger({ ...ledgerBody([transaction]), summary: { ...summary, surplus: "184.5" } }), null);
    assert.equal(parseLedger({ ...ledgerBody([transaction]), accountId: 0 }), null);
    assert.equal(parseLedger({ ...ledgerBody([transaction]), month: "2026-9" }), null);
  });

  it("keeps an empty month as an empty item list with zero summary strings", () => {
    const ledger = parseLedger({
      ...ledgerBody([]),
      summary: { transactionCount: 0, netIncome: "0.00", netExpense: "0.00", surplus: "0.00" },
    });
    assert.equal(ledger?.items.length, 0);
    assert.equal(ledger?.summary.transactionCount, 0);
    assert.equal(ledger?.summary.netIncome, "0.00");
  });
});

describe("ledger month requests", () => {
  it("requests the selected month and ignores a stale response", async () => {
    const paths: string[] = [];
    const api = apiFor((request) => {
      paths.push(request.url);
      const month = new URL(request.url).searchParams.get("month");
      return { statusCode: 200, data: { ...ledgerBody([]), month } };
    });
    assert.equal(ledgerPath(1001, previousMonth("2026-09")), "/api/v1/accounts/1001/ledger?month=2026-08");
    assert.equal(ledgerPath(1001, nextMonth("2026-09")), "/api/v1/accounts/1001/ledger?month=2026-10");
    const september = await api.getLedger(1001, "2026-09");
    const august = await api.getLedger(1001, "2026-08");
    assert.equal(september.month, "2026-09");
    assert.equal(august.month, "2026-08");
    assert.equal(paths[0]?.endsWith("month=2026-09"), true);
    assert.equal(paths[1]?.endsWith("month=2026-08"), true);

    const guard = createLoadGuard();
    const older = beginLoad(guard);
    const newer = beginLoad(guard);
    assert.equal(canApplyLedgerResult(guard, older ?? 0, "2026-09", "2026-10"), false);
    assert.equal(canApplyLedgerResult(guard, newer ?? 0, "2026-10", "2026-10"), true);
  });

  it("drops an in-flight ledger after logout starts", () => {
    const guard = createLoadGuard();
    const generation = beginLoad(guard);
    assert.equal(beginLogout(guard), true);
    endLogout(guard);
    assert.equal(canApplyLedgerResult(guard, generation ?? 0, "2026-09", "2026-09"), false);
  });

  it("clears the session and starts login on ledger 401", async () => {
    const storage = memoryStorage();
    saveSession(storage, { token: "opaque-token", expiresAt: "2099-01-01T00:00:00Z" });
    let unauthorized = 0;
    const http = createHttpClient({
      baseUrl: "http://127.0.0.1:8080",
      storage,
      now: () => new Date("2026-09-25T08:00:00Z"),
      onUnauthorized: () => {
        unauthorized += 1;
      },
      transport: async () => ({
        statusCode: 401,
        data: { error: { code: "SESSION_INVALID", message: "会话无效或已过期", requestId: "req-ledger" } },
      }),
    });
    await assert.rejects(() => createLedgerApi(http).getLedger(1001, "2026-09"), (error: unknown) => {
      return error instanceof ApiError && error.status === 401;
    });
    assert.equal(unauthorized, 1);
  });
});

function memoryStorage(): KeyValueStorage {
  let value: string | undefined;
  return {
    getItem() {
      return value;
    },
    setItem(_key, next) {
      value = next;
    },
    removeItem() {
      value = undefined;
    },
  };
}

function apiFor(responder: (request: HttpTransportRequest) => { statusCode: number; data: unknown }) {
  const storage = memoryStorage();
  saveSession(storage, { token: "opaque-token", expiresAt: "2099-01-01T00:00:00Z" });
  const http = createHttpClient({
    baseUrl: "http://127.0.0.1:8080",
    storage,
    now: () => new Date("2026-09-25T08:00:00Z"),
    onUnauthorized: () => undefined,
    transport: async (request) => responder(request),
  });
  return createLedgerApi(http);
}
