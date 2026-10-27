import type { Account } from "../../account/account";
import { moneyToneClass } from "../../account/money-tone";
import { adjustmentDetailPath } from "../../balance-adjustment/navigation";
import { entryChooserPath, type EntryKind } from "../../entry/fab";
import { ApiError } from "../../http/client";
import { formatBusinessTime, formatMoneyLabel } from "../../ledger/ledger";
import {
  type LedgerFeedItem,
  type LedgerFeedQuery,
  type LedgerFeedScope,
} from "../../ledger/feed-contract";
import { currentBeijingMonth, formatMonthLabel, nextMonth, previousMonth } from "../../ledger/month";
import { accountApi, ledgerFeedApi } from "../../services/runtime";
import { LedgerFeedResponseError } from "../../services/ledger-feed";
import { transactionDetailPath } from "../../transaction/navigation";
import { transferDetailPath } from "../../transfer/navigation";

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

interface FeedRow {
  readonly key: string;
  readonly title: string;
  readonly accountName: string;
  readonly amountLabel: string;
  readonly tone: string;
  readonly timeLabel: string;
  readonly kind: LedgerFeedItem["kind"];
  readonly id: number;
  readonly accountId: number;
  readonly accountCreatedTime: string;
  readonly businessTime: string;
}

Page({
  pageAlive: true,
  generation: 0,
  month: "",
  from: "",
  to: "",
  scope: "ACTIVE" as LedgerFeedScope,
  accountIds: [] as number[],
  searchKeyword: "",
  searchTimer: null as number | null,
  cursor: null as string | null,
  items: [] as LedgerFeedItem[],
  accounts: [] as Account[],

  data: {
    phase: "loading" as "loading" | "ready" | "error",
    mode: "month" as "month" | "range",
    searching: false,
    monthLabel: "",
    label: "",
    summaryReady: false,
    incomeLabel: "",
    expenseLabel: "",
    surplusLabel: "",
    incomeTone: "money-zero",
    expenseTone: "money-zero",
    surplusTone: "money-zero",
    rows: [] as FeedRow[],
    hasMore: false,
    errorMessage: "",
    searchInput: "",
    fabOpen: false,
    canEnter: false,
  },

  onLoad(query: Record<string, string | undefined>) {
    const from = query.from;
    const to = query.to;
    if (from !== undefined && to !== undefined && DATE.test(from) && DATE.test(to)) {
      this.from = from;
      this.to = to;
      this.setData({ mode: "range" });
    } else {
      this.month = query.month !== undefined && MONTH.test(query.month) ? query.month : currentBeijingMonth(new Date());
      this.setData({ mode: "month", monthLabel: formatMonthLabel(this.month) });
    }
    if (query.accountScope === "ALL" || query.accountScope === "SELECTED" || query.accountScope === "ACTIVE") {
      this.scope = query.accountScope;
    }
    this.accountIds = parseIds(query.accountIds);
    void this.loadAccounts();
    void this.reload();
  },

  onUnload() {
    this.pageAlive = false;
    if (this.searchTimer !== null) clearTimeout(this.searchTimer);
  },

  onPreviousMonth() {
    if (this.data.mode !== "month" || this.data.searching) return;
    this.month = previousMonth(this.month);
    void this.reload();
  },

  onNextMonth() {
    if (this.data.mode !== "month" || this.data.searching) return;
    this.month = nextMonth(this.month);
    void this.reload();
  },

  onSearchInput(event: WechatMiniprogram.Input) {
    const value = event.detail.value;
    this.setData({ searchInput: value });
    if (this.searchTimer !== null) clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => {
      this.searchKeyword = value.trim();
      void this.reload();
    }, 300) as unknown as number;
  },

  onRetry() {
    void this.reload();
  },

  onMore() {
    if (this.cursor === null) return;
    void this.load(false);
  },

  onOpen(event: WechatMiniprogram.BaseEvent) {
    const index = Number(event.currentTarget.dataset["index"]);
    const row = this.data.rows[index];
    if (row === undefined) return;
    const month = row.businessTime.slice(0, 7);
    if (row.kind === "TRANSACTION") {
      wx.navigateTo({ url: transactionDetailPath(row.accountId, row.id, row.accountCreatedTime, month) });
      return;
    }
    if (row.kind === "TRANSFER") {
      wx.navigateTo({ url: transferDetailPath(row.accountId, row.id, row.accountCreatedTime) });
      return;
    }
    wx.navigateTo({ url: adjustmentDetailPath(row.accountId, row.id) });
  },

  onToggleFab() {
    this.setData({ fabOpen: !this.data.fabOpen });
  },

  onFabExpense() { this.beginEntry("EXPENSE"); },
  onFabIncome() { this.beginEntry("INCOME"); },
  onFabTransfer() { this.beginEntry("TRANSFER"); },

  beginEntry(kind: EntryKind) {
    if (this.accounts.length === 0) return;
    this.setData({ fabOpen: false });
    wx.navigateTo({ url: entryChooserPath(kind) });
  },

  async loadAccounts() {
    try {
      const accounts = await accountApi.listAccounts("ACTIVE");
      if (!this.pageAlive) return;
      this.accounts = [...accounts];
      this.setData({ canEnter: this.accounts.length > 0 });
    } catch {
      if (!this.pageAlive) return;
      this.accounts = [];
    }
  },

  async reload() {
    this.cursor = null;
    this.items = [];
    await this.load(true);
  },

  async load(replace: boolean) {
    const generation = ++this.generation;
    const searching = this.searchKeyword.length > 0;
    if (replace) this.setData({ phase: this.items.length === 0 ? "loading" : "ready", errorMessage: "", searching });
    const query: LedgerFeedQuery = {
      accountScope: this.scope,
      accountIds: this.scope === "SELECTED" ? this.accountIds : undefined,
      name: searching ? this.searchKeyword : undefined,
      cursor: this.cursor ?? undefined,
      month: !searching && this.data.mode === "month" ? this.month : undefined,
      from: !searching && this.data.mode === "range" ? this.from : undefined,
      to: !searching && this.data.mode === "range" ? this.to : undefined,
    };
    try {
      const page = await ledgerFeedApi.load(query);
      if (!this.pageAlive || generation !== this.generation) return;
      this.items = replace ? [...page.items] : [...this.items, ...page.items];
      this.cursor = page.nextCursor;
      const summary = page.summary;
      this.setData({
        phase: "ready",
        searching,
        monthLabel: this.month.length === 0 ? "" : formatMonthLabel(this.month),
        label: page.label ?? "",
        summaryReady: summary !== null,
        incomeLabel: summary === null ? "" : formatMoneyLabel(summary.netIncome),
        expenseLabel: summary === null ? "" : formatMoneyLabel(summary.netExpense),
        surplusLabel: summary === null ? "" : formatMoneyLabel(summary.surplus),
        incomeTone: summary === null ? "money-zero" : moneyToneClass(summary.netIncome),
        expenseTone: summary === null ? "money-zero" : moneyToneClass(summary.netExpense),
        surplusTone: summary === null ? "money-zero" : moneyToneClass(summary.surplus),
        rows: this.items.map(toRow),
        hasMore: page.nextCursor !== null,
        errorMessage: "",
      });
    } catch (error) {
      if (!this.pageAlive || generation !== this.generation || error instanceof ApiError && error.status === 401) return;
      const message = error instanceof LedgerFeedResponseError ? "流水无法识别" : "交易暂时没有读取成功";
      this.setData({ phase: this.items.length === 0 ? "error" : "ready", errorMessage: message });
    }
  },
});

function toRow(item: LedgerFeedItem): FeedRow {
  const title = item.displayName === null || item.displayName.length === 0 ? "未命名交易" : item.displayName;
  return {
    key: `${item.kind}-${item.accountId}-${item.id}`,
    title,
    accountName: item.accountName,
    amountLabel: formatMoneyLabel(item.amount),
    tone: moneyToneClass(item.amount),
    timeLabel: formatBusinessTime(item.businessTime),
    kind: item.kind,
    id: item.id,
    accountId: item.accountId,
    accountCreatedTime: item.accountCreatedTime,
    businessTime: item.businessTime,
  };
}

function parseIds(raw: string | undefined): number[] {
  if (raw === undefined || raw.length === 0) return [];
  const ids: number[] = [];
  for (const part of raw.split(",")) {
    if (!/^[1-9][0-9]*$/.test(part)) return [];
    const id = Number(part);
    if (!Number.isSafeInteger(id)) return [];
    ids.push(id);
  }
  return ids;
}
