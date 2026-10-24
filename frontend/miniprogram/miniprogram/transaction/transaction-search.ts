import type { LoadGuard } from "../account/load-guard";
import { moneyToneClass } from "../account/money-tone";
import { formatBusinessTime, formatMoneyLabel, ledgerRows, type LedgerEntry, type LedgerRow } from "../ledger/ledger";
import type { ListShow } from "../navigation/scroll-restore";
import { isBusinessTimeAllowed, validateBusinessTime } from "./business-time";

const MONEY_RESPONSE_PATTERN = /^-?[0-9]+\.[0-9]{2}$/;

export const SEARCH_DEBOUNCE_MS = 300;
export const SEARCH_EMPTY_COPY = "没有匹配的交易";
export const SEARCH_LOAD_ERROR = "搜索失败，请稍后重试";
export const SEARCH_CONTRACT_ERROR = "搜索结果不符合约定";

export interface TransactionSearchCategory {
  readonly id: number;
  readonly name: string;
}

export interface TransactionSearchTag {
  readonly id: number;
  readonly name: string;
}

export interface TransactionSearchItem {
  readonly id: number;
  readonly type: "INCOME" | "EXPENSE";
  readonly amount: string;
  readonly displayName: string | null;
  readonly category: TransactionSearchCategory | null;
  readonly tags: readonly TransactionSearchTag[];
  readonly businessTime: string;
}

export interface TransactionSearchResult {
  readonly items: readonly TransactionSearchItem[];
}

export function trimSearchKeyword(raw: string): string {
  return raw.trim();
}

export function isActiveSearchKeyword(keyword: string): boolean {
  return trimSearchKeyword(keyword).length > 0;
}

export function nextSearchGeneration(current: number): number {
  return current + 1;
}

export function canApplySearchResult(
  guard: LoadGuard,
  generation: number,
  requestedAccountId: number,
  currentAccountId: number,
  requestedKeyword: string,
  currentKeyword: string,
  currentSearchGeneration: number,
  committedKeyword?: string,
): boolean {
  const committed = committedKeyword === undefined ? currentKeyword : committedKeyword;
  return (
    guard.pageAlive &&
    !guard.loggingOut &&
    generation === currentSearchGeneration &&
    requestedAccountId === currentAccountId &&
    trimSearchKeyword(requestedKeyword) === trimSearchKeyword(committed) &&
    !searchDraftDiffersFromCommitted(committed, currentKeyword)
  );
}

export function searchDraftDiffersFromCommitted(committedKeyword: string, draftInput: string): boolean {
  return trimSearchKeyword(draftInput) !== trimSearchKeyword(committedKeyword);
}

export interface SearchInputInvalidation {
  readonly nextGeneration: number;
  readonly clearSearchLoading: boolean;
}

export function invalidateSearchOnInputChange(
  committedKeyword: string,
  draftInput: string,
  searchLoading: boolean,
  currentGeneration: number,
): SearchInputInvalidation {
  const draftDiffers =
    isActiveSearchKeyword(committedKeyword) && searchDraftDiffersFromCommitted(committedKeyword, draftInput);
  if (!searchLoading && !draftDiffers) {
    return { nextGeneration: currentGeneration, clearSearchLoading: false };
  }
  return {
    nextGeneration: nextSearchGeneration(currentGeneration),
    clearSearchLoading: searchLoading,
  };
}

export function shouldRunDebouncedSearch(
  committedKeyword: string,
  debouncedKeyword: string,
  searchLoading: boolean,
  searchGeneration: number,
  searchRequestGeneration: number,
): boolean {
  const debounced = trimSearchKeyword(debouncedKeyword);
  const committed = trimSearchKeyword(committedKeyword);
  if (!isActiveSearchKeyword(debounced)) {
    return true;
  }
  if (debounced !== committed) {
    return true;
  }
  if (searchLoading) {
    return true;
  }
  return searchGeneration !== searchRequestGeneration;
}

export function shouldRefreshSearchAfterDetailReturn(searchKeyword: string): boolean {
  return isActiveSearchKeyword(searchKeyword);
}

export function shouldRefreshSearchOnListShow(show: ListShow, searchKeyword: string): boolean {
  if (!isActiveSearchKeyword(searchKeyword)) {
    return false;
  }
  if (show.refresh === "keep") {
    return false;
  }
  if (show.refresh === "reload" && show.scope === "account") {
    return false;
  }
  return true;
}

export interface LedgerLoadSetDataCommon {
  readonly phase: "ready";
  readonly balanceLabel: string;
  readonly balanceToneClass: string;
  readonly negativeBalanceHint: string;
  readonly monthLabel: string;
  readonly incomeLabel: string;
  readonly incomeToneClass: string;
  readonly expenseLabel: string;
  readonly expenseToneClass: string;
  readonly surplusLabel: string;
  readonly surplusToneClass: string;
  readonly countLabel: string;
  readonly errorMessage: string;
  readonly locateMiss: string;
  readonly highlightKey: string;
}

