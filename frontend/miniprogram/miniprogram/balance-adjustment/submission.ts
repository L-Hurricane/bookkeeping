import { ApiError } from "../http/client";
import type { LedgerLocateIntent } from "../ledger/post-save";
import { businessMonth, isBusinessTimeAllowed, validateBusinessTime } from "../transaction/business-time";
import { normalizeOptionalText } from "../transaction/form";
import type { AdjustmentCreateResult } from "./contract";
import { canonicalMoney } from "./money";

export interface BalanceAdjustmentSnapshot {
  readonly accountId: number;
  readonly targetBalance: string;
  readonly businessTime: string;
  readonly remark: string | null;
}

export interface AdjustmentRequestBody {
  readonly targetBalance: string;
  readonly businessTime: string;
  readonly remark?: string;
}

export type AdjustmentPhase = "editing" | "submitting" | "uncertain" | "conflict" | "success";

export interface AdjustmentAttempt {
  readonly snapshot: BalanceAdjustmentSnapshot;
  readonly idempotencyKey: string;
}

export interface AdjustmentSaveState {
  readonly phase: AdjustmentPhase;
  readonly attempt: AdjustmentAttempt | null;
  readonly error: string;
  readonly postSave: LedgerLocateIntent | null;
  readonly currentBalance: string;
  readonly confirmedTarget: string | null;
}

export const ADJUSTMENT_PREPARE_MESSAGE = "无法准备保存请求，请重试";
export const ADJUSTMENT_UNCERTAIN_MESSAGE = "保存结果尚未确认";
export const ADJUSTMENT_CONFLICT_MESSAGE = "保存请求发生冲突，请重新进入余额调整页面后再试";

export function editingAdjustment(currentBalance: string): AdjustmentSaveState {
  return { phase: "editing", attempt: null, error: "", postSave: null, currentBalance, confirmedTarget: null };
}

export function adjustmentFormLocked(phase: AdjustmentPhase, preparing: boolean): boolean {
  return preparing || phase !== "editing";
}

export function canSaveAdjustment(phase: AdjustmentPhase, preparing: boolean, zero: boolean, targetReady: boolean): boolean {
  return phase === "editing" && !preparing && targetReady && !zero;
}

export function buildAdjustmentSnapshot(
  targetRaw: string,
  businessTime: string,
  remark: string,
  accountId: number,
  accountCreatedTime: string,
): { readonly ok: true; readonly value: BalanceAdjustmentSnapshot } | { readonly ok: false; readonly message: string } {
  if (!Number.isSafeInteger(accountId) || accountId <= 0) {
    return { ok: false, message: "请检查填写内容" };
  }
  const targetBalance = canonicalMoney(targetRaw);
  if (targetRaw.trim().length === 0) {
    return { ok: false, message: "请输入目标余额" };
  }
  if (targetBalance === null) {
    return { ok: false, message: "请输入最多两位小数的金额" };
  }
  if (!validateBusinessTime(businessTime)) {
    return { ok: false, message: "请输入有效的日期和时间" };
  }
  if (!isBusinessTimeAllowed(businessTime, accountCreatedTime)) {
    return { ok: false, message: "调整时间不得早于账户创建时间" };
  }
  return { ok: true, value: { accountId, targetBalance, businessTime, remark: normalizeOptionalText(remark) } };
}

export function adjustmentRequestBody(snapshot: BalanceAdjustmentSnapshot): AdjustmentRequestBody {
  return {
    targetBalance: snapshot.targetBalance,
    businessTime: snapshot.businessTime,
    ...(snapshot.remark === null ? {} : { remark: snapshot.remark }),
  };
}

export function beginAdjustment(state: AdjustmentSaveState, snapshot: BalanceAdjustmentSnapshot, idempotencyKey: string): AdjustmentSaveState | null {
  if (state.phase !== "editing") {
    return null;
  }
  return { phase: "submitting", attempt: { snapshot, idempotencyKey }, error: "", postSave: null, currentBalance: state.currentBalance, confirmedTarget: null };
}

