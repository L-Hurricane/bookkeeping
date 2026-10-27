import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { ApiError } from "../miniprogram/http/client";
import { analysisGenerateMessage } from "../miniprogram/analysis/screen";

const statistics = readFileSync(new URL("../miniprogram/pages/statistics/index.wxml", import.meta.url), "utf8");
const settings = readFileSync(new URL("../miniprogram/pages/account-settings/index.wxml", import.meta.url), "utf8");
const mine = readFileSync(new URL("../miniprogram/pages/mine/index.wxml", import.meta.url), "utf8");

describe("s6 frozen ui", () => {
  it("names the statistics sections and gives category and tag equal entries", () => {
    assert.equal(statistics.includes("账本净值"), true);
    assert.equal(statistics.includes("收入分析"), true);
    assert.equal(statistics.includes("支出分析"), true);
    assert.equal(statistics.includes("净资产"), false);
    assert.equal(statistics.includes('data-breakdown="CATEGORY"'), true);
    assert.equal(statistics.includes('data-breakdown="TAG"'), true);
    assert.equal(statistics.includes("加入多选"), true);
  });

  it("moves category and tag management to the ledger mine page", () => {
    assert.equal(mine.includes("分类管理"), true);
    assert.equal(mine.includes("标签管理"), true);
    assert.equal(settings.includes("分类管理"), false);
    assert.equal(settings.includes("标签管理"), false);
    assert.equal(settings.includes("归档账户"), true);
  });

  it("explains an empty ledger period without the generic failure sentence", () => {
    assert.equal(
      analysisGenerateMessage(new ApiError(409, "EMPTY_PERIOD", "server")),
      "当前期间没有可分析的收支记录。所指是这个期间的账本还没有收入或支出。",
    );
  });
});