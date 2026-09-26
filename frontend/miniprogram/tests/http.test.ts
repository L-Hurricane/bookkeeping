import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { ApiError, createHttpClient, type HttpTransportRequest, type HttpTransportResponse } from "../miniprogram/http/client";
import { createAuthApi } from "../miniprogram/services/auth";
import { restoreSession, saveSession } from "../miniprogram/session/session";
import type { KeyValueStorage } from "../miniprogram/storage/key-value";

function memoryStorage(): KeyValueStorage {
  let value: string | undefined;
  return {
    getItem() {
      return value;
    },
    setItem(_key, next) {
      value = next;
    },
    removeItem() {
      value = undefined;
    },
  };
}

function clientFor(
  storage: KeyValueStorage,
  responder: (request: HttpTransportRequest) => HttpTransportResponse | Promise<HttpTransportResponse>,
  onUnauthorized: () => void = () => undefined,
) {
  const seen: HttpTransportRequest[] = [];
  const http = createHttpClient({
    baseUrl: "http://127.0.0.1:8080",
    storage,
    now: () => new Date("2026-09-25T08:00:00Z"),
    onUnauthorized,
    transport: async (request) => {
      seen.push(request);
      return responder(request);
    },
  });
  return { http, seen };
}

const activeSession = { token: "opaque-token", expiresAt: "2099-01-01T00:00:00Z" };

describe("http client", () => {
  it("adds Authorization only for authenticated requests", async () => {
    const storage = memoryStorage();
    saveSession(storage, activeSession);
    const { http, seen } = clientFor(storage, () => ({ statusCode: 204, data: "" }));

    await http.request({ method: "POST", path: "/api/v1/auth/logout", authenticated: true });
    await http.request({
      method: "POST",
      path: "/api/v1/auth/login",
      body: { username: "demo", password: "secret" },
      authenticated: false,
    });

    assert.equal(seen[0]?.header.Authorization, "Bearer opaque-token");
    assert.equal(seen[1]?.header.Authorization, undefined);
    assert.equal(seen[1]?.data, JSON.stringify({ username: "demo", password: "secret" }));
    assert.equal(seen[1]?.header["Idempotency-Key"], undefined);
  });

  it("sends an extra Idempotency-Key without replacing client headers", async () => {
    const storage = memoryStorage();
    saveSession(storage, activeSession);
    const { http, seen } = clientFor(storage, () => ({ statusCode: 201, data: { id: 1 } }));
    await http.request({
      method: "POST",
      path: "/api/v1/accounts/1001/transactions",
      body: { amount: "30" },
      authenticated: true,
      headers: {
        "Idempotency-Key": "a14f",
        Authorization: "Bearer stolen",
        "Content-Type": "text/plain",
        Accept: "text/html",
      },
    });
    assert.equal(seen[0]?.header["Idempotency-Key"], "a14f");
    assert.equal(seen[0]?.header.Authorization, "Bearer opaque-token");
    assert.equal(seen[0]?.header["Content-Type"], "application/json");
    assert.equal(seen[0]?.header.Accept, "application/json");
  });

  it("sends PUT with a body and DELETE without a body", async () => {
    const storage = memoryStorage();
    saveSession(storage, activeSession);
    const { http, seen } = clientFor(storage, (request) => ({ statusCode: request.method === "DELETE" ? 204 : 200, data: "" }));
    await http.request({ method: "GET", path: "/api/v1/accounts/1001/transactions/4001", authenticated: true });
    await http.request({
      method: "PUT",
      path: "/api/v1/accounts/1001/transactions/4001",
      body: { amount: "30.00", tagIds: [1] },
      authenticated: true,
    });
    await http.request({ method: "DELETE", path: "/api/v1/accounts/1001/transactions/4001", authenticated: true });
    assert.equal(seen[0]?.method, "GET");
    assert.equal(seen[0]?.header["Content-Type"], undefined);
    assert.equal(seen[0]?.header.Authorization, "Bearer opaque-token");
    assert.equal(seen[1]?.method, "PUT");
    assert.equal(seen[1]?.data, JSON.stringify({ amount: "30.00", tagIds: [1] }));
    assert.equal(seen[1]?.header["Content-Type"], "application/json");
    assert.equal(seen[1]?.header.Authorization, "Bearer opaque-token");
    assert.equal(seen[2]?.method, "DELETE");
    assert.equal(seen[2]?.data, undefined);
    assert.equal(seen[2]?.header["Content-Type"], undefined);
    assert.equal(seen[2]?.header.Authorization, "Bearer opaque-token");
  });

  it("maps the backend error shape", async () => {
    const { http } = clientFor(memoryStorage(), () => ({
      statusCode: 400,
      data: {
        error: {
          code: "VALIDATION_FAILED",
          message: "请求不合法",
          requestId: "req-1",
          details: [{ field: "username", reason: "blank" }],
        },
      },
    }));

    await assert.rejects(
      () => http.request({ method: "POST", path: "/api/v1/auth/login", authenticated: false }),
      (error: unknown) => {
        assert.ok(error instanceof ApiError);
        assert.equal(error.status, 400);
        assert.equal(error.code, "VALIDATION_FAILED");
        assert.equal(error.message, "请求不合法");
        assert.equal(error.requestId, "req-1");
        assert.deepEqual(error.details, [{ field: "username", reason: "blank" }]);
        return true;
      },
    );
  });

  it("does not apply authenticated-session handling to login 401", async () => {
    const storage = memoryStorage();
    saveSession(storage, activeSession);
    let unauthorized = 0;
    const { http } = clientFor(
      storage,
      () => ({
        statusCode: 401,
        data: { error: { code: "AUTHENTICATION_FAILED", message: "用户名或密码错误", requestId: "req-2" } },
      }),
      () => {
        unauthorized += 1;
      },
    );

    await assert.rejects(
      () =>
        http.request({
          method: "POST",
          path: "/api/v1/auth/login",
          body: { username: "demo", password: "wrong" },
          authenticated: false,
        }),
      (error: unknown) => error instanceof ApiError && error.code === "AUTHENTICATION_FAILED",
    );
    assert.equal(unauthorized, 0);
    assert.deepEqual(restoreSession(storage, new Date("2026-09-25T08:00:00Z")), activeSession);
  });

  it("still starts the login transition when clearing storage fails on authenticated 401", async () => {
    const storage = memoryStorage();
    saveSession(storage, activeSession);
    storage.removeItem = () => {
      throw new Error("storage unavailable");
    };
    let unauthorized = 0;
    const { http } = clientFor(
      storage,
      () => ({
        statusCode: 401,
        data: { error: { code: "SESSION_INVALID", message: "会话无效或已过期", requestId: "req-storage" } },
      }),
      () => {
        unauthorized += 1;
      },
    );

    await assert.rejects(
      () => http.request({ method: "POST", path: "/api/v1/auth/logout", authenticated: true }),
      (error: unknown) => error instanceof ApiError && error.status === 401 && error.code === "SESSION_INVALID",
    );
    assert.equal(unauthorized, 1);
  });

  it("clears the session and reports unauthorized on authenticated 401", async () => {
    const storage = memoryStorage();
    saveSession(storage, activeSession);
    let unauthorized = 0;
    const { http } = clientFor(
      storage,
      () => ({
        statusCode: 401,
        data: { error: { code: "SESSION_INVALID", message: "会话无效或已过期", requestId: "req-3" } },
      }),
      () => {
        unauthorized += 1;
      },
    );

    await assert.rejects(
      () => http.request({ method: "POST", path: "/api/v1/auth/logout", authenticated: true }),
      (error: unknown) => error instanceof ApiError && error.status === 401,
    );
    assert.equal(unauthorized, 1);
    assert.equal(restoreSession(storage, new Date("2026-09-25T08:00:00Z")), null);
  });

  it("returns no body for 204", async () => {
    const storage = memoryStorage();
    saveSession(storage, activeSession);
    const { http } = clientFor(storage, () => ({ statusCode: 204, data: "" }));
    const result = await http.request<void>({ method: "POST", path: "/api/v1/auth/logout", authenticated: true });
    assert.equal(result, undefined);
  });
});