export function retryAdjustment(state: AdjustmentSaveState): AdjustmentSaveState | null {
  if (state.phase !== "uncertain" || state.attempt === null) {
    return null;
  }
  return { ...state, phase: "submitting", error: "" };
}

export async function openAdjustment(
  state: AdjustmentSaveState,
  snapshot: BalanceAdjustmentSnapshot,
  createKey: () => Promise<string>,
): Promise<{ readonly state: AdjustmentSaveState; readonly error: string }> {
  try {
    const next = beginAdjustment(state, snapshot, await createKey());
    if (next === null) {
      return { state, error: "" };
    }
    return { state: next, error: "" };
  } catch {
    return { state, error: ADJUSTMENT_PREPARE_MESSAGE };
  }
}

export function classifyAdjustmentError(error: unknown): "definite" | "conflict" | "uncertain" | "unauthorized" {
  if (error instanceof BalanceAdjustmentResponseError) {
    return "uncertain";
  }
  if (!(error instanceof ApiError)) {
    return "uncertain";
  }
  if (error.status === 401) {
    return "unauthorized";
  }
  if (error.status === 409 && error.code === "IDEMPOTENCY_CONFLICT") {
    return "conflict";
  }
  if (error.status === 400 || error.status === 403 || error.status === 404 || error.status === 409) {
    return "definite";
  }
  return "uncertain";
}

export function adjustmentFailureMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "保存失败，请检查后重试";
  }
  if (error.code === "VALIDATION_FAILED") {
    return "请检查填写内容";
  }
  if (error.code === "BUSINESS_RULE_VIOLATION" && error.details?.some((detail) => detail.field === "businessTime" && detail.reason === "BEFORE_ACCOUNT_CREATION")) {
    return "调整时间不得早于账户创建时间";
  }
  if (error.code === "RESOURCE_NOT_FOUND") {
    return "账户已发生变化，请返回后重试";
  }
  return "保存失败，请检查后重试";
}

export function applyAdjustmentResult(state: AdjustmentSaveState, result: AdjustmentCreateResult): AdjustmentSaveState {
  if (state.phase !== "submitting" || state.attempt === null) {
    return state;
  }
  if (!result.created) {
    return {
      phase: "editing",
      attempt: null,
      error: "",
      postSave: null,
      currentBalance: result.currentBalance,
      confirmedTarget: result.targetBalance,
    };
  }
  const targetMonth = businessMonth(result.adjustment.businessTime);
  if (targetMonth === null) {
    return { ...state, phase: "uncertain", error: ADJUSTMENT_UNCERTAIN_MESSAGE };
  }
  return {
    phase: "success",
    attempt: state.attempt,
    error: "",
    postSave: { kind: "adjustment", recordId: result.adjustment.id, targetMonth },
    currentBalance: result.currentBalanceAfter,
    confirmedTarget: result.targetBalance,
  };
}

export function applyAdjustmentFailure(state: AdjustmentSaveState, kind: "definite" | "conflict" | "uncertain" | "unauthorized", error: unknown): AdjustmentSaveState {
  if (state.phase !== "submitting" || state.attempt === null) {
    return state;
  }
  if (kind === "definite") {
    return { ...editingAdjustment(state.currentBalance), error: adjustmentFailureMessage(error) };
  }
  if (kind === "conflict") {
    return { ...state, phase: "conflict", error: ADJUSTMENT_CONFLICT_MESSAGE };
  }
  if (kind === "unauthorized") {
    return { ...state, error: "" };
  }
  return { ...state, phase: "uncertain", error: ADJUSTMENT_UNCERTAIN_MESSAGE };
}

export function handoffCreatedAdjustment(
  phase: AdjustmentPhase,
  postSave: LedgerLocateIntent | null,
  completed: boolean,
): { readonly completed: boolean; readonly result: LedgerLocateIntent | null } {
  if (completed || phase !== "success" || postSave === null) {
    return { completed, result: null };
  }
  return { completed: true, result: postSave };
}

export class BalanceAdjustmentResponseError extends Error {
  constructor() {
    super("余额调整响应无法识别");
    this.name = "BalanceAdjustmentResponseError";
  }
}
