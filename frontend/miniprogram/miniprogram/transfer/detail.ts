import { ApiError } from "../http/client";
import { ARCHIVED_RELATED_BUSINESS_MESSAGE } from "../account/archive-copy";
import type { AccountLifecycle } from "../account/account";
import { TransferResponseError } from "./submission";

export type TransferDeletePhase = "idle" | "deleting" | "uncertain";

export interface TransferDetailAccess {
  readonly save: boolean;
  readonly delete: boolean;
  readonly retry: boolean;
}

export function transferDetailReadOnly(
  sourceLifecycle: AccountLifecycle,
  fromLifecycle: AccountLifecycle | null,
  toLifecycle: AccountLifecycle | null,
): boolean {
  if (sourceLifecycle === "ARCHIVED") {
    return true;
  }
  if (fromLifecycle === "ARCHIVED" || toLifecycle === "ARCHIVED") {
    return true;
  }
  return false;
}

export function transferArchiveHint(readOnly: boolean): string {
  return readOnly ? ARCHIVED_RELATED_BUSINESS_MESSAGE : "";
}

export function transferDetailAccess(deletePhase: TransferDeletePhase): TransferDetailAccess {
  if (deletePhase === "deleting") {
    return { save: false, delete: false, retry: false };
  }
  if (deletePhase === "uncertain") {
    return { save: false, delete: false, retry: true };
  }
  return { save: true, delete: true, retry: false };
}

export function confirmTransferDelete(confirmed: boolean): "send" | "cancel" {
  return confirmed ? "send" : "cancel";
}

export const TRANSFER_DELETE_CONFIRM = "删除整笔转账？";

export function classifyTransferDelete(error: unknown): "missing" | "definite" | "uncertain" | "unauthorized" {
  if (error instanceof TransferResponseError) {
    return "uncertain";
  }
  if (!(error instanceof ApiError)) {
    return "uncertain";
  }
  if (error.status === 401) {
    return "unauthorized";
  }
  if (error.status === 404) {
    return "missing";
  }
  if (error.status === 400 || error.status === 403 || error.status === 409) {
    return "definite";
  }
  return "uncertain";
}
