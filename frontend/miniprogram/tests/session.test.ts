import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { clearSession, restoreSession, saveSession, type Session } from "../miniprogram/session/session";
import type { KeyValueStorage } from "../miniprogram/storage/key-value";

function memoryStorage(initial?: string): KeyValueStorage & { raw(): string | undefined } {
  let value = initial;
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
    raw() {
      return value;
    },
  };
}

const future = "2099-01-01T00:00:00Z";
const past = "2000-01-01T00:00:00Z";
const now = new Date("2026-09-25T08:00:00Z");
const session: Session = { token: "opaque-token", expiresAt: future };

describe("session storage", () => {
  it("saves and restores a valid session", () => {
    const storage = memoryStorage();
    saveSession(storage, session);
    assert.deepEqual(restoreSession(storage, now), session);
  });

  it("rejects and clears an expired session", () => {
    const storage = memoryStorage();
    saveSession(storage, { token: "opaque-token", expiresAt: past });
    assert.equal(restoreSession(storage, now), null);
    assert.equal(storage.raw(), undefined);
  });

  it("treats the exact expiry instant as expired", () => {
    const storage = memoryStorage();
    saveSession(storage, { token: "opaque-token", expiresAt: now.toISOString() });
    assert.equal(restoreSession(storage, now), null);
    assert.equal(storage.raw(), undefined);
  });

  it("clears malformed stored data", () => {
    for (const raw of ["{", "null", "[]", JSON.stringify({ token: "", expiresAt: future }), JSON.stringify({ expiresAt: future })]) {
      const storage = memoryStorage(raw);
      assert.equal(restoreSession(storage, now), null);
      assert.equal(storage.raw(), undefined);
    }
  });

  it("clears the stored session", () => {
    const storage = memoryStorage();
    saveSession(storage, session);
    clearSession(storage);
    assert.equal(restoreSession(storage, now), null);
  });
});
