export type TagType = "INCOME" | "EXPENSE";

export interface Tag {
  readonly id: number;
  readonly name: string;
  readonly type: TagType;
}

export interface TagNode extends Tag {
  readonly children: readonly TagNode[];
}

export function parseTag(value: unknown): Tag | null {
  if (!isRecord(value) || !isPositiveId(value.id) || !isNonEmptyString(value.name)) {
    return null;
  }
  if (value.type !== "INCOME" && value.type !== "EXPENSE") {
    return null;
  }
  return { id: value.id, name: value.name, type: value.type };
}

export function parseTagList(value: unknown): Tag[] | null {
  return flattenTagTree(value);
}

export function parseTagTree(value: unknown): TagNode[] | null {
  if (!isRecord(value) || !Array.isArray(value.items)) {
    return null;
  }
  const items: TagNode[] = [];
  const seen = new Set<number>();
  for (const item of value.items) {
    const node = parseTagNode(item, seen, 1);
    if (node === null) {
      return null;
    }
    items.push(node);
  }
  return items;
}

function parseTagNode(value: unknown, seen: Set<number>, depth: number): TagNode | null {
  const tag = parseTag(value);
  if (tag === null || depth > 3 || seen.has(tag.id)) {
    return null;
  }
  seen.add(tag.id);
  const children: TagNode[] = [];
  if (isRecord(value) && "children" in value) {
    if (!Array.isArray(value.children) || (depth === 3 && value.children.length > 0)) {
      return null;
    }
    for (const child of value.children) {
      const parsed = parseTagNode(child, seen, depth + 1);
      if (parsed === null) {
        return null;
      }
      children.push(parsed);
    }
  }
  return { ...tag, children };
}

export function flattenTagTree(value: unknown): Tag[] | null {
  if (!isRecord(value) || !Array.isArray(value.items)) {
    return null;
  }
  const items: Tag[] = [];
  const seen = new Set<number>();
  for (const item of value.items) {
    if (!appendTag(item, items, seen, 1)) {
      return null;
    }
  }
  return items;
}

function appendTag(value: unknown, items: Tag[], seen: Set<number>, depth: number): boolean {
  const tag = parseTag(value);
  if (tag === null || depth > 3 || seen.has(tag.id)) {
    return false;
  }
  seen.add(tag.id);
  items.push(tag);
  if (!isRecord(value) || !("children" in value)) {
    return true;
  }
  if (!Array.isArray(value.children) || (depth === 3 && value.children.length > 0)) {
    return false;
  }
  for (const child of value.children) {
    if (!appendTag(child, items, seen, depth + 1)) {
      return false;
    }
  }
  return true;
}

function isPositiveId(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value) && value > 0;
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
