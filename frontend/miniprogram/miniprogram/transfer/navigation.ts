import { parseBusinessTimeQueryValue } from "../transaction/navigation";

export interface TransferCreateContext {
  readonly sourceAccountId: number;
  readonly accountCreatedTime: string;
}

export interface TransferDetailContext {
  readonly sourceAccountId: number;
  readonly transferId: number;
  readonly accountCreatedTime: string;
}

export function canOpenTransfer(phase: string, loggingOut: boolean): boolean {
  return !loggingOut && (phase === "ledger-loading" || phase === "ready" || phase === "ledger-error");
}

export function canOpenTransferDetail(kind: string, loggingOut: boolean): boolean {
  return kind === "transfer" && !loggingOut;
}

export function transferCreatePath(sourceAccountId: number, accountCreatedTime: string): string {
  return `/pages/transfer-create/index?sourceAccountId=${sourceAccountId}&accountCreatedTime=${encodeURIComponent(accountCreatedTime)}`;
}

export function parseTransferCreateContext(query: Record<string, string | undefined>): TransferCreateContext | null {
  const sourceAccountId = parsePositiveId(query.sourceAccountId ?? query.accountId);
  const rawCreatedTime = query.accountCreatedTime;
  if (sourceAccountId === null || rawCreatedTime === undefined) {
    return null;
  }
  const accountCreatedTime = parseBusinessTimeQueryValue(rawCreatedTime);
  if (accountCreatedTime === null) {
    return null;
  }
  return { sourceAccountId, accountCreatedTime };
}

export function transferDetailPath(sourceAccountId: number, transferId: number, accountCreatedTime: string): string {
  return `/pages/transfer-detail/index?sourceAccountId=${sourceAccountId}&transferId=${transferId}&accountCreatedTime=${encodeURIComponent(accountCreatedTime)}`;
}

export function parseTransferDetailContext(query: Record<string, string | undefined>): TransferDetailContext | null {
  const sourceAccountId = parsePositiveId(query.sourceAccountId ?? query.accountId);
  const transferId = parsePositiveId(query.transferId);
  const rawCreatedTime = query.accountCreatedTime;
  if (sourceAccountId === null || transferId === null || rawCreatedTime === undefined) {
    return null;
  }
  const accountCreatedTime = parseBusinessTimeQueryValue(rawCreatedTime);
  if (accountCreatedTime === null) {
    return null;
  }
  return { sourceAccountId, transferId, accountCreatedTime };
}

function parsePositiveId(raw: string | undefined): number | null {
  if (raw === undefined || !/^[1-9][0-9]*$/.test(raw)) {
    return null;
  }
  const id = Number(raw);
  return Number.isSafeInteger(id) ? id : null;
}
