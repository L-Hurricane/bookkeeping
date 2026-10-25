import type { Account } from "../account/account";

export type AdjustmentBalanceSource =
  | { readonly status: "ready"; readonly currentBalance: string }
  | { readonly status: "archived" }
  | { readonly status: "unavailable" };

/** Use the requested account's own balance. A longer active list is not a read failure. */
export function adjustmentBalanceSource(account: Account | null, accountId: number): AdjustmentBalanceSource {
  if (account === null || account.id !== accountId) {
    return { status: "unavailable" };
  }
  if (account.lifecycle !== "ACTIVE") {
    return { status: "archived" };
  }
  return { status: "ready", currentBalance: account.currentBalance };
}
