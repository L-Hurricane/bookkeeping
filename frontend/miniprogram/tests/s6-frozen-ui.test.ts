import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { ApiError } from "../miniprogram/http/client";
import { analysisGenerateMessage } from "../miniprogram/analysis/screen";

const statistics = readFileSync(new URL("../miniprogram/pages/statistics/index.wxml", import.meta.url), "utf8");
const report = readFileSync(new URL("../miniprogram/pages/statistics-report/index.wxml", import.meta.url), "utf8");
const detail = readFileSync(new URL("../miniprogram/pages/statistics-detail/index.wxml", import.meta.url), "utf8");
const settings = readFileSync(new URL("../miniprogram/pages/account-settings/index.wxml", import.meta.url), "utf8");
const mine = readFileSync(new URL("../miniprogram/pages/mine/index.wxml", import.meta.url), "utf8");

describe("s6 frozen ui", () => {
  it("names the statistics sections and gives category and tag equal entries", () => {
    assert.equal(statistics.includes("净值趋势"), true);
    assert.equal(statistics.includes("收入分类对比"), true);
    assert.equal(statistics.includes("收入标签对比"), true);
    assert.equal(statistics.includes("支出分类对比"), true);
    assert.equal(statistics.includes("支出标签对比"), true);
    assert.equal(statistics.includes("净资产"), false);
    assert.equal(report.includes("加入多选"), false);
    assert.equal(report.includes("查看所选标签"), false);
    assert.equal(detail.includes("同时包含其他标签"), true);
    assert.equal(detail.includes("分类构成"), true);
    assert.equal(detail.includes("关联交易"), true);
    assert.equal(detail.includes("含下级"), true);
    assert.equal(detail.includes("仅本标签"), true);
    assert.equal(detail.includes("depth-{{item.depth}}"), true);
    assert.equal(detail.includes("不能逐行相加"), false);
    const detailScript = readFileSync(new URL("../miniprogram/pages/statistics-detail/index.ts", import.meta.url), "utf8");
    const detailStyle = readFileSync(new URL("../miniprogram/pages/statistics-detail/index.wxss", import.meta.url), "utf8");
    assert.equal(detailScript.includes("不能逐行相加"), true);
    assert.equal(detailScript.includes("当前范围净额为零，不计算覆盖率。"), true);
    assert.equal(detailScript.includes("同时包含只按标签及下级计算"), true);
    assert.equal(detailStyle.includes(".row.depth-1"), true);
    assert.equal(detailStyle.includes("word-break: break-all"), true);
  });

  it("moves category and tag management to the ledger mine page", () => {
    assert.equal(mine.includes("分类管理"), true);
    assert.equal(mine.includes("标签管理"), true);
    assert.equal(mine.includes("已归档账户"), true);
    assert.equal(mine.includes("退出登录"), true);
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