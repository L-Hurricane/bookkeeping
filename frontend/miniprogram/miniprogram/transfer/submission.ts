import { ARCHIVED_RELATED_BUSINESS_MESSAGE } from "../account/archive-copy";
import { ApiError, type ApiErrorDetail } from "../http/client";
import { validateBusinessTime } from "../transaction/business-time";
import type { TransferRequestBody, TransferView } from "./contract";
import { positiveTransferAmount } from "./money";
import { transferPostSavePayload, type TransferPostSavePayload } from "./post-save";

export interface TransferSnapshot {
  readonly fromAccountId: number;
  readonly toAccountId: number;
  readonly amount: string;
  readonly fromBusinessTime: string;
  readonly toBusinessTime: string;
}

export type TransferPhase = "editing" | "submitting" | "uncertain" | "conflict" | "success";

export interface TransferAttempt {
  readonly snapshot: TransferSnapshot;
  readonly idempotencyKey: string;
}

export interface TransferSaveState {
  readonly phase: TransferPhase;
  readonly attempt: TransferAttempt | null;
  readonly error: string;
  readonly postSave: TransferPostSavePayload | null;
}

export const TRANSFER_PREPARE_MESSAGE = "无法准备保存请求，请重试";
export const TRANSFER_UNCERTAIN_MESSAGE = "保存结果尚未确认";
export const TRANSFER_UNCERTAIN_HINT = "请重试确认，系统不会重复创建同一笔记录。";
export const TRANSFER_CONFLICT_MESSAGE = "保存请求发生冲突，请重新进入转账页面后再试";
export const SAME_ACCOUNT_MESSAGE = "请选择两个不同的账户";
export const NON_POSITIVE_AMOUNT_MESSAGE = "请输入大于 0 的金额";
export const TRANSFER_NOT_FOUND_MESSAGE = "转账不存在或已删除";

export function editingTransfer(): TransferSaveState {
  return { phase: "editing", attempt: null, error: "", postSave: null };
}

export function transferFormLocked(phase: TransferPhase, preparing: boolean): boolean {
  return preparing || phase !== "editing";
}

export function canSaveTransfer(phase: TransferPhase, preparing: boolean, snapshotReady: boolean): boolean {
  return phase === "editing" && !preparing && snapshotReady;
}

export function buildTransferSnapshot(
  fromAccountId: number,
  toAccountId: number,
  amountRaw: string,
  fromBusinessTime: string,
  toBusinessTime: string,
): { readonly ok: true; readonly value: TransferSnapshot } | { readonly ok: false; readonly message: string } {
  if (!Number.isSafeInteger(fromAccountId) || fromAccountId <= 0 || !Number.isSafeInteger(toAccountId) || toAccountId <= 0) {
    return { ok: false, message: "请检查填写内容" };
  }
  if (fromAccountId === toAccountId) {
    return { ok: false, message: SAME_ACCOUNT_MESSAGE };
  }
  const amount = positiveTransferAmount(amountRaw);
  if (amountRaw.trim().length === 0) {
    return { ok: false, message: NON_POSITIVE_AMOUNT_MESSAGE };
  }
  if (amount === null) {
    return { ok: false, message: NON_POSITIVE_AMOUNT_MESSAGE };
  }
  if (!validateBusinessTime(fromBusinessTime) || !validateBusinessTime(toBusinessTime)) {
    return { ok: false, message: "请输入有效的日期和时间" };
  }
  return {
    ok: true,
    value: { fromAccountId, toAccountId, amount, fromBusinessTime, toBusinessTime },
  };
}

export function transferRequestBody(snapshot: TransferSnapshot): TransferRequestBody {
  return {
    fromAccountId: snapshot.fromAccountId,
    toAccountId: snapshot.toAccountId,
    amount: snapshot.amount,
    fromBusinessTime: snapshot.fromBusinessTime,
    toBusinessTime: snapshot.toBusinessTime,
  };
}

