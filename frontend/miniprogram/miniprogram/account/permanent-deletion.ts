import { MONEY_RESPONSE_PATTERN } from "./account";
import { moneyToneClass } from "./money-tone";
import { ApiError } from "../http/client";

export interface PermanentDeletionAffectedAccount {
  readonly accountId: number;
  readonly name: string;
  readonly balanceBefore: string;
  readonly balanceAfter: string;
  readonly change: string;
}

export interface PermanentDeletionPreview {
  readonly transactionCount: number;
  readonly adjustmentCount: number;
  readonly transferCount: number;
  readonly analysisSnapshotCount: number;
  readonly analysisGenerationCount: number;
  readonly ledgerReportCount: number;
  readonly affectedAccounts: readonly PermanentDeletionAffectedAccount[];
  readonly previewToken: string;
}

export interface AffectedAccountRow {
  readonly accountId: number;
  readonly name: string;
  readonly balanceBeforeLabel: string;
  readonly balanceAfterLabel: string;
  readonly changeLabel: string;
  readonly balanceBeforeToneClass: string;
  readonly balanceAfterToneClass: string;
  readonly changeToneClass: string;
}

export type PermanentDeletionExecuteOutcome =
  | "success"
  | "preview_stale"
  | "analysis_reference_conflict"
  | "analysis_records_remain"
  | "account_not_archived"
  | "not_found"
  | "other";

export const PERMANENT_DELETION_IRREVERSIBLE_HINT =
  "永久删除后不可恢复。该账户及其普通交易、余额调整、关联转账，以及本账户的 AI 历史报告与生成记录将一并删除。已保存的全账本 AI 月报和年报也不再可读，已使用的分析次数不退回。";
export const PERMANENT_DELETION_NO_COUNTERPART_HINT = "没有其他账户会因本次删除而改变余额。";
export const ANALYSIS_REFERENCE_CONFLICT_MESSAGE =
  "分析记录存在跨账户或跨用户引用，本次未删除任何数据";
export const ANALYSIS_RECORDS_REMAIN_MESSAGE = "该账户仍有分析记录，本次未删除任何数据";
export const PREVIEW_STALE_MESSAGE = "删除影响已变化，请重新预览并确认";
export const ACCOUNT_NOT_ARCHIVED_MESSAGE = "只有已归档账户可以永久删除";

function isMoneyResponse(value: unknown): boolean {
  return typeof value === "string" && MONEY_RESPONSE_PATTERN.test(value);
}

function isNonEmptyString(value: unknown): boolean {
  return typeof value === "string" && value.trim().length > 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseAffectedAccount(value: unknown): PermanentDeletionAffectedAccount | null {
  if (!isRecord(value)) {
    return null;
  }
  const accountId = value.accountId;
  const name = value.name;
  const balanceBefore = value.balanceBefore;
  const balanceAfter = value.balanceAfter;
  const change = value.change;
  if (typeof accountId !== "number" || !Number.isSafeInteger(accountId) || accountId <= 0) {
    return null;
  }
  if (!isNonEmptyString(name)) {
    return null;
  }
  if (
    !isMoneyResponse(balanceBefore) ||
    !isMoneyResponse(balanceAfter) ||
    !isMoneyResponse(change) ||
    typeof name !== "string"
  ) {
    return null;
  }
  return {
    accountId,
    name,
    balanceBefore: balanceBefore as string,
    balanceAfter: balanceAfter as string,
    change: change as string,
  };
}

function parseNonNegativeInt(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isInteger(value) || value < 0) {
    return null;
  }
  return value;
}

