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
  };
}

function matchesType(categories: readonly CategoryNode[], type: CategoryType): boolean {
  return categories.every(
    (category) => category.type === type && category.children.every((child) => child.type === type),
  );
}
