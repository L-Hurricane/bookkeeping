import { bookableCategoryOptions, type CategoryNode } from "../category/category";
import type { Tag } from "../tag/tag";
import { switchTransactionType, type CreateTransactionForm } from "./form";
import type { TransactionType } from "./transaction";

export type ReferencePhase = "idle" | "loading" | "ready" | "error";

export interface TypeBucket<T> {
  readonly generation: number;
  readonly phase: ReferencePhase;
  readonly items: readonly T[];
}

export function emptyBucket<T>(): TypeBucket<T> {
  return { generation: 0, phase: "idle", items: [] };
}

export function beginBucket<T>(bucket: TypeBucket<T>): { readonly generation: number; readonly bucket: TypeBucket<T> } {
  const generation = bucket.generation + 1;
  return { generation, bucket: { generation, phase: "loading", items: bucket.items } };
}

export function finishBucket<T>(
  bucket: TypeBucket<T>,
  generation: number,
  pageAlive: boolean,
  items: readonly T[] | null,
  merge: (current: readonly T[], incoming: readonly T[]) => readonly T[],
): TypeBucket<T> | null {
  if (!pageAlive || bucket.generation !== generation) {
    return null;
  }
  if (items === null) {
    return { ...bucket, phase: "error" };
  }
  return { generation, phase: "ready", items: merge(bucket.items, items) };
}

export function replaceItems<T>(_current: readonly T[], incoming: readonly T[]): readonly T[] {
  return incoming;
}

export function showsReference(activeType: TransactionType, loadedType: TransactionType): boolean {
  return activeType === loadedType;
}

export function shouldLoadReference(phase: ReferencePhase): boolean {
  return phase === "idle" || phase === "error";
}

export interface CategoryRow {
  readonly key: string;
  readonly kind: "group" | "option";
  readonly id: number | null;
  readonly label: string;
}

export function categoryRows(categories: readonly CategoryNode[]): CategoryRow[] {
  const rows: CategoryRow[] = [];
  for (const root of bookableCategoryOptions(categories)) {
    if (root.children.length > 0) {
      rows.push({ key: `group-${root.id}`, kind: "group", id: null, label: root.name });
      for (const child of root.children) {
        rows.push({ key: `option-${child.id}`, kind: "option", id: child.id, label: child.name });
      }
    } else if (root.bookable) {
      rows.push({ key: `option-${root.id}`, kind: "option", id: root.id, label: root.name });
    }
  }
  return rows;
}

export function categoryLabel(categories: readonly CategoryNode[], categoryId: number | null): string {
  if (categoryId === null) {
    return "未分类";
  }
  for (const root of categories) {
    if (root.children.length === 0 && root.bookable && root.id === categoryId) {
      return root.name;
    }
    for (const child of root.children) {
      if (child.bookable && child.id === categoryId) {
        return `${root.name} / ${child.name}`;
      }
    }
  }
  return "未分类";
}

export function mergeTagLists(local: readonly Tag[], incoming: readonly Tag[]): Tag[] {
  const seen = new Set<number>();
  const merged: Tag[] = [];
  for (const tag of incoming) {
    if (!seen.has(tag.id)) {
      seen.add(tag.id);
      merged.push(tag);
    }
  }
  for (const tag of local) {
    if (!seen.has(tag.id)) {
      seen.add(tag.id);
      merged.push(tag);
    }
  }
  return merged;
}

export function toggleTagSelection(selected: readonly number[], tagId: number, tags: readonly Tag[]): number[] {
  if (!tags.some((tag) => tag.id === tagId)) {
    return [...selected];
  }
  if (selected.includes(tagId)) {
    return selected.filter((id) => id !== tagId);
  }
  return [...selected, tagId];
}

export type CreateTagPlan =
  | { readonly status: "empty" }
  | { readonly status: "duplicate" }
  | { readonly status: "create"; readonly name: string };