describe("auth api", () => {
  it("clears the local session after logout 204", async () => {
    const storage = memoryStorage();
    saveSession(storage, activeSession);
    const { http } = clientFor(storage, () => ({ statusCode: 204, data: "" }));
    await createAuthApi(http, storage).logout();
    assert.equal(restoreSession(storage, new Date("2026-09-25T08:00:00Z")), null);
  });

  it("clears the local session after logout 401", async () => {
    const storage = memoryStorage();
    saveSession(storage, activeSession);
    const { http } = clientFor(storage, () => ({
      statusCode: 401,
      data: { error: { code: "SESSION_INVALID", message: "会话无效或已过期", requestId: "req-4" } },
    }));
    await createAuthApi(http, storage).logout();
    assert.equal(restoreSession(storage, new Date("2026-09-25T08:00:00Z")), null);
  });

  it("rejects a login response whose expiresAt is not a timestamp", async () => {
    const { http } = clientFor(memoryStorage(), () => ({
      statusCode: 200,
      data: { token: "opaque-token", expiresAt: "tomorrow" },
    }));
    await assert.rejects(
      () => createAuthApi(http, memoryStorage()).login("demo", "secret"),
      (error: unknown) => error instanceof ApiError && error.code === "HTTP_ERROR",
    );
  });

  it("does not treat other logout failures as success", async () => {
    const storage = memoryStorage();
    saveSession(storage, activeSession);
    const { http } = clientFor(storage, () => ({
      statusCode: 500,
      data: { error: { code: "INTERNAL_ERROR", message: "服务暂时不可用", requestId: "req-5" } },
    }));
    await assert.rejects(() => createAuthApi(http, storage).logout(), (error: unknown) => error instanceof ApiError);
    assert.deepEqual(restoreSession(storage, new Date("2026-09-25T08:00:00Z")), activeSession);
  });
});
