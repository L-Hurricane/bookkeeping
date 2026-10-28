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
  readonly barPercent: number;
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
    barPercent: barPercent(share),
    selected: false,
  };
}

export interface RingSegment {
  readonly key: string;
  readonly title: string;
  readonly amountLabel: string;
  readonly share: string;
  readonly drillKind: "category" | "uncategorized";
  readonly drillId: number;
}

export interface RingSweep {
  readonly index: number;
  readonly start: number;
  readonly end: number;
}

export type RingHit =
  | { readonly kind: "sector"; readonly index: number }
  | { readonly kind: "hole" }
  | { readonly kind: "miss" };

const RING_INSET = 8;
const RING_HOLE_RATIO = 0.62;

/** Top-level composition only. Returns null when a ring would pretend to be a full non-negative pie. */
export function ringSegments(flow: LedgerFlow): RingSegment[] | null {
  if (flow.net.startsWith("-") || flow.net === "0.00") return null;
  const tops: Array<{ key: string; title: string; amount: string; share: string | null; drillKind: RingSegment["drillKind"]; drillId: number }> = flow.categories.map((node) => ({
    key: `category-${node.id}`,
    title: node.name,
    amount: node.amount,
    share: node.share,
    drillKind: "category",
    drillId: node.id,
  }));
  if (flow.uncategorized !== null) {
    tops.push({
      key: "uncategorized",
      title: "未分类",
      amount: flow.uncategorized.amount,
      share: flow.uncategorized.share,
      drillKind: "uncategorized",
      drillId: 0,
    });
  }
  if (tops.some((item) => item.amount.startsWith("-") || item.share === null)) return null;
  return tops.filter((item) => item.share !== "0.00").map((item) => ({
    key: item.key,
    title: item.title,
    amountLabel: formatMoneyLabel(item.amount),
    share: item.share ?? "0.00",
    drillKind: item.drillKind,
    drillId: item.drillId,
  }));
}

/** Geometry only. The displayed amount and share stay the backend strings on each segment. */
export function ringSweeps(segments: readonly { share: string }[]): RingSweep[] {
  const sweeps: RingSweep[] = [];
  let start = -Math.PI / 2;
  for (let index = 0; index < segments.length; index += 1) {
    const weight = Number(segments[index]?.share);
    if (!Number.isFinite(weight) || weight <= 0) continue;
    const end = start + (weight / 100) * Math.PI * 2;
    sweeps.push({ index, start, end });
    start = end;
  }
  return sweeps;
}

export function ringHit(segments: readonly { share: string }[], x: number, y: number, width: number, height: number): RingHit {
  const geometry = ringGeometry(width, height);
  if (geometry === null || !Number.isFinite(x) || !Number.isFinite(y)) return { kind: "miss" };
  const distance = Math.hypot(x - geometry.cx, y - geometry.cy);
  if (distance > geometry.radius) return { kind: "miss" };
  if (distance < geometry.hole) return { kind: "hole" };
  let angle = Math.atan2(y - geometry.cy, x - geometry.cx);
  if (angle < -Math.PI / 2) angle += Math.PI * 2;
  for (const sweep of ringSweeps(segments)) {
    if (angle >= sweep.start && angle < sweep.end) return { kind: "sector", index: sweep.index };
  }
  return { kind: "miss" };
}

export function ringTouchPoint(
  detailX: number | undefined,
  detailY: number | undefined,
  touch: { clientX: number; clientY: number } | undefined,
  canvas: { width: number; height: number; left?: number; top?: number },
): { x: number; y: number } | null {
  const direct = canvasPoint(detailX, detailY, canvas.width, canvas.height);
  if (direct !== null) return direct;
  if (touch !== undefined && canvas.left !== undefined && canvas.top !== undefined) {
    return canvasPoint(touch.clientX - canvas.left, touch.clientY - canvas.top, canvas.width, canvas.height);
  }
  return null;
}

export function ringGeometry(width: number, height: number): { cx: number; cy: number; radius: number; hole: number } | null {
  if (!(width > 0) || !(height > 0)) return null;
  const radius = Math.min(width, height) / 2 - RING_INSET;
  if (!(radius > 0)) return null;
  return { cx: width / 2, cy: height / 2, radius, hole: radius * RING_HOLE_RATIO };
}

function canvasPoint(x: number | undefined, y: number | undefined, width: number, height: number): { x: number; y: number } | null {
  if (x === undefined || y === undefined || !Number.isFinite(x) || !Number.isFinite(y) || x < 0 || y < 0 || x > width || y > height) return null;
  return { x, y };
}

export function pointHeights(amounts: readonly string[]): number[] {
  const cents = amounts.map(centsOf);
  if (cents.some((value) => value === null)) return amounts.map(() => 24);
  const values = cents as number[];
  const min = Math.min(...values);
  const max = Math.max(...values);
  if (min === max) return values.map(() => 24);
  return values.map((value) => 8 + Math.round(((value - min) / (max - min)) * 72));
}

function barPercent(share: string | null): number {
  if (share === null) return 0;
  const match = /^-?([0-9]+)\.([0-9]{2})$/.exec(share);
  if (match === null) return 0;
  const whole = Number(match[1]);
  const fraction = Number(match[2]);
  if (!Number.isSafeInteger(whole)) return 0;
  return Math.min(100, whole + fraction / 100);
}

function centsOf(amount: string): number | null {
  const match = /^(-?)([0-9]+)\.([0-9]{2})$/.exec(amount);
  if (match === null) return null;
  const whole = Number(match[2]);
  const fraction = Number(match[3]);
  if (!Number.isSafeInteger(whole) || !Number.isSafeInteger(whole * 100 + fraction)) return null;
  const cents = whole * 100 + fraction;
  return match[1] === "-" ? -cents : cents;
}
