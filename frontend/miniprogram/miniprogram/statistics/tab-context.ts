import { ledgerStatisticsEntryPath } from "./drilldown";

let pendingAccountId: number | null | undefined;

export function accountStatisticsTarget(accountId: number): string {
  pendingAccountId = accountId;
  return ledgerStatisticsEntryPath();
}

export function takeStatisticsAccount(): number | null | undefined {
  const accountId = pendingAccountId;
  pendingAccountId = undefined;
  return accountId;
}
