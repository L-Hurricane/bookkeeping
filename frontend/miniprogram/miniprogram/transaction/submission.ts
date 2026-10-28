import { ApiError, type ApiErrorDetail } from "../http/client";
import { TransactionResponseError } from "../services/transaction";
import { isBusinessTimeAllowed } from "./business-time";
import {
  buildCreateTransactionSnapshot,
  postSaveResult,
  type CreateTransactionForm,
  type CreateTransactionSnapshot,
  type PostSaveResult,
} from "./form";
import type { TransactionView } from "./transaction";

export type SubmissionPhase = "editing" | "submitting" | "uncertain" | "conflict" | "success";

export interface SubmissionAttempt {
  readonly snapshot: CreateTransactionSnapshot;
  readonly idempotencyKey: string;
}

export interface SubmissionState {
  readonly phase: SubmissionPhase;
  readonly attempt: SubmissionAttempt | null;
  readonly error: string;
  readonly transaction: TransactionView | null;
  readonly postSave: PostSaveResult | null;
}

export const WAIT_TAG_MESSAGE = "请等待标签创建完成";
export const PREPARE_MESSAGE = "无法准备保存请求，请重试";
export const UNCERTAIN_MESSAGE = "保存结果尚未确认";
export const UNCERTAIN_HINT = "请重试确认，系统不会重复创建同一笔记录。";
export const CONFLICT_MESSAGE = "保存请求发生冲突，请重新进入记账页面后再试";
export const SAVED_MESSAGE = "交易已保存";

export function editingSubmission(): SubmissionState {
  return { phase: "editing", attempt: null, error: "", transaction: null, postSave: null };
}

export function formLocked(phase: SubmissionPhase): boolean {
  return phase !== "editing";
}

export function interactionLocked(phase: SubmissionPhase, preparingSave: boolean): boolean {
  return preparingSave || formLocked(phase);
}

export function canSave(phase: SubmissionPhase, tagCreateInFlight: boolean, preparingSave = false): boolean {
  return phase === "editing" && !tagCreateInFlight && !preparingSave;
}

export type SaveIntent =
  | { readonly status: "blocked" }
  | { readonly status: "wait-tag" }
  | { readonly status: "invalid"; readonly message: string }
  | { readonly status: "ready"; readonly snapshot: CreateTransactionSnapshot };

export function prepareSave(
  phase: SubmissionPhase,
  tagCreateInFlight: boolean,
  form: CreateTransactionForm,
  accountId: number,
  accountCreatedTime: string,
): SaveIntent {
  if (phase !== "editing") {
    return { status: "blocked" };
  }
  if (tagCreateInFlight) {
    return { status: "wait-tag" };
  }
  const snapshot = buildCreateTransactionSnapshot(form, accountId);
  if (!snapshot.ok) {
    return { status: "invalid", message: validationMessage(snapshot.reason) };
  }
  if (!isBusinessTimeAllowed(snapshot.value.businessTime, accountCreatedTime)) {
    return { status: "invalid", message: "交易时间不得早于账户创建时间" };
  }
  return { status: "ready", snapshot: snapshot.value };
}

export function beginAttempt(
  state: SubmissionState,
  snapshot: CreateTransactionSnapshot,
  idempotencyKey: string,
): SubmissionState | null {
  if (state.phase !== "editing") {
    return null;
  }
  return {
    phase: "submitting",
    attempt: { snapshot, idempotencyKey },
    error: "",
    transaction: null,
    postSave: null,
  };
}

export function retryAttempt(state: SubmissionState): SubmissionState | null {
  if (state.phase !== "uncertain" || state.attempt === null) {
    return null;
  }
  return { ...state, phase: "submitting", error: "" };
}

export type SubmitOutcome =
  | { readonly kind: "success"; readonly transaction: TransactionView }
  | { readonly kind: "definite"; readonly code: string; readonly details: readonly ApiErrorDetail[] | undefined }
  | { readonly kind: "conflict" }
  | { readonly kind: "uncertain" }
  | { readonly kind: "unauthorized" };

