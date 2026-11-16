import { accountKindLabel, formatCurrentBalance, MONEY_RESPONSE_PATTERN, type Account, type AccountPickerCard } from "./account";
import { moneyToneClass } from "./money-tone";

export interface AccountGroup {
  readonly id: number;
  readonly name: string;
  readonly displayOrder: number;
  readonly subtotal: string;
  readonly activeCount: number;
  readonly archivedCount: number;
}

export interface HomeSectionPlan {
  readonly key: string;
  readonly groupId: number | null;
  readonly title: string;
  readonly subtotal: string | null;
  readonly accountIds: readonly number[];
  readonly archivedIds: readonly number[];
}

export interface DropRect {
  readonly key: string;
  readonly top: number;
  readonly bottom: number;
}

export interface AccountPlacement {
  readonly groupId: number | null;
  readonly orderedIds: readonly number[];
}

export function parseAccountGroup(value: unknown): AccountGroup | null {
  if (!isRecord(value)) {
    return null;
  }
  const id = positiveId(value.id);
  const displayOrder = positiveId(value.displayOrder);
  const activeCount = count(value.activeCount);
  const archivedCount = count(value.archivedCount);
  if (id === null || displayOrder === null || activeCount === null || archivedCount === null) {
    return null;
  }
  if (typeof value.name !== "string" || value.name.trim().length === 0 || value.name !== value.name.trim()) {
    return null;
  }
  if (value.name === "未分组" || typeof value.subtotal !== "string" || !MONEY_RESPONSE_PATTERN.test(value.subtotal)) {
    return null;
  }
  return {
    id,
    name: value.name,
    displayOrder,
    subtotal: value.subtotal,
    activeCount,
    archivedCount,
  };
}

export function parseAccountGroupList(value: unknown): AccountGroup[] | null {
  if (!isRecord(value) || !Array.isArray(value.items)) {
    return null;
  }
  const groups: AccountGroup[] = [];
  for (const item of value.items) {
    const group = parseAccountGroup(item);
    if (group === null) {
      return null;
    }
    groups.push(group);
  }
  return groups;
}

/** Custom groups keep the server order. Ungrouped is virtual and only appears when it has a visible account. */
export function planHome(
  groups: readonly AccountGroup[],
  active: readonly Account[],
  archived: readonly Account[],
): { readonly grouped: boolean; readonly sections: readonly HomeSectionPlan[] } {
  if (groups.length === 0) {
    return { grouped: false, sections: [] };
  }
  const sections: HomeSectionPlan[] = groups.map((group) => ({
    key: `group:${group.id}`,
    groupId: group.id,
    title: group.name,
    subtotal: group.subtotal,
    accountIds: sortAccounts(active.filter((account) => account.groupId === group.id)).map((account) => account.id),
    archivedIds: sortAccounts(archived.filter((account) => account.groupId === group.id)).map((account) => account.id),
  }));
  const ungrouped = sortAccounts(active.filter((account) => account.groupId === null));
  if (ungrouped.length > 0) {
    sections.push({
      key: "ungrouped",
      groupId: null,
      title: "未分组",
      subtotal: null,
      accountIds: ungrouped.map((account) => account.id),
      archivedIds: sortAccounts(archived.filter((account) => account.groupId === null)).map((account) => account.id),
    });
  }
  return { grouped: true, sections };
}

export function deleteGroupConfirm(activeCount: number, archivedCount: number): string {
  const total = activeCount + archivedCount;
  const archivedDetail = archivedCount === 0 ? "" : `（其中归档 ${archivedCount} 个）`;
  return `该分组下 ${total} 个账户${archivedDetail}将变为未分组，账户和流水均保留`;
}

export function groupNameFor(account: Account, groups: readonly AccountGroup[]): string {
  if (account.groupId === null) {
    return "未分组";
  }
  return groups.find((group) => group.id === account.groupId)?.name ?? "未分组";
}

export function hitKey(rects: readonly DropRect[], y: number): string | null {
  for (const rect of rects) {
    if (y >= rect.top && y < rect.bottom) {
      return rect.key;
    }
  }
  return null;
}

export function sectionExpanded(key: string, homeCollapsed: readonly string[], sortCollapsed: readonly string[], sorting: boolean): boolean {
  const collapsed = sorting ? sortCollapsed : homeCollapsed;
  return !collapsed.includes(key);
}

export function toggleCollapsed(keys: readonly string[], key: string): string[] {
  return keys.includes(key) ? keys.filter((item) => item !== key) : [...keys, key];
}

export function rowShiftUnits(index: number, startIndex: number, overIndex: number): number {
  if (index === startIndex) {
    return 0;
  }
  if (startIndex < overIndex && index > startIndex && index <= overIndex) {
    return -1;
  }
  if (overIndex < startIndex && index >= overIndex && index < startIndex) {
    return 1;
  }
  return 0;
}

