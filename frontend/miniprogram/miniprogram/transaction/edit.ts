import type { CategoryNode } from "../category/category";
import { ApiError, type ApiErrorDetail } from "../http/client";
import { formatMoneyLabel } from "../ledger/ledger";
import type { Tag } from "../tag/tag";
import { businessTimeDisplay, isBusinessTimeAllowed, validateBusinessTime } from "./business-time";
import {
  normalizeOptionalText,
  postSaveResult,
  validateTransactionAmount,
  type CreateTransactionForm,
  type PostSaveResult,
  type ValidationResult,
} from "./form";
import {
  emptyComposer,
  rememberCreatedTag,
  resolveCategoryLabel,
  retireComposer,
  settleCreateTagComposer,
  type TagComposer,
  type TypeBucket,
} from "./reference";
import type { CategoryRef, TagRef, TransactionType, TransactionView } from "./transaction";

const UPDATE_UNCERTAIN_MESSAGE = "保存结果尚未确认";

export interface TransactionDetailView {
  readonly typeLabel: string;
  readonly title: string;
  readonly amountLabel: string;
  readonly categoryLabel: string;
  readonly tagNames: readonly string[];
  readonly tagsLabel: string;
  readonly businessTimeLabel: string;
  readonly remarkLabel: string;
}

export interface UpdateTransactionSnapshot {
  readonly accountId: number;
  readonly transactionId: number;
  readonly fixedType: TransactionType;
  readonly amount: string;
  readonly name: string | null;
  readonly categoryId: number | null;
  readonly tagIds: readonly number[];
  readonly businessTime: string;
  readonly remark: string | null;
}

export interface UpdateTransactionRequestBody {
  readonly amount: string;
  readonly name?: string;
  readonly categoryId?: number;
  readonly tagIds: readonly number[];
  readonly businessTime: string;
  readonly remark?: string;
}

export type UpdatePhase = "editing" | "submitting" | "uncertain" | "success";

export interface UpdateState {
  readonly phase: UpdatePhase;
  readonly attempt: UpdateTransactionSnapshot | null;
  readonly error: string;
  readonly postSave: PostSaveResult | null;
}

export type UpdateOutcome =
  | { readonly kind: "success"; readonly transaction: TransactionView }
  | { readonly kind: "definite"; readonly code: string; readonly details: readonly ApiErrorDetail[] | undefined }
  | { readonly kind: "missing" }
  | { readonly kind: "uncertain" }
  | { readonly kind: "unauthorized" };

export type DeleteOutcome =
  | { readonly kind: "absent" }
  | { readonly kind: "definite"; readonly message: string }
  | { readonly kind: "uncertain" }
  | { readonly kind: "unauthorized" };

export const DELETE_UNCERTAIN_MESSAGE = "删除结果尚未确认";
export const MISSING_TRANSACTION_MESSAGE = "交易不存在或已删除";

export function transactionDetailView(transaction: TransactionView): TransactionDetailView {
  const tagNames = transaction.tags.map((tag) => tag.name);
  const businessTimeLabel = businessTimeDisplay(transaction.businessTime) ?? transaction.businessTime;
  return {
    typeLabel: transaction.type === "INCOME" ? "收入" : "支出",
    title: transaction.displayName === null || transaction.displayName.length === 0 ? "未命名交易" : transaction.displayName,
    amountLabel: formatMoneyLabel(transaction.amount),
    categoryLabel: transaction.category === null ? "未分类" : transaction.category.name,
    tagNames,
    tagsLabel: tagNames.length === 0 ? "—" : tagNames.join(" "),
    businessTimeLabel,
    remarkLabel: transaction.remark === null || transaction.remark.length === 0 ? "—" : transaction.remark,
  };
}

export interface TagCreateOwnership {
  readonly sessionId: number;
  readonly generation: number;
}

export interface TagEditSession {
  readonly id: number;
  readonly composer: TagComposer;
  readonly request: TagCreateOwnership | null;
}

export function initialTagEditSession(): TagEditSession {
  return { id: 0, composer: emptyComposer(), request: null };
}

export function enterEditSession(session: TagEditSession): TagEditSession {
  return { id: session.id + 1, composer: retireComposer(session.composer), request: session.request };
}

export function abandonEditSession(session: TagEditSession): TagEditSession {
  return { ...session, composer: retireComposer(session.composer) };
}

export function claimTagCreate(session: TagEditSession): { readonly session: TagEditSession; readonly request: TagCreateOwnership } {
  const request = { sessionId: session.id, generation: session.composer.generation };
  return { request, session: { ...session, request } };
}

export function tagCreateOwned(session: TagEditSession, request: TagCreateOwnership): boolean {
  return session.id === request.sessionId && session.composer.generation === request.generation;
}

export function tagCreateInFlight(session: TagEditSession): boolean {
  return session.request !== null && tagCreateOwned(session, session.request);
}