export type LedgerLoadSetDataPatch = LedgerLoadSetDataCommon | (LedgerLoadSetDataCommon & { readonly rows: LedgerRow[]; readonly empty: boolean });

export function buildLedgerLoadSetDataPatch(
  searchKeyword: string,
  ledgerItems: readonly LedgerEntry[],
  common: LedgerLoadSetDataCommon,
): LedgerLoadSetDataPatch {
  if (isActiveSearchKeyword(searchKeyword)) {
    return common;
  }
  return {
    ...common,
    rows: ledgerRows(ledgerItems),
    empty: ledgerItems.length === 0,
  };
}

export function shouldPreserveSearchRowsOnLedgerLoad(searchKeyword: string): boolean {
  return isActiveSearchKeyword(searchKeyword);
}

export function shouldRunSearchOnMonthChange(searchKeyword: string): boolean {
  return !isActiveSearchKeyword(searchKeyword);
}

export function searchTransactionsPath(accountId: number, keyword: string): string {
  const query = encodeURIComponent(trimSearchKeyword(keyword));
  return `/api/v1/accounts/${accountId}/transactions/search?name=${query}`;
}

export function parseTransactionSearch(value: unknown, accountCreatedTime: string): TransactionSearchResult | null {
  if (!isRecord(value) || !Array.isArray(value.items)) {
    return null;
  }
  const items: TransactionSearchItem[] = [];
  for (const item of value.items) {
    const parsed = parseSearchItem(item, accountCreatedTime);
    if (parsed === null) {
      return null;
    }
    items.push(parsed);
  }
  return { items };
}

export function searchLedgerRows(items: readonly TransactionSearchItem[]): LedgerRow[] {
  return items.map((item) => {
    const tags = item.tags.map((tag) => tag.name).join(" ");
    const category = item.category === null ? "未分类" : item.category.name;
    return {
      recordId: item.id,
      key: `transaction-${item.id}`,
      kind: "transaction",
      title: item.displayName === null || item.displayName.length === 0 ? "未命名交易" : item.displayName,
      detail: tags.length === 0 ? category : `${category} ${tags}`,
      typeLabel: item.type === "INCOME" ? "收入" : "支出",
      amountLabel: formatMoneyLabel(item.amount),
      amountToneClass: moneyToneClass(item.amount),
      timeLabel: formatBusinessTime(item.businessTime),
    };
  });
}

export interface SearchDebounce {
  schedule(keyword: string, onFire: (keyword: string) => void): void;
  cancel(): void;
}

export function createSearchDebounce(delayMs: number, schedule: (callback: () => void, delayMs: number) => number, clear: (handle: number) => void): SearchDebounce {
  let pending: number | null = null;
  return {
    schedule(keyword, onFire) {
      if (pending !== null) {
        clear(pending);
      }
      pending = schedule(() => {
        pending = null;
        onFire(keyword);
      }, delayMs);
    },
    cancel() {
      if (pending !== null) {
        clear(pending);
        pending = null;
      }
    },
  };
}

function parseSearchItem(value: unknown, accountCreatedTime: string): TransactionSearchItem | null {
  if (!isRecord(value) || !isPositiveId(value.id) || !isMoney(value.amount)) {
    return null;
  }
  if (value.type !== "INCOME" && value.type !== "EXPENSE") {
    return null;
  }
  if (
    typeof value.businessTime !== "string" ||
    !validateBusinessTime(value.businessTime) ||
    !isBusinessTimeAllowed(value.businessTime, accountCreatedTime)
  ) {
    return null;
  }
  const displayName = value.displayName === undefined || value.displayName === null ? null : value.displayName;
  if (displayName !== null && typeof displayName !== "string") {
    return null;
  }
  const category = parseCategory(value.category);
  const tags = parseTags(value.tags);
  if (category === undefined || tags === null) {
    return null;
  }
  return {
    id: value.id,
    type: value.type,
    amount: value.amount,
    displayName,
    category,
    tags,
    businessTime: value.businessTime,
  };
}

function parseCategory(value: unknown): TransactionSearchCategory | null | undefined {
  if (value === undefined || value === null) {
    return null;
  }
  if (!isRecord(value) || !isPositiveId(value.id) || typeof value.name !== "string" || value.name.length === 0) {
    return undefined;
  }
  return { id: value.id, name: value.name };
}

function parseTags(value: unknown): TransactionSearchTag[] | null {
  if (!Array.isArray(value)) {
    return null;
  }
  const tags: TransactionSearchTag[] = [];
  for (const item of value) {
    if (!isRecord(item) || !isPositiveId(item.id) || typeof item.name !== "string" || item.name.length === 0) {
      return null;
    }
    tags.push({ id: item.id, name: item.name });
  }
  return tags;
}

function isMoney(value: unknown): value is string {
  return typeof value === "string" && MONEY_RESPONSE_PATTERN.test(value);
}

function isPositiveId(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
