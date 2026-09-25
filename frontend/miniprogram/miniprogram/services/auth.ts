import { ApiError, type HttpClient } from "../http/client";
import { clearSession } from "../session/session";
import type { KeyValueStorage } from "../storage/key-value";

export interface LoginResult {
  readonly token: string;
  readonly expiresAt: string;
}

export interface AuthApi {
  login(username: string, password: string): Promise<LoginResult>;
  logout(): Promise<void>;
}

export function createAuthApi(http: HttpClient, storage: KeyValueStorage): AuthApi {
  return {
    async login(username, password) {
      const result = await http.request<unknown>({
        method: "POST",
        path: "/api/v1/auth/login",
        body: { username, password },
        authenticated: false,
      });
      if (!isLoginResult(result)) {
        throw new ApiError(200, "HTTP_ERROR", "登录失败，请稍后重试");
      }
      return result;
    },

    async logout() {
      try {
        await http.request<void>({
          method: "POST",
          path: "/api/v1/auth/logout",
          authenticated: true,
        });
        clearSession(storage);
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          try {
            clearSession(storage);
          } catch {
            // The authenticated 401 path already owns the transition to Login.
          }
          return;
        }
        throw error;
      }
    },
  };
}

function isLoginResult(value: unknown): value is LoginResult {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    typeof record.token === "string" &&
    record.token.length > 0 &&
    typeof record.expiresAt === "string" &&
    Number.isFinite(Date.parse(record.expiresAt))
  );
}
