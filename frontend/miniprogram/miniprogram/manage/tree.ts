export interface NamedNode {
  readonly id: number;
  readonly name: string;
  readonly children: readonly NamedNode[];
}

export interface ManageRow {
  readonly id: number;
  readonly name: string;
  readonly depth: number;
  readonly hasChildren: boolean;
}

export interface ParentChoice {
  readonly id: number | null;
  readonly label: string;
}

export function manageRows(nodes: readonly NamedNode[]): ManageRow[] {
  const rows: ManageRow[] = [];
  appendRows(rows, nodes, 1);
  return rows;
}

export function parentChoices(nodes: readonly NamedNode[], movingId: number | null): ParentChoice[] {
  const moving = movingId === null ? null : findNode(nodes, movingId);
  const blocked = new Set<number>();
  if (moving !== null) {
    collectIds(moving, blocked);
  }
  const span = moving === null ? 0 : descendantSpan(moving);
  const choices: ParentChoice[] = [{ id: null, label: "作为一级" }];
  appendParents(choices, nodes, 1, blocked, span);
  return choices;
}

export function normalizedName(raw: string): string | null {
  const name = raw.trim();
  if (name.length === 0 || name.length > 100) {
    return null;
  }
  return name;
}

export function deletionConfirm(subject: "分类" | "标签", name: string, count: number): string {
  if (subject === "分类") {
    return `删除「${name}」后，${count} 笔交易会变为未分类。交易、金额和余额保持不变。`;
  }
  return `删除「${name}」后，会移除它在 ${count} 笔交易上的关联。交易本身保持不变。`;
}

export function keptSelection(original: readonly number[]): number[] {
  return [...original];
}

export interface MeasuredRow {
  readonly id: number;
  readonly top: number;
  readonly bottom: number;
}

/** Viewport coordinates, same space as a touch clientY. A miss returns null. */
export function rowHitId(rows: readonly MeasuredRow[], y: number): number | null {
  const hit = rows.find((row) => y >= row.top && y <= row.bottom);
  return hit === undefined ? null : hit.id;
}

export type ParentDrop =
  | { readonly status: "unmeasured" }
  | { readonly status: "unchanged" }
  | { readonly status: "rejected" }
  | { readonly status: "move"; readonly parentId: number };

/** Drop onto a measured row. Only a legal parent becomes a move. */
export function planParentDrop(
  nodes: readonly NamedNode[],
  dragId: number,
  measured: readonly MeasuredRow[],
  y: number,
): ParentDrop {
  if (measured.length === 0) {
    return { status: "unmeasured" };
  }
  const targetId = rowHitId(measured, y);
  if (targetId === null || targetId === dragId) {
    return { status: "unchanged" };
  }
  if (!parentChoices(nodes, dragId).some((choice) => choice.id === targetId)) {
    return { status: "rejected" };
  }
  return { status: "move", parentId: targetId };
}

/** Reads the first `selectAll` result from `SelectorQuery.exec`. */
export function measuredRows(result: unknown): MeasuredRow[] {
  const rects = Array.isArray(result) && Array.isArray(result[0]) ? result[0] : [];
  return rects.flatMap((rect) => {
    if (rect === null || typeof rect !== "object") {
      return [];
    }
    const record = rect as { dataset?: { id?: unknown }; top?: unknown; bottom?: unknown };
    const id = Number(record.dataset?.id);
    const top = Number(record.top);
    const bottom = Number(record.bottom);
    if (!Number.isSafeInteger(id) || id <= 0 || !Number.isFinite(top) || !Number.isFinite(bottom)) {
      return [];
    }
    return [{ id, top, bottom }];
  });
}

function appendRows(rows: ManageRow[], nodes: readonly NamedNode[], depth: number): void {
  for (const node of nodes) {
    rows.push({ id: node.id, name: node.name, depth, hasChildren: node.children.length > 0 });
    appendRows(rows, node.children, depth + 1);
  }
}

function appendParents(
  choices: ParentChoice[],
  nodes: readonly NamedNode[],
  depth: number,
  blocked: ReadonlySet<number>,
  span: number,
): void {
  for (const node of nodes) {
    if (!blocked.has(node.id) && depth + 1 + span <= 3) {
      choices.push({ id: node.id, label: `${"· ".repeat(depth - 1)}${node.name}` });
    }
    appendParents(choices, node.children, depth + 1, blocked, span);
  }
}

function findNode(nodes: readonly NamedNode[], id: number): NamedNode | null {
  for (const node of nodes) {
    if (node.id === id) {
      return node;
    }
    const nested = findNode(node.children, id);
    if (nested !== null) {
      return nested;
    }
  }
  return null;
}

function collectIds(node: NamedNode, ids: Set<number>): void {
  ids.add(node.id);
  for (const child of node.children) {
    collectIds(child, ids);
  }
}

function descendantSpan(node: NamedNode): number {
  let span = 0;
  for (const child of node.children) {
    span = Math.max(span, 1 + descendantSpan(child));
  }
  return span;
}
