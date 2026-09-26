export class IdempotencyKeyError extends Error {
  constructor() {
    super("无法准备保存请求，请重试");
    this.name = "IdempotencyKeyError";
  }
}

export function randomBytesToIdempotencyKey(bytes: Uint8Array): string | null {
  if (bytes.length !== 16) {
    return null;
  }
  let key = "";
  for (const byte of bytes) {
    key += byte.toString(16).padStart(2, "0");
  }
  if (key.length === 0 || key.length > 64) {
    return null;
  }
  return key;
}

export async function createIdempotencyKey(readBytes: (length: number) => Promise<Uint8Array>): Promise<string> {
  const key = randomBytesToIdempotencyKey(await readBytes(16));
  if (key === null) {
    throw new IdempotencyKeyError();
  }
  return key;
}

export interface RandomValuesSource {
  getRandomValues(option: {
    length: number;
    success: (result: { randomValues: ArrayBuffer }) => void;
    fail: () => void;
  }): void;
}

export function readRandomBytes(length: number, source: RandomValuesSource): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    source.getRandomValues({
      length,
      success(result) {
        resolve(new Uint8Array(result.randomValues));
      },
      fail() {
        reject(new IdempotencyKeyError());
      },
    });
  });
}
