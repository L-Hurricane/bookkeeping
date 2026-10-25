import { parseCategoryList, type CategoryNode, type CategoryType } from "../category/category";
import type { HttpClient } from "../http/client";

export class CategoryResponseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "CategoryResponseError";
  }
}

export interface CategoryApi {
  listCategories(type: CategoryType): Promise<CategoryNode[]>;
  createCategory(type: CategoryType, name: string, parentId: number | null): Promise<CategoryNode>;
  renameCategory(categoryId: number, name: string): Promise<CategoryNode>;
  moveCategory(categoryId: number, parentId: number | null): Promise<CategoryNode>;
  categoryDeletionPreview(categoryId: number): Promise<number>;
  deleteCategory(categoryId: number, directTransactionCount: number): Promise<void>;
}

export function categoryPath(type: CategoryType): string {
  return `/api/v1/categories?type=${type}`;
}

export function createCategoryApi(http: HttpClient): CategoryApi {
  return {
    async listCategories(type) {
      const body = await http.request<unknown>({
        method: "GET",
        path: categoryPath(type),
        authenticated: true,
      });
      const categories = parseCategoryList(body);
      if (categories === null || !matchesType(categories, type)) {
        throw new CategoryResponseError("分类响应无法识别");
      }
      return categories;
    },

    async createCategory(type, name, parentId) {
      const body = await http.request<unknown>({
        method: "POST",
        path: "/api/v1/categories",
        body: { type, name, parentId },
        authenticated: true,
      });
      return requireNode(body, type);
    },

    async renameCategory(categoryId, name) {
      const body = await http.request<unknown>({
        method: "POST",
        path: `/api/v1/categories/${categoryId}/rename`,
        body: { name },
        authenticated: true,
      });
      return requireAnyNode(body);
    },

    async moveCategory(categoryId, parentId) {
      const body = await http.request<unknown>({
        method: "POST",
        path: `/api/v1/categories/${categoryId}/move`,
        body: { parentId },
        authenticated: true,
      });
      return requireAnyNode(body);
    },

    async categoryDeletionPreview(categoryId) {
      const body = await http.request<unknown>({
        method: "POST",
        path: `/api/v1/categories/${categoryId}/deletion-preview`,
        body: {},
        authenticated: true,
      });
      return requireCount(body);
    },

    async deleteCategory(categoryId, directTransactionCount) {
      await http.request<unknown>({
        method: "POST",
        path: `/api/v1/categories/${categoryId}/deletion`,
        body: { directTransactionCount },
        authenticated: true,
      });
    },
  };
}

function requireNode(body: unknown, type: CategoryType): CategoryNode {
  const categories = parseCategoryList({ items: [body] });
  const category = categories?.[0];
  if (category === undefined || !matchesType([category], type)) {
    throw new CategoryResponseError("分类响应无法识别");
  }
  return category;
}

function requireAnyNode(body: unknown): CategoryNode {
  const categories = parseCategoryList({ items: [body] });
  const category = categories?.[0];
  if (category === undefined) {
    throw new CategoryResponseError("分类响应无法识别");
  }
  return category;
}

function requireCount(body: unknown): number {
  if (typeof body !== "object" || body === null || !("directTransactionCount" in body)) {
    throw new CategoryResponseError("分类响应无法识别");
  }
  const count = body.directTransactionCount;
  if (typeof count !== "number" || !Number.isSafeInteger(count) || count < 0) {
    throw new CategoryResponseError("分类响应无法识别");
  }
  return count;
}

function matchesType(categories: readonly CategoryNode[], type: CategoryType): boolean {
  return categories.every((category) => category.type === type && matchesType(category.children, type));
}
