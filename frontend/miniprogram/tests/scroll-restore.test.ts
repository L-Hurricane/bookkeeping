import assert from "node:assert/strict";
import { describe, it } from "node:test";
import {
  detailReturnPlan,
  noteListStale,
  noteSavedTransaction,
  openTransactionDetail,
  restoreDetailScroll,
  showOpenList,
  type OpenList,
} from "../miniprogram/navigation/scroll-restore";

describe("detail back scroll", () => {
  it("restores a saved position for the same list without an explicit locate", () => {
    assert.equal(restoreDetailScroll({ scrollTop: 480, context: "2026-09" }, "2026-09", false), 480);
  });

  it("does not restore a previous month after the list context changes", () => {
    assert.equal(restoreDetailScroll({ scrollTop: 480, context: "2026-09" }, "2026-10", false), null);
  });

  it("lets an explicit locate win over a saved detail position", () => {
    assert.equal(restoreDetailScroll({ scrollTop: 480, context: "2026-09" }, "2026-09", true), null);
  });

  it("starts from the normal position when nothing was saved", () => {
    assert.equal(restoreDetailScroll(null, "2026-09", false), null);
  });
});

describe("detail return", () => {
  const visit = { dirty: false, context: "2026-09", scrollTop: 480 };

  it("keeps a read-only return on the current page without refreshing", () => {
    assert.deepEqual(detailReturnPlan(visit, "2026-09", false), { refresh: "keep", scrollTop: null });
  });

  it("refreshes after an edit and leaves the explicit locate in charge of scrolling", () => {
    assert.deepEqual(detailReturnPlan({ ...visit, dirty: true }, "2026-09", true), {
      refresh: "background-refresh",
      scrollTop: null,
    });
  });

  it("refreshes after a delete and keeps the saved position", () => {
    assert.deepEqual(detailReturnPlan({ ...visit, dirty: true }, "2026-09", false), {
      refresh: "background-refresh",
      scrollTop: 480,
    });
  });

  it("reloads for an explicit create locate instead of restoring a detail scroll", () => {
    assert.deepEqual(detailReturnPlan(null, "2026-09", true), { refresh: "reload", scrollTop: null });
  });

  it("reloads when the list context changed", () => {
    assert.deepEqual(detailReturnPlan({ ...visit, dirty: true }, "2026-10", true), { refresh: "reload", scrollTop: null });
  });
});

describe("account home detail return", () => {
  const home = (month: string): OpenList => ({ month, locateMonth: null, visit: null });

  it("keeps a read-only detail return without a refresh", () => {
    const shown = showOpenList(openTransactionDetail(home("2026-09"), 480));
    assert.deepEqual(shown.show, { refresh: "keep" });
  });

  it("does one background refresh after delete and restores the saved position", () => {
    const opened = openTransactionDetail(home("2026-09"), 480);
    const stale = noteListStale(opened);
    assert.equal(stale.visit?.dirty, true);
    const shown = showOpenList(stale);
    assert.deepEqual(shown.show, { refresh: "background-refresh", scrollTop: 480, locate: false });
    assert.equal(shown.state.visit, null);
  });

  it("refreshes an edited transaction once and lets locate own the scroll", () => {
    const opened = openTransactionDetail(home("2026-09"), 480);
    const saved = noteSavedTransaction(opened, "2026-09");
    assert.equal(saved.visit?.context, "2026-09");
    assert.notEqual(saved.visit, null);
    const shown = showOpenList(saved);
    assert.deepEqual(shown.show, { refresh: "background-refresh", scrollTop: null, locate: true });
  });

  it("reloads the target month after an edit and does not restore the old scroll", () => {
    const opened = openTransactionDetail(home("2026-09"), 480);
    const saved = noteSavedTransaction(opened, "2026-10");
    assert.equal(saved.month, "2026-10");
    assert.equal(saved.visit?.context, "2026-09");
    const shown = showOpenList(saved);
    assert.deepEqual(shown.show, { refresh: "reload", scope: "ledger", locate: true });
  });

  it("keeps create locate on one account reload", () => {
    const saved = noteSavedTransaction(home("2026-09"), "2026-10");
    const shown = showOpenList(saved);
    assert.deepEqual(shown.show, { refresh: "reload", scope: "account", locate: true });
  });
});