export function edgeScrollDelta(clientY: number, viewportHeight: number, edge: number, step: number): number {
  if (viewportHeight <= 0 || edge <= 0 || step === 0) {
    return 0;
  }
  if (clientY < edge) {
    return -Math.abs(step);
  }
  if (clientY > viewportHeight - edge) {
    return Math.abs(step);
  }
  return 0;
}

export interface TransferPickerSection {
  readonly key: string;
  readonly title: string;
  readonly accounts: readonly AccountPickerCard[];
}

export function transferPickerSections(
  groups: readonly AccountGroup[],
  accounts: readonly Account[],
  selectedId: number,
  blockedId: number,
  query: string,
): { readonly sections: TransferPickerSection[]; readonly accountSheetHeight: number } {
  const needle = query.trim();
  const selectable = accounts.filter((account) =>
    account.lifecycle !== "ARCHIVED"
    && account.id !== blockedId
    && (needle.length === 0 || account.name.includes(needle)),
  );
  const planned = planHome(groups, selectable, []);
  const sections = planned.grouped
    ? planned.sections.flatMap((section) => {
      const rows = section.accountIds.flatMap((id) => {
        const account = selectable.find((item) => item.id === id);
        return account === undefined ? [] : [pickerCard(account, selectedId)];
      });
      return rows.length === 0 ? [] : [{ key: section.key, title: section.title, accounts: rows }];
    })
    : [{
      key: "accounts",
      title: "",
      accounts: [...selectable].sort(compareDisplayOrder).map((account) => pickerCard(account, selectedId)),
    }];
  const visible = sections.filter((section) => section.accounts.length > 0);
  const rows = visible.reduce((count, section) => count + section.accounts.length, 0);
  const heads = visible.filter((section) => section.title.length > 0).length;
  return {
    sections: [...visible],
    accountSheetHeight: Math.min(Math.max(rows * 128 + heads * 56, 128), 640),
  };
}

function pickerCard(account: Account, selectedId: number): AccountPickerCard {
  return {
    id: account.id,
    name: account.name,
    kindLabel: accountKindLabel(account.kind),
    balanceLabel: formatCurrentBalance(account),
    balanceTone: moneyToneClass(account.currentBalance),
    selected: account.id === selectedId,
  };
}

function compareDisplayOrder(left: Account, right: Account): number {
  const leftOrder = left.displayOrder ?? left.id;
  const rightOrder = right.displayOrder ?? right.id;
  if (leftOrder !== rightOrder) {
    return leftOrder < rightOrder ? -1 : 1;
  }
  return left.id < right.id ? -1 : 1;
}

export function indexFromDelta(startIndex: number, deltaY: number, rowHeight: number, length: number): number {
  if (length <= 0 || rowHeight <= 0) {
    return 0;
  }
  const shift = Math.trunc(deltaY / rowHeight);
  return Math.max(0, Math.min(length - 1, startIndex + shift));
}

export function planGroupDrop(groupIds: readonly number[], groupId: number, toIndex: number): number[] | null {
  const fromIndex = groupIds.indexOf(groupId);
  if (fromIndex < 0 || toIndex < 0 || toIndex >= groupIds.length || fromIndex === toIndex) {
    return null;
  }
  const next = groupIds.filter((id) => id !== groupId);
  next.splice(toIndex, 0, groupId);
  return next;
}

export function planAccountDrop(
  sections: readonly HomeSectionPlan[],
  accountId: number,
  fromKey: string,
  toKey: string,
  toIndex: number,
): AccountPlacement | null {
  const from = sections.find((section) => section.key === fromKey);
  const to = sections.find((section) => section.key === toKey);
  if (from === undefined || to === undefined || !from.accountIds.includes(accountId)) {
    return null;
  }
  if (from.key === to.key) {
    const currentIndex = from.accountIds.indexOf(accountId);
    if (currentIndex === toIndex) {
      return null;
    }
    const active = moveId(from.accountIds, accountId, toIndex);
    return { groupId: from.groupId, orderedIds: [...active, ...from.archivedIds] };
  }
  const active = insertId(to.accountIds, accountId, toIndex);
  return { groupId: to.groupId, orderedIds: [...active, ...to.archivedIds] };
}

export function appendPlacement(section: HomeSectionPlan, accountId: number): AccountPlacement {
  return {
    groupId: section.groupId,
    orderedIds: [...section.accountIds.filter((id) => id !== accountId), accountId, ...section.archivedIds],
  };
}

function moveId(ids: readonly number[], id: number, toIndex: number): number[] {
  const next = ids.filter((item) => item !== id);
  next.splice(Math.max(0, Math.min(toIndex, next.length)), 0, id);
  return next;
}

function insertId(ids: readonly number[], id: number, toIndex: number): number[] {
  const next = ids.filter((item) => item !== id);
  next.splice(Math.max(0, Math.min(toIndex, next.length)), 0, id);
  return next;
}

function sortAccounts(accounts: readonly Account[]): Account[] {
  return [...accounts].sort(compareDisplayOrder);
}

function positiveId(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value <= 0) {
    return null;
  }
  return value;
}

function count(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    return null;
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
