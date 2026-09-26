import type { HttpClient } from "../http/client";
import { parseTag, parseTagList, type Tag, type TagType } from "../tag/tag";

export class TagResponseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "TagResponseError";
  }
}

export interface TagApi {
  listTags(type: TagType): Promise<Tag[]>;
  createTag(type: TagType, name: string): Promise<Tag>;
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

    async createTag(type, name) {
      const body = await http.request<unknown>({
        method: "POST",
        path: "/api/v1/tags",
        body: { type, name },
        authenticated: true,
      });
      const tag = parseTag(body);
      if (tag === null || tag.type !== type) {
        throw new TagResponseError("标签响应无法识别");
      }
      return tag;
    },
  };
}
