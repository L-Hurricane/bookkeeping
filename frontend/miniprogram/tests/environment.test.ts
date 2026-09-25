import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { resolveEnvironment } from "../miniprogram/config/environment";

describe("resolveEnvironment", () => {
  it("defaults to the local dev API origin", () => {
    const environment = resolveEnvironment();
    assert.equal(environment.name, "dev");
    assert.equal(environment.apiBaseUrl, "http://127.0.0.1:8080");
  });

  it("keeps production without a committed host", () => {
    const environment = resolveEnvironment("production");
    assert.equal(environment.name, "production");
    assert.equal(environment.apiBaseUrl, "");
  });
});
