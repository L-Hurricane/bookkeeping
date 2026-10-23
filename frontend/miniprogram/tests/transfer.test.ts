import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ledgerRows, parseLedger } from "../miniprogram/ledger/ledger";
import {
  acceptTransferCreated,
  locateLedgerRecord,
  resolveTransferTargetMonth,
  TRANSFER_LOCATE_MISS_MESSAGE,
} from "../miniprogram/ledger/post-save";
import { moneyToneClass } from "../miniprogram/account/money-tone";
import { transferDetailReadOnly } from "../miniprogram/transfer/detail";
import { parseTransferView } from "../miniprogram/transfer/contract";
import { positiveTransferAmount } from "../miniprogram/transfer/money";
import {
  changeFromBusinessTime,
  changeToBusinessTime,
  initialTransferTimes,
  resyncTransferTimes,
  unsyncTransferTimes,
} from "../miniprogram/transfer/time-sync";
import {
  SAME_ACCOUNT_MESSAGE,
  NON_POSITIVE_AMOUNT_MESSAGE,
  buildTransferSnapshot,
  beginTransfer,
  editingTransfer,
  retryTransfer,
} from "../miniprogram/transfer/submission";

const summary = {
  transactionCount: 1,
  netIncome: "10.00",
  netExpense: "0.00",
  surplus: "10.00",
};

function ledgerBody(items: unknown[]) {
  return {
    accountId: 1,
    month: "2026-09",
    currentBalance: "100.00",
    summary,
    items,
  };
}

describe("transfer ledger", () => {
  it("parses transfer rows without transactionType, category, or tags", () => {
    const items = [
      { kind: "TRANSFER", id: 9, displayName: "转账", amount: "-50.00", businessTime: "2026-09-10T10:00:00+08:00" },
      { kind: "TRANSFER", id: 10, displayName: "转账", amount: "25.00", businessTime: "2026-09-11T11:00:00+08:00" },
    ];
    const ledger = parseLedger(ledgerBody(items));
    assert.equal(ledger?.items.length, 2);
    assert.equal(ledger?.items[0]?.kind, "TRANSFER");
    const rows = ledgerRows(ledger?.items ?? []);
    assert.equal(rows[0]?.kind, "transfer");
    assert.equal(rows[0]?.typeLabel, "转账");
    assert.equal(rows[0]?.title, "转账");
    assert.equal(rows[0]?.amountLabel, "¥-50.00");
    assert.equal(rows[0]?.amountToneClass, "money-negative");
    assert.equal(rows[1]?.amountToneClass, "money-positive");
    assert.equal(moneyToneClass("0.00"), "money-zero");
    assert.equal(rows[0]?.key, "transfer-9");
    assert.equal(rows[1]?.key, "transfer-10");
  });

  it("keeps server item order", () => {
    const ledger = parseLedger(
      ledgerBody([
        { kind: "TRANSFER", id: 2, displayName: "转账", amount: "1.00", businessTime: "2026-09-01T00:00:00+08:00" },
        {
          kind: "TRANSACTION",
          id: 1,
          transactionType: "INCOME",
          amount: "2.00",
          tags: [],
          businessTime: "2026-09-02T00:00:00+08:00",
        },
      ]),
    );
    const rows = ledgerRows(ledger?.items ?? []);
    assert.equal(rows[0]?.kind, "transfer");
    assert.equal(rows[1]?.kind, "transaction");
  });
});

describe("transfer post-save", () => {
  it("resolves target month from the source side and locates transfer rows", () => {
    const payload = {
      sourceAccountId: 100,
      transferId: 55,
      fromAccountId: 100,
      toAccountId: 200,
      fromBusinessTime: "2026-08-15T12:00:00+08:00",
      toBusinessTime: "2026-09-01T00:00:00+08:00",
    };
    assert.equal(resolveTransferTargetMonth(100, payload), "2026-08");
    assert.equal(resolveTransferTargetMonth(200, payload), "2026-09");
    assert.equal(resolveTransferTargetMonth(300, payload), "2026-09");
    const home = { selectedMonth: "2026-09", pending: null, highlightKey: "" };
    const accepted = acceptTransferCreated(home, payload);
    assert.equal(accepted.selectedMonth, "2026-08");
    const items = [
      {
        kind: "TRANSFER" as const,
        id: 55,
        displayName: "转账",
        amount: "-10.00",
        businessTime: "2026-08-15T12:00:00+08:00",
      },
    ];
    assert.equal(locateLedgerRecord(items, accepted.pending!)?.key, "transfer-55");
    assert.equal(TRANSFER_LOCATE_MISS_MESSAGE.includes("转账"), true);
  });
});

