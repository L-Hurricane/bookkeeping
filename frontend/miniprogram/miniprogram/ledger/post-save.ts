import type { LedgerEntry } from "./ledger";
import type { PostSaveResult } from "../transaction/form";
import type { SubmissionPhase } from "../transaction/submission";

const MONTH_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;

export const LOCATE_MISS_MESSAGE = "交易已保存，但暂未定位到记录";
export const HIGHLIGHT_MS = 2500;

export interface LocateNavigation {
  readonly selectedMonth: string;
  readonly pending: PostSaveResult | null;
  readonly highlightKey: string;
}

export interface LocatedTransaction {
  readonly key: string;
  readonly index: number;
  readonly disposition: "top" | "center";
}

export interface LedgerSettlement {
  readonly pending: PostSaveResult | null;
  readonly locate: LocatedTransaction | null;
  readonly missMessage: string;
}

export interface HighlightState {
  readonly key: string;
  readonly generation: number;
}

export function parsePostSaveResult(value: unknown): PostSaveResult | null {
  if (!isRecord(value) || !isPositiveId(value.transactionId) || typeof value.targetMonth !== "string" || !MONTH_PATTERN.test(value.targetMonth)) {
    return null;
  }
  return { transactionId: value.transactionId, targetMonth: value.targetMonth };
}

export function acceptTransactionCreated(state: LocateNavigation, payload: unknown): LocateNavigation {
  const pending = parsePostSaveResult(payload);
  if (pending === null) {
    return state;
  }
  return { selectedMonth: pending.targetMonth, pending, highlightKey: "" };
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
  return { completed: true, result: { transactionId: postSave.transactionId, targetMonth: postSave.targetMonth } };
}

export function settleLedger(pending: PostSaveResult | null, outcome: "success" | "error", items: readonly LedgerEntry[]): LedgerSettlement {
  if (pending === null || outcome === "error") {
    return { pending, locate: null, missMessage: "" };
  }
  const locate = locateTransaction(items, pending.transactionId);
  if (locate === null) {
    return { pending, locate: null, missMessage: LOCATE_MISS_MESSAGE };
  }
  return { pending: null, locate, missMessage: "" };
}

export function locateTransaction(items: readonly LedgerEntry[], transactionId: number): LocatedTransaction | null {
  const index = items.findIndex((item) => item.kind === "TRANSACTION" && item.id === transactionId);
  if (index < 0) {
    return null;
  }
  return {
    key: `transaction-${transactionId}`,
    index,
    disposition: index === 0 ? "top" : "center",
  };
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
