import type { TagNode } from "../tag/tag";

export interface TagPickerRow {
  readonly id: number;
  readonly name: string;
  readonly depth: number;
  readonly selected: boolean;
}

export const TAG_SAVED_NOTICE = "新标签已保存。取消这笔交易也不会删除它。";

export function flattenTagNodes(nodes: readonly TagNode[]): Array<{ id: number; name: string; type: TagNode["type"] }> {
  const items: Array<{ id: number; name: string; type: TagNode["type"] }> = [];
  const visit = (node: TagNode) => {
    items.push({ id: node.id, name: node.name, type: node.type });
    node.children.forEach(visit);
  };
  nodes.forEach(visit);
  return items;
}

export function appendCreatedTag(nodes: readonly TagNode[], created: TagNode): TagNode[] {
  if (containsTag(nodes, created.id)) return [...nodes];
  return [...nodes, { ...created, children: created.children }];
}

export function tagPickerRows(nodes: readonly TagNode[], selected: readonly number[], query: string): TagPickerRow[] {
  const needle = query.trim();
  const visit = (node: TagNode, depth: number): TagPickerRow[] => {
    const children = node.children.flatMap((child) => visit(child, depth + 1));
    const selfMatches = needle.length === 0 || node.name.includes(needle);
    if (!selfMatches && children.length === 0) return [];
    return [{ id: node.id, name: node.name, depth, selected: selected.includes(node.id) }, ...children];
  };
  return nodes.flatMap((node) => visit(node, 0));
}

export function tagSelectionSummary(selected: readonly number[], tags: readonly { readonly id: number; readonly name: string }[]): string {
  const names = selected
    .map((id) => tags.find((tag) => tag.id === id)?.name)
    .filter((name): name is string => name !== undefined);
  if (names.length === 0) return "未选择";
  if (names.length <= 2) return names.join("、");
  return `已选 ${names.length} 个`;
}

export function tagSheetHeight(rowCount: number): number {
  return Math.min(Math.max(rowCount, 1) * 88, 640);
}

function containsTag(nodes: readonly TagNode[], id: number): boolean {
  return nodes.some((node) => node.id === id || containsTag(node.children, id));
}