export function finishTagCreate(session: TagEditSession, request: TagCreateOwnership): TagEditSession {
  if (session.request === null || session.request.sessionId !== request.sessionId || session.request.generation !== request.generation) {
    return session;
  }
  return { ...session, request: null };
}

export function applyCreateTagToEditSession(
  session: TagEditSession,
  request: TagCreateOwnership,
  bucket: TypeBucket<Tag>,
  selectedTagIds: readonly number[],
  outcome: { readonly status: "success"; readonly tag: Tag } | { readonly status: "failure"; readonly message: string },
): { readonly session: TagEditSession; readonly bucket: TypeBucket<Tag>; readonly selectedTagIds: readonly number[] } {
  if (outcome.status === "failure") {
    if (!tagCreateOwned(session, request)) {
      return { session, bucket, selectedTagIds };
    }
    return {
      session: { ...session, composer: settleCreateTagComposer(session.composer, request.generation, outcome) },
      bucket,
      selectedTagIds,
    };
  }
  const nextBucket = rememberCreatedTag(bucket, outcome.tag);
  if (!tagCreateOwned(session, request)) {
    return { session, bucket: nextBucket, selectedTagIds };
  }
  return {
    session: { ...session, composer: settleCreateTagComposer(session.composer, request.generation, { status: "success" }) },
    bucket: nextBucket,
    selectedTagIds: selectedTagIds.includes(outcome.tag.id) ? selectedTagIds : [...selectedTagIds, outcome.tag.id],
  };
}

export function editCategoryText(categories: readonly CategoryNode[], categoryId: number | null, saved: CategoryRef | null): string {
  if (categoryId === null) {
    return "未分类";
  }
  const resolved = resolveCategoryLabel(categories, categoryId);
  if (resolved !== null) {
    return resolved;
  }
  if (saved !== null && saved.id === categoryId) {
    return saved.name;
  }
  return "未分类";
}

export function editTagChoices(saved: readonly TagRef[], list: readonly Tag[], type: TransactionType): Tag[] {
  const listed = new Map(list.map((tag) => [tag.id, tag]));
  const choices: Tag[] = [];
  const seen = new Set<number>();
  for (const tag of saved) {
    if (seen.has(tag.id)) {
      continue;
    }
    seen.add(tag.id);
    choices.push(listed.get(tag.id) ?? { id: tag.id, name: tag.name, type });
  }
  for (const tag of list) {
    if (!seen.has(tag.id)) {
      seen.add(tag.id);
      choices.push(tag);
    }
  }
  return choices;
}

export function editFormFromTransaction(transaction: TransactionView): CreateTransactionForm {
  return {
    type: transaction.type,
    amount: transaction.amount,
    name: transaction.name ?? "",
    categoryId: transaction.category === null ? null : transaction.category.id,
    selectedTagIds: transaction.tags.map((tag) => tag.id),
    businessTime: transaction.businessTime,
    remark: transaction.remark ?? "",
  };
}

export function buildUpdateTransactionSnapshot(
  form: CreateTransactionForm,
  accountId: number,
  transactionId: number,
  fixedType: TransactionType,
  accountCreatedTime: string,
): ValidationResult<UpdateTransactionSnapshot> {
  if (form.type !== fixedType || (fixedType !== "INCOME" && fixedType !== "EXPENSE")) {
    return { ok: false, reason: "type-invalid" };
  }
  if (!isPositiveId(accountId) || !isPositiveId(transactionId)) {
    return { ok: false, reason: "resource-invalid" };
  }
  const amount = validateTransactionAmount(form.amount);
  if (!amount.ok) {
    return amount;
  }
  if (!validateBusinessTime(form.businessTime)) {
    return { ok: false, reason: "business-time-invalid" };
  }
  if (!isBusinessTimeAllowed(form.businessTime, accountCreatedTime)) {
    return { ok: false, reason: "before-account-creation" };
  }
  if (form.categoryId !== null && !isPositiveId(form.categoryId)) {
    return { ok: false, reason: "category-invalid" };
  }
  const tagIds: number[] = [];
  for (const tagId of form.selectedTagIds) {
    if (!isPositiveId(tagId)) {
      return { ok: false, reason: "tag-invalid" };
    }
    if (!tagIds.includes(tagId)) {
      tagIds.push(tagId);
    }
  }
  tagIds.sort((left, right) => left - right);
  return {
    ok: true,
    value: {
      accountId,
      transactionId,
      fixedType,
      amount: amount.value,
      name: normalizeOptionalText(form.name),
      categoryId: form.categoryId,
      tagIds,
      businessTime: form.businessTime,
      remark: normalizeOptionalText(form.remark),
    },
  };
}

export function updateTransactionRequestBody(snapshot: UpdateTransactionSnapshot): UpdateTransactionRequestBody {
  return {
    amount: snapshot.amount,
    ...(snapshot.name === null ? {} : { name: snapshot.name }),
    ...(snapshot.categoryId === null ? {} : { categoryId: snapshot.categoryId }),
    tagIds: [...snapshot.tagIds],
    businessTime: snapshot.businessTime,
    ...(snapshot.remark === null ? {} : { remark: snapshot.remark }),
  };
}

