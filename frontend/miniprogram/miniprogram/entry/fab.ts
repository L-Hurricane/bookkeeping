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