describe("transfer validation and idempotency", () => {
  it("rejects same accounts and non-positive amounts before submit", () => {
    const same = buildTransferSnapshot(1, 1, "10.00", "2026-09-01T00:00:00+08:00", "2026-09-01T00:00:00+08:00");
    assert.equal(same.ok, false);
    if (!same.ok) {
      assert.equal(same.message, SAME_ACCOUNT_MESSAGE);
    }
    const zero = buildTransferSnapshot(1, 2, "0", "2026-09-01T00:00:00+08:00", "2026-09-01T00:00:00+08:00");
    assert.equal(zero.ok, false);
    if (!zero.ok) {
      assert.equal(zero.message, NON_POSITIVE_AMOUNT_MESSAGE);
    }
    assert.equal(positiveTransferAmount("-1.00"), null);
    assert.equal(positiveTransferAmount("12.50"), "12.50");
  });

  it("reuses the same idempotency key while retrying an uncertain save", () => {
    const snapshot = {
      fromAccountId: 1,
      toAccountId: 2,
      amount: "10.00",
      fromBusinessTime: "2026-09-01T00:00:00+08:00",
      toBusinessTime: "2026-09-01T00:00:00+08:00",
    };
    const started = beginTransfer(editingTransfer(), snapshot, "abc123");
    assert.equal(started?.phase, "submitting");
    const uncertain = { ...started!, phase: "uncertain" as const };
    const retried = retryTransfer(uncertain);
    assert.equal(retried?.attempt?.idempotencyKey, "abc123");
    assert.equal(retried?.attempt?.snapshot, snapshot);
  });
});

describe("transfer time sync", () => {
  it("mirrors edits while synced and restores sync from the from side", () => {
    const initial = initialTransferTimes("2026-09-01T10:00:00+08:00");
    assert.equal(initial.synced, true);
    const changed = changeFromBusinessTime(initial, "2026-09-02T11:00:00+08:00");
    assert.equal(changed.toBusinessTime, "2026-09-02T11:00:00+08:00");
    const split = unsyncTransferTimes(changed);
    const toOnly = changeToBusinessTime(split, "2026-09-03T12:00:00+08:00");
    assert.equal(toOnly.fromBusinessTime, "2026-09-02T11:00:00+08:00");
    const restored = resyncTransferTimes(toOnly);
    assert.equal(restored.synced, true);
    assert.equal(restored.toBusinessTime, restored.fromBusinessTime);
  });
});

describe("transfer detail access", () => {
  it("is read-only when the source or linked account is archived", () => {
    assert.equal(transferDetailReadOnly("ARCHIVED", "ACTIVE", "ACTIVE"), true);
    assert.equal(transferDetailReadOnly("ACTIVE", "ARCHIVED", "ACTIVE"), true);
    assert.equal(transferDetailReadOnly("ACTIVE", "ACTIVE", "ARCHIVED"), true);
    assert.equal(transferDetailReadOnly("ACTIVE", "ACTIVE", "ACTIVE"), false);
  });
});

describe("transfer api contract", () => {
  it("parses a transfer view without firstSavedAt", () => {
    const view = parseTransferView({
      id: 7,
      fromAccountId: 1,
      toAccountId: 2,
      amount: "30.00",
      fromBusinessTime: "2026-01-31T23:00:00+08:00",
      toBusinessTime: "2026-02-01T00:00:00+08:00",
      firstSaveOrder: 42,
    });
    assert.equal(view?.amount, "30.00");
    assert.equal(view?.firstSaveOrder, 42);
    assert.equal(
      parseTransferView({
        id: 7,
        fromAccountId: 1,
        toAccountId: 2,
        amount: "-1.00",
        fromBusinessTime: "2026-01-31T23:00:00+08:00",
        toBusinessTime: "2026-02-01T00:00:00+08:00",
        firstSaveOrder: 42,
      }),
      null,
    );
  });
});
