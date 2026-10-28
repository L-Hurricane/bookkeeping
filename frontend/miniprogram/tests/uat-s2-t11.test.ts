import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import {
  accountListAfterArchivePath,
  accountListReloadMode,
  archiveArrivalFeedback,
  parseAccountListNotice,
} from "../miniprogram/account/navigation";
import type { Account } from "../miniprogram/account/account";
import { initialTransferAccountIds } from "../miniprogram/transfer/defaults";

const active = (id: number, name: string): Account => ({
  id,
  name,
  kind: "NORMAL",
  currency: "CNY",
  openingBalance: "0.00",
  currentBalance: "0.00",
  createdTime: "2026-10-01T10:00:00+08:00",
  lifecycle: "ACTIVE",
});

describe("UAT-01 archive return", () => {
  it("replaces the stack with the active list and names the archived account", () => {
    assert.equal(accountListAfterArchivePath(42), "/pages/account-list/index?notice=archived&accountId=42");
    assert.deepEqual(parseAccountListNotice({ notice: "archived", accountId: "42" }), { accountId: 42 });
    assert.equal(parseAccountListNotice({}), null);
    assert.equal(parseAccountListNotice({ notice: "archived" }), null);
  });

  it("shows success only after the active list no longer contains that account", () => {
    assert.equal(archiveArrivalFeedback([{ id: 7 }, { id: 8 }], 42), "success");
    assert.equal(archiveArrivalFeedback([{ id: 42 }], 42), "withhold");
  });

  it("keeps an already visible account list on screen while refreshing", () => {
    assert.equal(accountListReloadMode("loading"), "blocking");
    assert.equal(accountListReloadMode("error"), "blocking");
    assert.equal(accountListReloadMode("ready"), "background");
  });
});

describe("UAT-02 new transfer defaults", () => {
  it("uses the current account as outgoing and leaves incoming unset when several accounts exist", () => {
    const accounts = [active(1, "甲"), active(2, "乙"), active(3, "丙")];
    assert.deepEqual(initialTransferAccountIds(accounts, 2), { fromAccountId: 2, toAccountId: 0 });
  });

  it("does not pick the only other account", () => {
    assert.deepEqual(initialTransferAccountIds([active(1, "甲"), active(2, "乙")], 1), {
      fromAccountId: 1,
      toAccountId: 0,
    });
  });

  it("leaves incoming unset when the only active account is the current account", () => {
    assert.deepEqual(initialTransferAccountIds([active(1, "甲")], 1), { fromAccountId: 1, toAccountId: 0 });
  });

  it("leaves incoming empty when the only active account is not the current account", () => {
    assert.deepEqual(initialTransferAccountIds([active(2, "乙")], 1), { fromAccountId: 0, toAccountId: 0 });
  });

  it("does not select an archived account or an arbitrary active account", () => {
    const archived = { ...active(1, "甲"), lifecycle: "ARCHIVED" as const };
    assert.deepEqual(initialTransferAccountIds([archived, active(2, "乙"), active(3, "丙")], 1), {
      fromAccountId: 0,
      toAccountId: 0,
    });
  });
});

describe("UAT-04 home button target", () => {
  it("makes the account list the mini program home page", () => {
    const app = JSON.parse(readFileSync(new URL("../miniprogram/app.json", import.meta.url), "utf8")) as {
      pages: string[];
    };
    assert.equal(app.pages[0], "pages/account-list/index");
    assert.equal(app.pages.includes("pages/bootstrap/index"), true);
  });
});