export function parsePermanentDeletionPreview(value: unknown): PermanentDeletionPreview | null {
  if (!isRecord(value)) {
    return null;
  }
  const transactionCount = parseNonNegativeInt(value.transactionCount);
  const adjustmentCount = parseNonNegativeInt(value.adjustmentCount);
  const transferCount = parseNonNegativeInt(value.transferCount);
  const analysisSnapshotCount = parseNonNegativeInt(value.analysisSnapshotCount);
  const analysisGenerationCount = parseNonNegativeInt(value.analysisGenerationCount);
  const ledgerReportCount = parseNonNegativeInt(value.ledgerReportCount);
  const previewToken = value.previewToken;
  const affectedRaw = value.affectedAccounts;
  if (
    transactionCount === null ||
    adjustmentCount === null ||
    transferCount === null ||
    analysisSnapshotCount === null ||
    analysisGenerationCount === null ||
    ledgerReportCount === null
  ) {
    return null;
  }
  if (typeof previewToken !== "string" || previewToken.trim().length === 0) {
    return null;
  }
  if (!Array.isArray(affectedRaw)) {
    return null;
  }
  const affectedAccounts: PermanentDeletionAffectedAccount[] = [];
  for (const item of affectedRaw) {
    const parsed = parseAffectedAccount(item);
    if (parsed === null) {
      return null;
    }
    affectedAccounts.push(parsed);
  }
  return {
    transactionCount,
    adjustmentCount,
    transferCount,
    analysisSnapshotCount,
    analysisGenerationCount,
    ledgerReportCount,
    affectedAccounts,
    previewToken: previewToken as string,
  };
}

export function formatPreviewMoney(amount: string): string {
  return `¥${amount}`;
}

export function affectedAccountRows(preview: PermanentDeletionPreview): AffectedAccountRow[] {
  return preview.affectedAccounts.map((item) => ({
    accountId: item.accountId,
    name: item.name,
    balanceBeforeLabel: formatPreviewMoney(item.balanceBefore),
    balanceAfterLabel: formatPreviewMoney(item.balanceAfter),
    changeLabel: formatPreviewMoney(item.change),
    balanceBeforeToneClass: moneyToneClass(item.balanceBefore),
    balanceAfterToneClass: moneyToneClass(item.balanceAfter),
    changeToneClass: moneyToneClass(item.change),
  }));
}

export function canConfirmPermanentDeletion(submitting: boolean): boolean {
  return !submitting;
}

export function classifyPermanentDeletionExecute(error: unknown): PermanentDeletionExecuteOutcome {
  if (!(error instanceof ApiError)) {
    return "other";
  }
  if (error.status === 404) {
    return "not_found";
  }
  if (error.code === "PREVIEW_STALE") {
    return "preview_stale";
  }
  if (error.code === "ANALYSIS_REFERENCE_CONFLICT") {
    return "analysis_reference_conflict";
  }
  if (error.code === "ANALYSIS_RECORDS_REMAIN") {
    return "analysis_records_remain";
  }
  if (error.code === "ACCOUNT_NOT_ARCHIVED") {
    return "account_not_archived";
  }
  return "other";
}

export function isPermanentDeletionExecuteSuccess(outcome: PermanentDeletionExecuteOutcome): boolean {
  return outcome === "success";
}

export function permanentDeletionExecuteErrorMessage(outcome: PermanentDeletionExecuteOutcome): string {
  if (outcome === "preview_stale") {
    return PREVIEW_STALE_MESSAGE;
  }
  if (outcome === "analysis_reference_conflict") {
    return ANALYSIS_REFERENCE_CONFLICT_MESSAGE;
  }
  if (outcome === "analysis_records_remain") {
    return ANALYSIS_RECORDS_REMAIN_MESSAGE;
  }
  if (outcome === "account_not_archived") {
    return ACCOUNT_NOT_ARCHIVED_MESSAGE;
  }
  if (outcome === "not_found") {
    return "账户不存在或无权访问";
  }
  return "永久删除失败，请稍后重试";
}

export function mayEnterPermanentDeletion(lifecycle: "ACTIVE" | "ARCHIVED"): boolean {
  return lifecycle === "ARCHIVED";
}
