import type { HttpClient } from "../http/client";
import { parseTag, parseTagList, parseTagTree, type Tag, type TagNode, type TagType } from "../tag/tag";

export class TagResponseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TagResponseError";
  }
}

export interface TagApi {
  listTags(type: TagType): Promise<Tag[]>;
  listTagTree(type: TagType): Promise<TagNode[]>;
  createTag(type: TagType, name: string, parentId?: number | null): Promise<Tag>;
  renameTag(tagId: number, type: TagType, name: string): Promise<Tag>;
  moveTag(tagId: number, type: TagType, parentId: number | null): Promise<Tag>;
  tagDeletionPreview(tagId: number): Promise<number>;
  deleteTag(tagId: number, directTransactionCount: number): Promise<void>;
}

export function tagListPath(type: TagType): string {
  return `/api/v1/tags?type=${type}`;
}

export function createTagApi(http: HttpClient): TagApi {
  return {
    async listTags(type) {
      const body = await http.request<unknown>({
        method: "GET",
        path: tagListPath(type),
        authenticated: true,
      });
      const tags = parseTagList(body);
      if (tags === null || tags.some((tag) => tag.type !== type)) {
        throw new TagResponseError("标签响应无法识别");
      }
      return tags;
    },

    async listTagTree(type) {
      const body = await http.request<unknown>({
        method: "GET",
        path: tagListPath(type),
        authenticated: true,
      });
      const tags = parseTagTree(body);
      if (tags === null || !treeMatchesType(tags, type)) {
        throw new TagResponseError("标签响应无法识别");
      }
      return tags;
    },

    async createTag(type, name, parentId = null) {
      const body = await http.request<unknown>({
        method: "POST",
        path: "/api/v1/tags",
        body: parentId === null || parentId === undefined ? { type, name } : { type, name, parentId },
        authenticated: true,
      });
      return requireTag(body, type);
    },

    async renameTag(tagId, type, name) {
      const body = await http.request<unknown>({
        method: "POST",
        path: `/api/v1/tags/${tagId}/rename`,
        body: { type, name },
        authenticated: true,
      });
      return requireTag(body, type);
    },

    async moveTag(tagId, type, parentId) {
      const body = await http.request<unknown>({
        method: "POST",
        path: `/api/v1/tags/${tagId}/move`,
        body: { type, parentId },
        authenticated: true,
      });
      return requireTag(body, type);
    },

    async tagDeletionPreview(tagId) {
      const body = await http.request<unknown>({
        method: "POST",
        path: `/api/v1/tags/${tagId}/deletion-preview`,
        body: {},
        authenticated: true,
      });
      return requireCount(body);
    },

    async deleteTag(tagId, directTransactionCount) {
      await http.request<unknown>({
        method: "POST",
        path: `/api/v1/tags/${tagId}/deletion`,
        body: { directTransactionCount },
        authenticated: true,
      });
    },
  };
}

function treeMatchesType(tags: readonly TagNode[], type: TagType): boolean {
  return tags.every((tag) => tag.type === type && treeMatchesType(tag.children, type));
}

function requireTag(body: unknown, type: TagType): Tag {
  const tag = parseTag(body);
  if (tag === null || tag.type !== type) {
    throw new TagResponseError("标签响应无法识别");
  }
  return tag;
}

function requireCount(body: unknown): number {
  if (typeof body !== "object" || body === null || !("directTransactionCount" in body)) {
    throw new TagResponseError("标签响应无法识别");
  }
  const count = body.directTransactionCount;
  if (typeof count !== "number" || !Number.isSafeInteger(count) || count < 0) {
    throw new TagResponseError("标签响应无法识别");
  }
  return count;
}
