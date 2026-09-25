import type { KeyValueStorage } from "../storage/key-value";

const SESSION_STORAGE_KEY = "bookkeeping.session";

export interface Session {
  readonly token: string;
  readonly expiresAt: string;
}

export function saveSession(storage: KeyValueStorage, session: Session): void {
  storage.setItem(
    SESSION_STORAGE_KEY,
    JSON.stringify({ token: session.token, expiresAt: session.expiresAt }),
  );
}

export function restoreSession(storage: KeyValueStorage, now: Date): Session | null {
  const raw = storage.getItem(SESSION_STORAGE_KEY);
  if (raw === undefined) {
    return null;
  }

  const session = parseSession(raw);
  if (session === null || !isUnexpired(session.expiresAt, now)) {
    storage.removeItem(SESSION_STORAGE_KEY);
    return null;
  }

  return session;
}

export function clearSession(storage: KeyValueStorage): void {
  storage.removeItem(SESSION_STORAGE_KEY);
}

function parseSession(raw: string): Session | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch {
    return null;
  }

  if (typeof parsed !== "object" || parsed === null) {
    return null;
  }

  const record = parsed as Record<string, unknown>;
  const token = record.token;
  const expiresAt = record.expiresAt;
  if (typeof token !== "string" || token.length === 0) {
    return null;
  }
  if (typeof expiresAt !== "string" || !Number.isFinite(Date.parse(expiresAt))) {
    return null;
  }

  return { token, expiresAt };
}

function isUnexpired(expiresAt: string, now: Date): boolean {
  return Date.parse(expiresAt) > now.getTime();
}