export function editingUpdate(): UpdateState {
  return { phase: "editing", attempt: null, error: "", postSave: null };
}

export function beginUpdate(state: UpdateState, snapshot: UpdateTransactionSnapshot): UpdateState | null {
  if (state.phase !== "editing") {
    return null;
  }
  return { phase: "submitting", attempt: snapshot, error: "", postSave: null };
}

export function retryUpdate(state: UpdateState): UpdateState | null {
  if (state.phase !== "uncertain" || state.attempt === null) {
    return null;
  }
  return { ...state, phase: "submitting", error: "" };
}

export function classifyUpdateError(error: unknown): UpdateOutcome {
  if (isResponseContractError(error)) {
    return { kind: "uncertain" };
  }
  if (!(error instanceof ApiError)) {
    return { kind: "uncertain" };
  }
  if (error.status === 401) {
    return { kind: "unauthorized" };
  }
  if (error.status === 404) {
    return { kind: "missing" };
  }
  if (error.status === 400 || error.status === 403 || error.status === 409) {
    return { kind: "definite", code: error.code, details: error.details };
  }
  return { kind: "uncertain" };
}

export function applyUpdateOutcome(state: UpdateState, outcome: UpdateOutcome): UpdateState {
  if (state.phase !== "submitting" || state.attempt === null) {
    return state;
  }
  if (outcome.kind === "success") {
    const postSave = postSaveResult(outcome.transaction);
    if (postSave === null || postSave.transactionId !== state.attempt.transactionId) {
      return { ...state, phase: "uncertain", error: UPDATE_UNCERTAIN_MESSAGE };
    }
    return { phase: "success", attempt: state.attempt, error: "", postSave };
  }
  if (outcome.kind === "definite") {
    return { phase: "editing", attempt: null, error: updateFailureMessage(outcome.code, outcome.details), postSave: null };
  }
  if (outcome.kind === "unauthorized" || outcome.kind === "missing") {
    return { ...state, error: "" };
  }
  return { ...state, phase: "uncertain", error: UPDATE_UNCERTAIN_MESSAGE };
}

function updateFailureMessage(code: string, details: readonly ApiErrorDetail[] | undefined): string {
  if (code === "VALIDATION_FAILED") {
    return "请检查填写内容";
  }
  if (code === "BUSINESS_RULE_VIOLATION" && details?.some((detail) => detail.field === "businessTime" && detail.reason === "BEFORE_ACCOUNT_CREATION")) {
    return "交易时间不得早于账户创建时间";
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
  return "保存失败，请检查后重试";
}

export function updateValidationMessage(reason: string): string {
  if (reason === "amount-required") {
    return "请输入金额";
  }
  if (reason === "amount-zero") {
    return "金额不能为 0";
  }
  if (reason === "amount-invalid") {
    return "请输入最多两位小数的金额";
  }
  if (reason === "before-account-creation") {
    return "交易时间不得早于账户创建时间";
  }
  if (reason === "business-time-invalid") {
    return "请输入有效的日期和时间";
  }
  return "请检查填写内容";
}

export function resolveEditCancel(choice: "continue" | "abandon"): "stay" | "detail" {
  return choice === "abandon" ? "detail" : "stay";
}

export function decideDelete(confirmed: boolean): "cancel" | "send" {
  return confirmed ? "send" : "cancel";
}

export type DeletePhase = "idle" | "deleting" | "uncertain";

export interface DetailActionAccess {
  readonly edit: boolean;
  readonly delete: boolean;
  readonly retry: boolean;
}

export function detailActionAccess(deletePhase: DeletePhase): DetailActionAccess {
  if (deletePhase === "idle") {
    return { edit: true, delete: true, retry: false };
  }
  if (deletePhase === "uncertain") {
    return { edit: false, delete: false, retry: true };
  }
  return { edit: false, delete: false, retry: false };
}

export function classifyDeleteError(error: unknown): DeleteOutcome {
  if (!(error instanceof ApiError)) {
    return { kind: "uncertain" };
  }
  if (error.status === 401) {
    return { kind: "unauthorized" };
  }
  if (error.status === 404) {
    return { kind: "absent" };
  }
  if (error.status === 400 || error.status === 403 || error.status === 409) {
    return { kind: "definite", message: "删除失败，请稍后重试" };
  }
  return { kind: "uncertain" };
}

export function classifyDetailError(error: unknown): "missing" | "unauthorized" | "error" {
  if (error instanceof ApiError && error.status === 401) {
    return "unauthorized";
  }
  if (error instanceof ApiError && error.status === 404) {
    return "missing";
  }
  return "error";
}

function isResponseContractError(error: unknown): boolean {
  return error instanceof Error && error.name === "TransactionResponseError";
}

function isPositiveId(value: number): boolean {
  return Number.isSafeInteger(value) && value > 0;
}
