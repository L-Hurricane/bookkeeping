import { findAccount, type Account } from "../account/account";

export interface TransferAccountDefaults {
  readonly fromAccountId: number;
  readonly toAccountId: number;
}

/**
 * New transfer only. The outgoing account is the current active account.
 * The incoming account stays empty until the user chooses one, even when only one other account exists.
 */
export function initialTransferAccountIds(
  accounts: readonly Account[],
  sourceAccountId: number,
): TransferAccountDefaults {
  const usable = accounts.filter((account) => account.lifecycle === "ACTIVE");
  return { fromAccountId: findAccount(usable, sourceAccountId)?.id ?? 0, toAccountId: 0 };
}