export function beginTransfer(state: TransferSaveState, snapshot: TransferSnapshot, idempotencyKey: string): TransferSaveState | null {
  if (state.phase !== "editing") {
    return null;
  }
  return { phase: "submitting", attempt: { snapshot, idempotencyKey }, error: "", postSave: null };
}

export function retryTransfer(state: TransferSaveState): TransferSaveState | null {
  if (state.phase !== "uncertain" || state.attempt === null) {
    return null;
  }
  return { ...state, phase: "submitting", error: "" };
}

export async function openTransfer(
  state: TransferSaveState,
  snapshot: TransferSnapshot,
  createKey: () => Promise<string>,
): Promise<{ readonly state: TransferSaveState; readonly error: string }> {
  try {
    const next = beginTransfer(state, snapshot, await createKey());
    if (next === null) {
      return { state, error: "" };
    }
    return { state: next, error: "" };
  } catch {
    return { state, error: TRANSFER_PREPARE_MESSAGE };
  }
}

export class TransferResponseError extends Error {
  constructor() {
    super("转账响应无法识别");
    this.name = "TransferResponseError";
  }
}

export function classifyTransferError(error: unknown): "definite" | "conflict" | "uncertain" | "unauthorized" {
  if (error instanceof TransferResponseError) {
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

export function transferFailureMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "保存失败，请检查后重试";
  }
  if (error.code === "VALIDATION_FAILED") {
    return validationFailureMessage(error.details);
  }
  if (error.code === "RESOURCE_NOT_FOUND") {
    return TRANSFER_NOT_FOUND_MESSAGE;
  }
  if (error.code === "ACCOUNT_ARCHIVED") {
    return ARCHIVED_RELATED_BUSINESS_MESSAGE;
  }
  return "保存失败，请检查后重试";
}

export function applyTransferCreateResult(
  state: TransferSaveState,
  transfer: TransferView,
  sourceAccountId: number,
): TransferSaveState {
  if (state.phase !== "submitting" || state.attempt === null) {
    return state;
  }
  const payload = transferPostSavePayload(sourceAccountId, transfer);
  return {
    phase: "success",
    attempt: state.attempt,
    error: "",
    postSave: payload,
  };
}

export function applyTransferFailure(
  state: TransferSaveState,
  kind: "definite" | "conflict" | "uncertain" | "unauthorized",
  error: unknown,
): TransferSaveState {
  if (state.phase !== "submitting" || state.attempt === null) {
    return state;
  }
  if (kind === "definite") {
    return { ...editingTransfer(), error: transferFailureMessage(error) };
  }
  if (kind === "conflict") {
    return { ...state, phase: "conflict", error: TRANSFER_CONFLICT_MESSAGE };
  }
  if (kind === "unauthorized") {
    return { ...state, error: "" };
  }
  return { ...state, phase: "uncertain", error: TRANSFER_UNCERTAIN_MESSAGE };
}

export function handoffCreatedTransfer(
  phase: TransferPhase,
  postSave: TransferPostSavePayload | null,
  completed: boolean,
): { readonly completed: boolean; readonly result: TransferPostSavePayload | null } {
  if (completed || phase !== "success" || postSave === null) {
    return { completed, result: null };
  }
  return { completed: true, result: postSave };
}

export function transferAlertMessage(phase: TransferPhase, preparing: boolean): string | null {
  if (preparing || phase === "submitting") {
    return "正在保存，离开后请到流水中确认是否保存成功";
  }
  if (phase === "editing") {
    return "放弃本次转账？";
  }
  if (phase === "uncertain") {
    return "保存结果尚未确认。离开后请到流水中确认本次转账是否已保存。";
  }
  return null;
}

function validationFailureMessage(details: readonly ApiErrorDetail[] | undefined): string {
  if (details?.some((detail) => detail.field === "toAccountId" && detail.reason === "MUST_DIFFER")) {
    return SAME_ACCOUNT_MESSAGE;
  }
  if (details?.some((detail) => detail.field === "amount" && detail.reason === "MUST_BE_POSITIVE")) {
    return NON_POSITIVE_AMOUNT_MESSAGE;
  }
  return "请检查填写内容";
}
