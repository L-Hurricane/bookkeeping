import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { createLoadGuard, beginLoad } from "../miniprogram/account/load-guard";
import { canApplyLedgerResult, ledgerPath, type LedgerEntry } from "../miniprogram/ledger/ledger";
import {
  LOCATE_MISS_MESSAGE,
  acceptTransactionCreated,
  centeredScrollTop,
  canRunLocate,
  changeSelectedMonth,
  clearHighlight,
  finishHighlight,
  handoffPostSave,
  idleHighlight,
  locateTransaction,
  nextLocateGeneration,
  parsePostSaveResult,
  settleLedger,
  startHighlight,
} from "../miniprogram/ledger/post-save";
import type { PostSaveResult } from "../miniprogram/transaction/form";

const saved: PostSaveResult = { transactionId: 4001, targetMonth: "2026-08" };
const home = { selectedMonth: "2026-09", pending: null, highlightKey: "" };

describe("post-save handoff", () => {
  it("accepts one valid result and ignores an invalid payload", () => {
    const accepted = acceptTransactionCreated(home, saved);
    assert.deepEqual(accepted.pending, saved);
    assert.equal(accepted.selectedMonth, "2026-08");
    assert.equal(ledgerPath(1001, accepted.selectedMonth), "/api/v1/accounts/1001/ledger?month=2026-08");
    assert.equal(acceptTransactionCreated(home, { transactionId: 0, targetMonth: "2026-08" }), home);
    assert.equal(parsePostSaveResult({ transactionId: 4001, targetMonth: "2026-13" }), null);
    assert.equal(parsePostSaveResult({ transactionId: 1.2, targetMonth: "2026-08" }), null);
  });

  it("emits a successful save once and skips uncertain, conflict, and editing", () => {
    const first = handoffPostSave("success", saved, false);
    const second = handoffPostSave("success", saved, first.completed);
    assert.deepEqual(first.result, saved);
    assert.equal(second.result, null);
    assert.equal(handoffPostSave("uncertain", saved, false).result, null);
    assert.equal(handoffPostSave("conflict", saved, false).result, null);
    assert.equal(handoffPostSave("editing", saved, false).result, null);
    assert.equal(handoffPostSave("success", null, false).result, null);
  });
});

describe("ledger locate", () => {
  it("uses transaction id and keeps the first row at the top", () => {
    const items = [transaction(10), transaction(20), transaction(30), adjustment(20)];
    assert.deepEqual(locateTransaction(items, 10), { key: "transaction-10", index: 0, disposition: "top" });
    assert.deepEqual(locateTransaction(items, 20), { key: "transaction-20", index: 1, disposition: "center" });
    assert.equal(locateTransaction(items, 30)?.disposition, "center");
    assert.equal(locateTransaction([adjustment(20)], 20), null);
    assert.equal(centeredScrollTop(800, 40, 600), 520);
    assert.equal(centeredScrollTop(10, 40, 600), 0);
  });

  it("keeps the pending locate through a ledger error and clears it after the id is found", () => {
    const pending = acceptTransactionCreated(home, saved).pending;
    const failed = settleLedger(pending, "error", []);
    assert.equal(failed.pending, pending);
    assert.equal(failed.locate, null);
    const found = settleLedger(failed.pending, "success", [transaction(4001)]);
    assert.equal(found.pending, null);
    assert.equal(found.locate?.key, "transaction-4001");
    assert.equal(found.locate?.disposition, "top");
    assert.equal(found.missMessage, "");
    const missing = settleLedger(pending, "success", [transaction(10)]);
    assert.equal(missing.pending, pending);
    assert.equal(missing.locate, null);
    assert.equal(missing.missMessage, LOCATE_MISS_MESSAGE);
  });

  it("drops the pending month when the user switches months", () => {
    const pendingHome = acceptTransactionCreated(home, saved);
    const switched = changeSelectedMonth(pendingHome, "2026-09");
    assert.equal(switched.pending, null);
    assert.equal(switched.highlightKey, "");
    assert.equal(switched.selectedMonth, "2026-09");
    const guard = createLoadGuard();
    const generation = beginLoad(guard);
    assert.equal(canApplyLedgerResult(guard, generation ?? 0, "2026-08", switched.selectedMonth), false);
    const scheduled = nextLocateGeneration(0);
    const afterSwitch = nextLocateGeneration(scheduled);
    assert.equal(canRunLocate(true, scheduled, afterSwitch), false);
    assert.equal(canRunLocate(false, afterSwitch, afterSwitch), false);
    assert.equal(canRunLocate(true, afterSwitch, afterSwitch), true);
  });

  it("does not let an older highlight timer clear a newer highlight", () => {
    const first = startHighlight(idleHighlight(), "transaction-4001");
    const second = startHighlight(first, "transaction-4002");
    assert.equal(first.key, "transaction-4001");
    assert.equal(finishHighlight(second, first.generation).key, "transaction-4002");
    assert.equal(finishHighlight(second, second.generation).key, "");
    const unloaded = clearHighlight(second);
    assert.equal(unloaded.key, "");
    assert.equal(finishHighlight(unloaded, second.generation).key, "");
  });
});

function transaction(id: number): LedgerEntry {
  return {
    kind: "TRANSACTION",
    id,
    transactionType: "EXPENSE",
    displayName: null,
    amount: "30.00",
    category: null,
    tags: [],
    businessTime: "2026-08-20T12:00:00+08:00",
  };
}

function adjustment(id: number): LedgerEntry {
  return {
    kind: "BALANCE_ADJUSTMENT",
    id,
    displayName: "调整",
    amount: "1.00",
    businessTime: "2026-08-20T12:00:00+08:00",
  };
}
