export type CategoryType = "INCOME" | "EXPENSE";

export interface CategoryNode {
  readonly id: number;
  readonly name: string;
  readonly type: CategoryType;
  readonly bookable: boolean;
  readonly children: readonly CategoryNode[];
}

export type CategoryChild = CategoryNode;

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
    const visible = visibleCategory(category);
    if (visible !== null) {
      options.push(visible);
    }
  }
  return options;
}

function visibleCategory(category: CategoryNode): CategoryNode | null {
  const children = category.children
    .map(visibleCategory)
    .filter((child): child is CategoryNode => child !== null);
  if (!category.bookable && children.length === 0) {
    return null;
  }
  return { ...category, children };
}

function parseRoot(value: unknown): CategoryNode | null {
  return parseNode(value, 1);
}

function parseNode(value: unknown, depth: number): CategoryNode | null {
  const fields = parseFields(value);
  if (fields === null || depth > 3 || !isRecord(value)) {
    return null;
  }
  if (!("children" in value) || !Array.isArray(value.children) || (depth === 3 && value.children.length > 0)) {
    return null;
  }
  const children: CategoryNode[] = [];
  for (const child of value.children) {
    const parsed = parseNode(child, depth + 1);
    if (parsed === null) {
      return null;
    }
    children.push(parsed);
  }
  return { ...fields, children };
}

function parseFields(value: unknown): Omit<CategoryNode, "children"> | null {
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
