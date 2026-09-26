import { ApiError } from "../http/client";
import { businessTimeDisplay } from "../transaction/business-time";
import { formatAdjustmentAmount } from "./money";
import type { AdjustmentRecord } from "./contract";

export interface AdjustmentDetailView {
  readonly amountLabel: string;
  readonly businessTimeLabel: string;
  readonly remarkLabel: string;
}

export type AdjustmentDeletePhase = "idle" | "deleting" | "uncertain";

export interface AdjustmentDetailAccess {
  readonly edit: false;
  readonly delete: boolean;
  readonly retry: boolean;
}

export function adjustmentDetailView(adjustment: AdjustmentRecord): AdjustmentDetailView {
  return {
    amountLabel: formatAdjustmentAmount(adjustment.adjustmentAmount),
    businessTimeLabel: businessTimeDisplay(adjustment.businessTime) ?? adjustment.businessTime,
    remarkLabel: adjustment.remark === null || adjustment.remark.length === 0 ? "—" : adjustment.remark,
  };
}

export function adjustmentDetailAccess(phase: AdjustmentDeletePhase): AdjustmentDetailAccess {
  return {
    edit: false,
    delete: phase === "idle",
    retry: phase === "uncertain",
  };
}

export const ADJUSTMENT_DELETE_UNCERTAIN_MESSAGE = "删除结果尚未确认";
export const ADJUSTMENT_MISSING_MESSAGE = "余额调整不存在或已删除";

export function confirmAdjustmentDelete(confirmed: boolean): "cancel" | "send" {
  return confirmed ? "send" : "cancel";
}

export function classifyAdjustmentDelete(error: unknown): "absent" | "unauthorized" | "uncertain" | "definite" {
  if (!(error instanceof ApiError)) {
    return "uncertain";
  }
  if (error.status === 401) {
    return "unauthorized";
  }
  if (error.status === 404) {
    return "absent";
  }
  if (error.status === 400 || error.status === 403 || error.status === 409) {
    return "definite";
  }
  return "uncertain";
}