export function prepareCreateTag(raw: string, tags: readonly Tag[]): CreateTagPlan {
  const name = raw.trim();
  if (name.length === 0) {
    return { status: "empty" };
  }
  if (tags.some((tag) => tag.name === name)) {
    return { status: "duplicate" };
  }
  return { status: "create", name };
}

export function rememberCreatedTag(bucket: TypeBucket<Tag>, created: Tag): TypeBucket<Tag> {
  if (bucket.items.some((tag) => tag.id === created.id)) {
    return bucket;
  }
  return { ...bucket, items: [...bucket.items, created] };
}

export interface TagComposer {
  readonly generation: number;
  readonly open: boolean;
  readonly draft: string;
  readonly error: string;
}

export function emptyComposer(): TagComposer {
  return { generation: 0, open: false, draft: "", error: "" };
}

export function openComposer(composer: TagComposer): TagComposer {
  return { generation: composer.generation + 1, open: true, draft: "", error: "" };
}

export function editComposer(composer: TagComposer, draft: string): TagComposer {
  return { ...composer, draft };
}

export function retireComposer(composer: TagComposer): TagComposer {
  return { generation: composer.generation + 1, open: false, draft: "", error: "" };
}

export function settleCreateTagComposer(
  composer: TagComposer,
  requestGeneration: number,
  outcome: { readonly status: "success" } | { readonly status: "failure"; readonly message: string },
): TagComposer {
  if (composer.generation !== requestGeneration) {
    return composer;
  }
  if (outcome.status === "success") {
    return { generation: composer.generation, open: false, draft: "", error: "" };
  }
  return { ...composer, error: outcome.message };
}

export function acceptCreatedTag(
  pageAlive: boolean,
  tags: readonly Tag[],
  selected: readonly number[],
  currentType: TransactionType,
  created: Tag,
): { readonly tags: Tag[]; readonly selected: number[] } | null {
  if (!pageAlive) {
    return null;
  }
  return applyCreatedTag(tags, selected, currentType, created);
}

export function createTagFailureMessage(status: number, code: string | undefined): string {
  return status === 409 && code === "TAG_NAME_CONFLICT" ? "标签名称已存在" : "标签创建失败，请稍后重试";
}

export function applyCreatedTag(
  tags: readonly Tag[],
  selected: readonly number[],
  currentType: TransactionType,
  created: Tag,
): { readonly tags: Tag[]; readonly selected: number[] } {
  const nextTags = tags.some((tag) => tag.id === created.id) ? [...tags] : [...tags, created];
  const nextSelected =
    currentType === created.type && !selected.includes(created.id) ? [...selected, created.id] : [...selected];
  return { tags: nextTags, selected: nextSelected };
}

export function visibleCreateTagError(
  currentType: TransactionType,
  submittedType: TransactionType,
  message: string,
): string | null {
  return currentType === submittedType ? message : null;
}

export function amountFeedback(raw: string, validation: { readonly ok: boolean; readonly reason?: string }): string | null {
  if (validation.ok) {
    return null;
  }
  if (raw.trim().length === 0 || validation.reason === "amount-required") {
    return "请输入金额";
  }
  if (validation.reason === "amount-zero") {
    return "金额不能为 0";
  }
  return "请输入最多两位小数的金额";
}

export function switchCreateType(
  form: CreateTransactionForm,
  nextType: TransactionType,
): { readonly form: CreateTransactionForm; readonly clearTagDraft: boolean } {
  if (form.type === nextType) {
    return { form: switchTransactionType(form, nextType), clearTagDraft: false };
  }
  return { form: switchTransactionType(form, nextType), clearTagDraft: true };
}

export function resolveCancel(
  choice: "continue" | "abandon",
  form: CreateTransactionForm,
  createdTagIds: readonly number[],
): { readonly action: "stay" | "leave"; readonly form: CreateTransactionForm | null; readonly createdTagIds: readonly number[] } {
  if (choice === "continue") {
    return { action: "stay", form, createdTagIds };
  }
  return { action: "leave", form: null, createdTagIds };
}
