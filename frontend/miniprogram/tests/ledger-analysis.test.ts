import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { parseLedgerAnalysis } from "../miniprogram/analysis/ledger-contract";
import { ledgerAnalysisPagePath } from "../miniprogram/analysis/navigation";

describe("ledger analysis", () => {
  it("keeps the encoded period and the decoded title period the same", () => {
    assert.equal(
      ledgerAnalysisPagePath("MONTH", "2026-10"),
      "/pages/monthly-analysis/index?scope=ledger&periodKind=MONTH&period=2026-10",
    );
    const parsed = parseLedgerAnalysis({
      periodKind: "YEAR",
      period: "2025",
      fingerprint: "ledger-1:abc",
      emptyPeriod: false,
      viewState: "NO_ANALYSIS",
      opening: "1.00",
      closing: "2.00",
      change: "1.00",
      incomeNet: "3.00",
      expenseNet: "1.00",
      incomeCount: 1,
      expenseCount: 1,
      priorPeriod: "2024",
      priorChange: "0.00",
      priorIncomeNet: "0.00",
      priorExpenseNet: "0.00",
      snapshot: null,
    }, "YEAR", "2025");
    assert.equal(parsed?.opening, "1.00");
    assert.equal(parsed?.snapshot, null);
    assert.equal(parsed?.generation, null);
    const omitted = Object.fromEntries(Object.entries({
      periodKind: "YEAR",
      period: "2025",
      fingerprint: "ledger-1:abc",
      emptyPeriod: false,
      viewState: "NO_ANALYSIS",
      opening: "1.00",
      closing: "2.00",
      change: "1.00",
      incomeNet: "3.00",
      expenseNet: "1.00",
      incomeCount: 1,
      expenseCount: 1,
      priorPeriod: "2024",
      priorChange: "0.00",
      priorIncomeNet: "0.00",
      priorExpenseNet: "0.00",
      snapshot: null,
      generation: null,
    }).filter(([, value]) => value !== null));
    const parsedOmitted = parseLedgerAnalysis(omitted, "YEAR", "2025");
    assert.equal(parsedOmitted?.viewState, "NO_ANALYSIS");
    assert.equal(parsedOmitted?.snapshot, null);
    assert.equal(parsedOmitted?.generation, null);
    assert.equal(parseLedgerAnalysis({ periodKind: "QUARTER", period: "2025" }, "YEAR", "2025"), null);
  });
});
