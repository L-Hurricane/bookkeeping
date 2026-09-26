import { parseBusinessTimeQueryValue } from "../transaction/navigation";

export interface AdjustmentCreateContext {
  readonly accountId: number;
  readonly accountCreatedTime: string;
}

export interface AdjustmentDetailContext {
  readonly accountId: number;
  readonly adjustmentId: number;
}

export function canOpenBalanceAdjustment(phase: string, loggingOut: boolean): boolean {
  return !loggingOut && (phase === "ledger-loading" || phase === "ready" || phase === "ledger-error");
}

export function canOpenAdjustmentDetail(kind: string, loggingOut: boolean): boolean {
  return kind === "adjustment" && !loggingOut;
}

export function adjustmentCreatePath(accountId: number, accountCreatedTime: string): string {
  return `/pages/balance-adjustment-create/index?accountId=${accountId}&accountCreatedTime=${encodeURIComponent(accountCreatedTime)}`;
}

export function parseAdjustmentCreateContext(query: Record<string, string | undefined>): AdjustmentCreateContext | null {
  const accountId = parsePositiveId(query.accountId);
  const rawCreatedTime = query.accountCreatedTime;
  if (accountId === null || rawCreatedTime === undefined) {
    return null;
  }
  const accountCreatedTime = parseBusinessTimeQueryValue(rawCreatedTime);
  if (accountCreatedTime === null) {
    return null;
  }
  return { accountId, accountCreatedTime };
}

export function adjustmentDetailPath(accountId: number, adjustmentId: number): string {
  return `/pages/balance-adjustment-detail/index?accountId=${accountId}&adjustmentId=${adjustmentId}`;
}

export function parseAdjustmentDetailContext(query: Record<string, string | undefined>): AdjustmentDetailContext | null {
  const accountId = parsePositiveId(query.accountId);
  const adjustmentId = parsePositiveId(query.adjustmentId);
  if (accountId === null || adjustmentId === null) {
    return null;
  }
  return { accountId, adjustmentId };
}

function parsePositiveId(raw: string | undefined): number | null {
  if (raw === undefined || !/^[1-9][0-9]*$/.test(raw)) {
    return null;
  }
  const id = Number(raw);
  return Number.isSafeInteger(id) ? id : null;
}
