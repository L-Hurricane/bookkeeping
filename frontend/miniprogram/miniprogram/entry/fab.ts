import { transactionCreatePath } from "../transaction/navigation";
import { transferCreatePath } from "../transfer/navigation";

export type EntryKind = "EXPENSE" | "INCOME" | "TRANSFER";

export interface EntryAccount {
  readonly id: number;
  readonly createdTime: string;
}

export function entryPath(kind: EntryKind, account: EntryAccount): string {
  if (kind === "TRANSFER") return transferCreatePath(account.id, account.createdTime);
  const path = transactionCreatePath(account.id, account.createdTime);
  return kind === "INCOME" ? `${path}&type=INCOME` : path;
}

/** Opens the form so the account is chosen there, not in the floating menu. */
export function entryChooserPath(kind: EntryKind): string {
  if (kind === "TRANSFER") return "/pages/transfer-create/index?choose=1";
  const path = "/pages/transaction-create/index?choose=1";
  return kind === "INCOME" ? `${path}&type=INCOME` : path;
}
