export type TagType = "INCOME" | "EXPENSE";

export interface Tag {
  readonly id: number;
  readonly name: string;
  readonly type: TagType;
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
  if (!isRecord(value) || !Array.isArray(value.items)) {
    return null;
  }
  const items: Tag[] = [];
  for (const item of value.items) {
    const tag = parseTag(item);
    if (tag === null) {
      return null;
    }
    items.push(tag);
  }
  return items;
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
