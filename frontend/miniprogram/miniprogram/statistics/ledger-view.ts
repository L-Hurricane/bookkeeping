import { moneyToneClass } from "../account/money-tone";
import { formatBusinessTime, formatMoneyLabel } from "../ledger/ledger";
import type { LedgerCategoryNode, LedgerFlow, LedgerSlice, LedgerTagNode, LedgerTransaction } from "./ledger-contract";

export interface LedgerAmountRow {
  readonly key: string;
  readonly title: string;
  readonly detail: string;
  readonly amountLabel: string;
  readonly amountToneClass: string;
  readonly depth: number;
  readonly drillKind: "category" | "uncategorized" | "tag" | "untagged" | "";
  readonly drillId: number;
  readonly selected: boolean;
}

export interface LedgerTransactionView {
  readonly id: number;
  readonly accountId: number;
  readonly accountCreatedTime: string;
  readonly title: string;
  readonly amountLabel: string;
  readonly amountToneClass: string;
  readonly timeLabel: string;
}

export function flowRows(flow: LedgerFlow, kind: "category" | "tag"): LedgerAmountRow[] {
  const rows = kind === "category" ? flattenCategories(flow.categories, 0) : flattenTags(flow.tags, 0);
  const extra = kind === "category" ? sliceRow(flow.uncategorized, "未分类", "uncategorized") : sliceRow(flow.untagged, "无标签", "untagged");
  return extra === null ? rows : [...rows, extra];
}

export function transactionViews(items: readonly LedgerTransaction[]): LedgerTransactionView[] {
  return items.map((item) => ({
    id: item.id,
    accountId: item.accountId,
    accountCreatedTime: item.accountCreatedTime,
    title: item.displayName === null || item.displayName.length === 0 ? "未命名交易" : item.displayName,
    amountLabel: formatMoneyLabel(item.amount),
    amountToneClass: moneyToneClass(item.amount),
    timeLabel: formatBusinessTime(item.businessTime),
  }));
}

function flattenCategories(nodes: readonly LedgerCategoryNode[], depth: number): LedgerAmountRow[] {
  const rows: LedgerAmountRow[] = [];
  for (const node of nodes) {
    rows.push(nodeRow(`category-${node.id}`, node.name, node.transactionCount, node.amount, node.share, depth, "category", node.id));
    rows.push(...flattenCategories(node.children, depth + 1));
  }
  return rows;
}

function flattenTags(nodes: readonly LedgerTagNode[], depth: number): LedgerAmountRow[] {
  const rows: LedgerAmountRow[] = [];
  for (const node of nodes) {
    rows.push(nodeRow(`tag-${node.id}`, node.name, node.transactionCount, node.amount, node.share, depth, "tag", node.id));
    rows.push(...flattenTags(node.children, depth + 1));
  }
  return rows;
}

function sliceRow(slice: LedgerSlice | null, title: string, kind: "uncategorized" | "untagged"): LedgerAmountRow | null {
  if (slice === null) return null;
  return nodeRow(kind, title, slice.transactionCount, slice.amount, slice.share, 0, kind, 0);
}

function nodeRow(key: string, title: string, count: number, amount: string, share: string | null, depth: number,
    drillKind: LedgerAmountRow["drillKind"], drillId: number): LedgerAmountRow {
  const shareText = share === null ? "不显示占比" : `${share}%`;
  return {
    key,
    title,
    detail: `${count} 笔 · ${shareText}`,
    amountLabel: formatMoneyLabel(amount),
    amountToneClass: moneyToneClass(amount),
    depth,
    drillKind,
    drillId,
    selected: false,
  };
}