export function classifySubmitError(error: unknown): SubmitOutcome {
  if (error instanceof TransactionResponseError) {
    return { kind: "uncertain" };
  }
  if (!(error instanceof ApiError)) {
    return { kind: "uncertain" };
  }
  if (error.status === 401) {
    return { kind: "unauthorized" };
  }
  if (error.status === 409 && error.code === "IDEMPOTENCY_CONFLICT") {
    return { kind: "conflict" };
  }
  if (error.status === 400 || error.status === 403 || error.status === 404 || error.status === 409) {
    return { kind: "definite", code: error.code, details: error.details };
  }
  return { kind: "uncertain" };
}

export function applySubmitOutcome(state: SubmissionState, outcome: SubmitOutcome): SubmissionState {
  if (state.phase !== "submitting" || state.attempt === null) {
    return state;
  }
  if (outcome.kind === "success") {
    const postSave = postSaveResult(outcome.transaction);
    if (postSave === null) {
      return { ...state, phase: "uncertain", error: UNCERTAIN_MESSAGE };
    }
    return { phase: "success", attempt: state.attempt, error: "", transaction: outcome.transaction, postSave };
  }
  if (outcome.kind === "definite") {
    return { phase: "editing", attempt: null, error: definiteFailureMessage(outcome.code, outcome.details), transaction: null, postSave: null };
  }
  if (outcome.kind === "conflict") {
    return { ...state, phase: "conflict", error: CONFLICT_MESSAGE };
  }
  if (outcome.kind === "unauthorized") {
    return { ...state, error: "" };
  }
  return { ...state, phase: "uncertain", error: UNCERTAIN_MESSAGE };
}

export function definiteFailureMessage(code: string, details: readonly ApiErrorDetail[] | undefined): string {
  if (code === "VALIDATION_FAILED") {
    return "请检查填写内容";
  }
  if (code === "BUSINESS_RULE_VIOLATION" && details?.some((detail) => detail.field === "businessTime" && detail.reason === "BEFORE_ACCOUNT_CREATION")) {
    return "交易时间无效";
  }
  if (code === "CATEGORY_NOT_BOOKABLE") {
    return "所选分类当前不可用于记账，请重新选择";
  }
  if (code === "CATEGORY_TYPE_MISMATCH") {
    return "所选分类与交易类型不匹配，请重新选择";
  }
  if (code === "TAG_TYPE_MISMATCH") {
    return "所选标签与交易类型不匹配，请重新选择";
  }
  if (code === "RESOURCE_NOT_FOUND") {
    return "账户或关联数据已发生变化，请返回后重试";
  }
  if (code === "ACCOUNT_ARCHIVED") {
    return "账户已归档，恢复后才能继续记账或编辑";
  }
  return "保存失败，请检查后重试";
}

export function alertBeforeUnloadMessage(phase: SubmissionPhase): string | null {
  if (phase === "editing") {
    return "放弃本次记账？";
  }
  if (phase === "submitting") {
    return "正在保存，离开后请到流水中确认是否保存成功";
  }
  if (phase === "uncertain") {
    return "保存结果尚未确认。离开后请到流水中确认本次记录是否已保存。";
  }
  return null;
}

export async function openSubmission(
  state: SubmissionState,
  snapshot: CreateTransactionSnapshot,
  createKey: () => Promise<string>,
): Promise<{ readonly state: SubmissionState; readonly error: string }> {
  try {
    const next = beginAttempt(state, snapshot, await createKey());
    if (next === null) {
      return { state, error: "" };
    }
    return { state: next, error: "" };
  } catch {
    return { state, error: PREPARE_MESSAGE };
  }
}

function validationMessage(reason: string): string {
  if (reason === "amount-required") {
    return "请输入金额";
  }
  if (reason === "amount-zero") {
    return "金额不能为 0";
  }
  if (reason === "amount-invalid") {
    return "请输入最多两位小数的金额";
  }
  if (reason === "business-time-invalid") {
    return "请输入有效的日期和时间";
  }
  return "请检查填写内容";
}
