import type { LedgerEntry } from "./ledger";
import type { PostSaveResult } from "../transaction/form";
import type { SubmissionPhase } from "../transaction/submission";

const MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;

export const LOCATE_MISS_MESSAGE = "交易已保存，但暂未定位到记录";
export const ADJUSTMENT_LOCATE_MISS_MESSAGE = "余额调整已保存，但暂未定位到记录";
export const HIGHLIGHT_MS = 2500;

export interface LedgerLocateIntent {
  readonly kind: "transaction" | "adjustment";
  readonly recordId: number;
  readonly targetMonth: string;
}

export interface LocateNavigation {
  readonly selectedMonth: string;
  readonly pending: LedgerLocateIntent | null;
  readonly highlightKey: string;
}

export interface LocatedTransaction {
  readonly key: string;
  readonly index: number;
  readonly disposition: "top" | "center";
}

export interface LedgerSettlement {
  readonly pending: LedgerLocateIntent | null;
  readonly locate: LocatedTransaction | null;
  readonly missMessage: string;
}

export interface HighlightState {
  readonly key: string;
  readonly generation: number;
}

export function parsePostSaveResult(value: unknown): PostSaveResult | null {
  if (
    !isRecord(value) ||
    !isPositiveId(value.transactionId) ||
    typeof value.targetMonth !== "string" ||
    !MONTH_PATTERN.test(value.targetMonth)
  ) {
    return null;
  }
  const accountId = value.accountId;
  if (accountId !== undefined && !isPositiveId(accountId)) {
    return null;
  }
  return {
    accountId: typeof accountId === "number" ? accountId : 0,
    transactionId: value.transactionId,
    targetMonth: value.targetMonth,
  };
}

export function acceptTransactionCreated(state: LocateNavigation, payload: unknown): LocateNavigation {
  const saved = parsePostSaveResult(payload);
  if (saved === null) {
    return state;
  }
  return acceptLocate({ kind: "transaction", recordId: saved.transactionId, targetMonth: saved.targetMonth });
}

export function parseAdjustmentCreated(value: unknown): LedgerLocateIntent | null {
  if (!isRecord(value) || value.kind !== "adjustment" || !isPositiveId(value.recordId) || typeof value.targetMonth !== "string" || !MONTH_PATTERN.test(value.targetMonth)) {
    return null;
  }
  return { kind: "adjustment", recordId: value.recordId, targetMonth: value.targetMonth };
}

export function acceptAdjustmentCreated(state: LocateNavigation, payload: unknown): LocateNavigation {
  const pending = parseAdjustmentCreated(payload);
  if (pending === null) {
    return state;
  }
  return acceptLocate(pending);
}

function acceptLocate(pending: LedgerLocateIntent): LocateNavigation {
  return { selectedMonth: pending.targetMonth, pending, highlightKey: "" };
}

export function applyAcceptedLocate(current: LocateNavigation, next: LocateNavigation): LocateNavigation | null {
  return next === current ? null : next;
}

export function changeSelectedMonth(state: LocateNavigation, month: string): LocateNavigation {
  if (month === state.selectedMonth) {
    return state;
  }
  return { selectedMonth: month, pending: null, highlightKey: "" };
}

export function handoffPostSave(
  phase: SubmissionPhase,
  postSave: PostSaveResult | null,
  completed: boolean,
): { readonly completed: boolean; readonly result: PostSaveResult | null } {
  if (completed || phase !== "success" || postSave === null) {
    return { completed, result: null };
  }
  return {
    completed: true,
    result: {
      accountId: postSave.accountId,
      transactionId: postSave.transactionId,
      targetMonth: postSave.targetMonth,
    },
  };
}

export function settleLedger(pending: LedgerLocateIntent | null, outcome: "success" | "error", items: readonly LedgerEntry[]): LedgerSettlement {
  if (pending === null || outcome === "error") {
    return { pending, locate: null, missMessage: "" };
  }
  const locate = locateLedgerRecord(items, pending);
  if (locate === null) {
    return { pending, locate: null, missMessage: pending.kind === "adjustment" ? ADJUSTMENT_LOCATE_MISS_MESSAGE : LOCATE_MISS_MESSAGE };
  }
  return { pending: null, locate, missMessage: "" };
}

export function locateLedgerRecord(items: readonly LedgerEntry[], intent: LedgerLocateIntent): LocatedTransaction | null {
  const wanted = intent.kind === "transaction" ? "TRANSACTION" : "BALANCE_ADJUSTMENT";
  const index = items.findIndex((item) => item.kind === wanted && item.id === intent.recordId);
  if (index < 0) {
    return null;
  }
  const prefix = intent.kind === "transaction" ? "transaction" : "adjustment";
  return { key: `${prefix}-${intent.recordId}`, index, disposition: index === 0 ? "top" : "center" };
}

export function locateTransaction(items: readonly LedgerEntry[], transactionId: number): LocatedTransaction | null {
  return locateLedgerRecord(items, { kind: "transaction", recordId: transactionId, targetMonth: "2000-01" });
}

export function centeredScrollTop(itemPageTop: number, itemHeight: number, viewportHeight: number): number {
  const raw = itemPageTop - (viewportHeight - itemHeight) / 2;
  if (!Number.isFinite(raw) || raw <= 0) {
    return 0;
  }
  return raw;
}

export function nextLocateGeneration(current: number): number {
  return current + 1;
}

export function canRunLocate(pageAlive: boolean, capturedGeneration: number, currentGeneration: number): boolean {
  return pageAlive && capturedGeneration === currentGeneration;
}

export function idleHighlight(): HighlightState {
  return { key: "", generation: 0 };
}

export function startHighlight(state: HighlightState, key: string): HighlightState {
  return { key, generation: state.generation + 1 };
}

export function finishHighlight(state: HighlightState, generation: number): HighlightState {
  if (state.generation !== generation) {
    return state;
  }
  return { key: "", generation: state.generation };
}

export function clearHighlight(state: HighlightState): HighlightState {
  return { key: "", generation: state.generation + 1 };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isPositiveId(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}
