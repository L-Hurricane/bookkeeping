import { clearSession, restoreSession, type Session } from "../session/session";
import type { KeyValueStorage } from "../storage/key-value";

export interface ApiErrorDetail {
  readonly field: string;
  readonly reason: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId: string | undefined;
  readonly details: readonly ApiErrorDetail[] | undefined;

  constructor(
    status: number,
    code: string,
    message: string,
    requestId?: string,
    details?: readonly ApiErrorDetail[],
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.details = details;
  }
}

export interface HttpTransportRequest {
  readonly url: string;
  readonly method: "GET" | "POST" | "PUT" | "DELETE";
  readonly header: Record<string, string>;
  readonly data?: string;
}

export interface HttpTransportResponse {
  readonly statusCode: number;
  readonly data: unknown;
}

export type HttpTransport = (request: HttpTransportRequest) => Promise<HttpTransportResponse>;

export interface HttpRequestOptions {
  readonly method: "GET" | "POST" | "PUT" | "DELETE";
  readonly path: string;
  readonly body?: unknown;
  readonly authenticated: boolean;
  readonly headers?: Readonly<Record<string, string>>;
}

export interface HttpClientDependencies {
  readonly baseUrl: string;
  readonly transport: HttpTransport;
  readonly storage: KeyValueStorage;
  readonly now: () => Date;
  readonly onUnauthorized: () => void;
}

export interface HttpClient {
  request<T>(options: HttpRequestOptions): Promise<T>;
}

export function createHttpClient(dependencies: HttpClientDependencies): HttpClient {
  return {
    async request<T>(options: HttpRequestOptions): Promise<T> {
      const header: Record<string, string> = { Accept: "application/json" };
      if (options.body !== undefined) {
        header["Content-Type"] = "application/json";
      }

      if (options.authenticated) {
        const session = readAuthenticatedSession(dependencies);
        if (session === null) {
          dependencies.onUnauthorized();
          throw new ApiError(401, "SESSION_INVALID", "会话无效或已过期");
        }
        header.Authorization = `Bearer ${session.token}`;
      }
      applyCallerHeaders(header, options.headers);

      let response: HttpTransportResponse;
      try {
        response = await dependencies.transport({
          url: `${dependencies.baseUrl}${options.path}`,
          method: options.method,
          header,
          data: options.body === undefined ? undefined : JSON.stringify(options.body),
        });
      } catch {
        throw new ApiError(0, "NETWORK_ERROR", "网络异常，请稍后重试");
      }

      if (response.statusCode === 204) {
        return undefined as T;
      }

      if (response.statusCode >= 200 && response.statusCode < 300) {
        try {
          return readJson(response.data) as T;
        } catch {
          throw new ApiError(response.statusCode, "HTTP_ERROR", "请求失败");
        }
      }

      const error = toApiError(response.statusCode, response.data);
      if (options.authenticated && response.statusCode === 401) {
        clearAuthenticatedSession(dependencies);
      }
      throw error;
    },
  };
}

function applyCallerHeaders(header: Record<string, string>, extra: Readonly<Record<string, string>> | undefined): void {
  if (extra === undefined) {
    return;
  }
  for (const [name, value] of Object.entries(extra)) {
    if (!isReservedHeader(name)) {
      header[name] = value;
    }
  }
}

function isReservedHeader(name: string): boolean {
  const normalized = name.toLowerCase();
  return normalized === "authorization" || normalized === "content-type" || normalized === "accept";
}

function readAuthenticatedSession(dependencies: HttpClientDependencies): Session | null {
  try {
    return restoreSession(dependencies.storage, dependencies.now());
  } catch {
    return null;
  }
}

function clearAuthenticatedSession(dependencies: HttpClientDependencies): void {
  try {
    clearSession(dependencies.storage);
  } catch {
    // A storage failure must not replace the server 401 or skip the login transition.
  }
  dependencies.onUnauthorized();
}

function readJson(data: unknown): unknown {
  if (typeof data !== "string") {
    return data;
  }
  if (data.trim().length === 0) {
    return undefined;
  }
  return JSON.parse(data) as unknown;
}

function toApiError(status: number, data: unknown): ApiError {
  let body = data;
  if (typeof data === "string") {
    try {
      body = readJson(data);
    } catch {
      return new ApiError(status, "HTTP_ERROR", "请求失败");
    }
  }
  if (!isRecord(body) || !isRecord(body.error)) {
    return new ApiError(status, "HTTP_ERROR", "请求失败");
  }

  const error = body.error;
  if (typeof error.code !== "string") {
    return new ApiError(status, "HTTP_ERROR", "请求失败");
  }
  const message = typeof error.message === "string" ? error.message : "请求失败";
  const requestId = typeof error.requestId === "string" ? error.requestId : undefined;
  return new ApiError(status, error.code, message, requestId, parseDetails(error.details));
}

function parseDetails(value: unknown): readonly ApiErrorDetail[] | undefined {
  if (!Array.isArray(value)) {
    return undefined;
  }

  const details: ApiErrorDetail[] = [];
  for (const item of value) {
    if (!isRecord(item) || typeof item.field !== "string" || typeof item.reason !== "string") {
      return undefined;
    }
    details.push({ field: item.field, reason: item.reason });
  }
  return details;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}
