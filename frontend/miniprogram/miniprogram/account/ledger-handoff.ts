import { parsePostSaveResult, type LedgerLocateIntent } from "../ledger/post-save";
import type { PostSaveResult } from "../transaction/form";

interface PendingHandoff {
  readonly accountId: number;
  readonly locate: LedgerLocateIntent;
}

let pending: PendingHandoff | null = null;

export function stashLedgerHandoff(accountId: number, postSave: PostSaveResult): void {
  if (!Number.isSafeInteger(accountId) || accountId <= 0) {
    return;
  }
  pending = {
    accountId,
    locate: { kind: "transaction", recordId: postSave.transactionId, targetMonth: postSave.targetMonth },
  };
}

export function takeLedgerHandoff(accountId: number): LedgerLocateIntent | null {
  if (pending === null || pending.accountId !== accountId) {
    return null;
  }
  const locate = pending.locate;
  pending = null;
  return locate;
}

export function clearLedgerHandoff(): void {
  pending = null;
}

export function postSaveFromUnknown(value: unknown): PostSaveResult | null {
  return parsePostSaveResult(value);
}
