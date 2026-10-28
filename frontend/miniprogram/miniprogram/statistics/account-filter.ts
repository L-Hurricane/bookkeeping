import type { LedgerAccountScope, LedgerPreset } from "./ledger-contract";

export interface AccountChoice {
  readonly id: number;
  readonly name: string;
  readonly checked: boolean;
}

export interface AccountChoiceGroup {
  readonly key: string;
  readonly title: string;
  readonly collapsed: boolean;
  readonly accounts: readonly AccountChoice[];
}

export interface StatisticsSelection {
  readonly preset: LedgerPreset;
  readonly customFrom: string;
  readonly customTo: string;
  readonly scope: LedgerAccountScope;
  readonly accountIds: readonly number[];
}

const GROUP_DEFS = [
  { key: "active", title: "未归档账户", archived: false },
  { key: "archived", title: "已归档账户", archived: true },
] as const;

const INITIAL: StatisticsSelection = {
  preset: "MONTH",
  customFrom: "",
  customTo: "",
  scope: "ACTIVE",
  accountIds: [],
};

let current: StatisticsSelection = { ...INITIAL, accountIds: [] };

export function statisticsSelection(): StatisticsSelection {
  return { ...current, accountIds: [...current.accountIds] };
}

export function rememberStatisticsSelection(next: StatisticsSelection): void {
  current = { ...next, accountIds: [...next.accountIds] };
}

export function resetStatisticsSelection(): void {
  current = { ...INITIAL, accountIds: [] };
}

export function retainKnownAccountIds(selected: readonly number[], known: readonly number[]): number[] {
  const allowed = new Set(known);
  return selected.filter((id) => allowed.has(id));
}

export function accountChoiceGroups(
  accounts: readonly { readonly id: number; readonly name: string; readonly archived: boolean }[],
  selected: readonly number[],
  query: string,
  collapsed: Readonly<Record<string, boolean>> = {},
): AccountChoiceGroup[] {
  const needle = query.trim();
  return GROUP_DEFS.map((definition) => ({
    key: definition.key,
    title: definition.title,
    collapsed: collapsed[definition.key] === true,
    accounts: accounts
      .filter((account) => account.archived === definition.archived)
      .filter((account) => needle.length === 0 || account.name.includes(needle))
      .map((account) => ({ id: account.id, name: account.name, checked: selected.includes(account.id) })),
  })).filter((group) => group.accounts.length > 0);
}
