export type CategoryType = "INCOME" | "EXPENSE";

export interface CategoryChild {
  readonly id: number;
  readonly name: string;
  readonly type: CategoryType;
  readonly bookable: boolean;
}

export interface CategoryNode {
  readonly id: number;
  readonly name: string;
  readonly type: CategoryType;
  readonly bookable: boolean;
  readonly children: readonly CategoryChild[];
}

export function parseCategoryList(value: unknown): CategoryNode[] | null {
  if (!isRecord(value) || !Array.isArray(value.items)) {
    return null;
  }
  const items: CategoryNode[] = [];
  for (const item of value.items) {
    const category = parseRoot(item);
    if (category === null) {
      return null;
    }
    items.push(category);
  }
  return items;
}

export function bookableCategoryOptions(categories: readonly CategoryNode[]): CategoryNode[] {
  const options: CategoryNode[] = [];
  for (const category of categories) {
    const children = category.children.filter((child) => child.bookable);
    if (category.bookable || children.length > 0) {
      options.push({
        id: category.id,
        name: category.name,
        type: category.type,
        bookable: category.bookable,
        children,
      });
    }
  }
  return options;
}

function parseRoot(value: unknown): CategoryNode | null {
  const fields = parseFields(value);
  if (fields === null || !isRecord(value) || !Array.isArray(value.children)) {
    return null;
  }
  const children: CategoryChild[] = [];
  for (const child of value.children) {
    const parsed = parseChild(child);
    if (parsed === null) {
      return null;
    }
    children.push(parsed);
  }
  return { ...fields, children };
}

function parseChild(value: unknown): CategoryChild | null {
  if (!isRecord(value)) {
    return null;
  }
  if ("children" in value && (!Array.isArray(value.children) || value.children.length !== 0)) {
    return null;
  }
  return parseFields(value);
}

function parseFields(value: unknown): CategoryChild | null {
  if (!isRecord(value) || !isPositiveId(value.id) || !isNonEmptyString(value.name)) {
    return null;
  }
  if ((value.type !== "INCOME" && value.type !== "EXPENSE") || typeof value.bookable !== "boolean") {
    return null;
  }
  return { id: value.id, name: value.name, type: value.type, bookable: value.bookable };
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
