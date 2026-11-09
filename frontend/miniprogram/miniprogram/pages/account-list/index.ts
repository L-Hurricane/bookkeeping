import {
  accountKindLabel,
  formatCurrentBalance,
  routeActiveAccounts,
  type Account,
} from "../../account/account";
import {
  edgeScrollDelta,
  hitKey,
  indexFromDelta,
  planAccountDrop,
  planGroupDrop,
  planHome,
  rowShiftUnits,
  toggleCollapsed,
  type DropRect,
  type HomeSectionPlan,
} from "../../account/grouping";
import { beginLoad, canApplyLoadResult, createLoadGuard } from "../../account/load-guard";
import { moneyToneClass } from "../../account/money-tone";
import { entryChooserPath, type EntryKind } from "../../entry/fab";
import { allTransactionsPath } from "../../ledger/feed-contract";
import { formatMoneyLabel } from "../../ledger/ledger";
import {
  accountArchivePath,
  accountCreatePath,
  accountGroupManagePath,
  accountHomePath,
  accountListReloadMode,
  archiveArrivalFeedback,
  parseAccountListNotice,
} from "../../account/navigation";
import { routeBootstrap } from "../../navigation/startup";
import { isUnauthorized } from "../../services/ledger";
import { accountApi, authNavigation, ledgerStatisticsApi } from "../../services/runtime";
import { restoreSession } from "../../session/session";
import { wechatStorage } from "../../storage/wechat-storage";

interface AccountRow {
  readonly id: number;
  readonly name: string;
  readonly kindLabel: string;
  readonly balanceLabel: string;
  readonly balanceToneClass: string;
  readonly shiftPx: number;
}

interface SectionView {
  readonly key: string;
  readonly groupId: number | null;
  readonly title: string;
  readonly showSubtotal: boolean;
  readonly subtotalLabel: string;
  readonly expanded: boolean;
  readonly shiftPx: number;
  readonly accounts: AccountRow[];
  readonly archivedIds: number[];
}

interface ActiveDrag {
  readonly kind: "group" | "account";
  readonly id: number;
  readonly sectionKey: string;
  readonly startY: number;
  readonly startIndex: number;
  readonly startScroll: number;
  overIndex: number;
}

const ROW_HEIGHT = 72;
const EDGE_PX = 80;
const EDGE_STEP = 28;

Page({
  loadGuard: createLoadGuard(),
  leaveToLogin: false,
  archiveNoticeAccountId: null as number | null,
  accounts: [] as Account[],
  sectionPlans: [] as HomeSectionPlan[],
  collapsedKeys: [] as string[],
  sortCollapsedKeys: [] as string[],
  summaryGeneration: 0,
  viewportHeight: 0,
  pageScrollTop: 0,
  drag: null as ActiveDrag | null,

  data: {
    phase: "loading" as "loading" | "ready" | "error",
    rows: [] as AccountRow[],
    grouped: false,
    sections: [] as SectionView[],
    sorting: false,
    draggingKey: "",
    dragOffset: 0,
    hasArchived: false,
    errorMessage: "",
    refreshError: "",
    loggingOut: false,
    logoutErrorMessage: "",
    summaryReady: false,
    summaryError: "",
        netLabel: "",
        incomeLabel: "",
        expenseLabel: "",
        netTone: "money-zero",
        incomeTone: "money-zero",
        expenseTone: "money-zero",
    fabOpen: false,
  },

  onLoad(query: Record<string, string | undefined>) {
    const notice = parseAccountListNotice(query);
    this.archiveNoticeAccountId = notice === null ? null : notice.accountId;
    if (routeBootstrap(restoreSession(wechatStorage, new Date()) !== null) === "login") {
      this.leaveToLogin = true;
      authNavigation.enterLogin();
    }
  },

  onShow() {
    if (this.leaveToLogin) {
      return;
    }
    void this.loadAccounts();
  },

  onUnload() {
    this.loadGuard.pageAlive = false;
  },

  async loadAccounts() {
    const generation = beginLoad(this.loadGuard);
    if (generation === null) {
      return;
    }
    const blocking = accountListReloadMode(this.data.phase) === "blocking";
    if (blocking) {
      this.setData({ phase: "loading", errorMessage: "", refreshError: "", logoutErrorMessage: "" });
    } else {
      this.setData({ refreshError: "", logoutErrorMessage: "" });
    }
    try {
      const [items, groups, archived] = await Promise.all([
        accountApi.listAccounts(),
        accountApi.listGroups(),
        accountApi.listAccounts("ARCHIVED"),
      ]);
      if (!canApplyLoadResult(this.loadGuard, generation)) {
        return;
      }
      const route = routeActiveAccounts(items, archived);
      if (route.destination === "init") {
        wx.reLaunch({ url: "/pages/account-init/index" });
        return;
      }
      if (route.destination === "error") {
        this.failLoad(blocking, route.message);
        return;
      }
      const home = planHome(groups, route.accounts, archived);
      this.accounts = [...route.accounts];
      this.sectionPlans = [...home.sections];
      this.setData({
        phase: "ready",
        grouped: home.grouped,
        rows: home.grouped ? [] : route.accounts.map((account) => toRow(account, 0)),
        sections: this.viewSections(this.data.sorting),
        sorting: home.grouped ? this.data.sorting : false,
        hasArchived: archived.length > 0,
        errorMessage: "",
        refreshError: "",
      });
      this.acknowledgeArchive(route.accounts);
      void this.loadSummary();
    } catch (error) {
      if (!canApplyLoadResult(this.loadGuard, generation) || isUnauthorized(error)) {
        return;
      }
      this.failLoad(blocking, "账户读取失败，请稍后重试");
    }
  },

  failLoad(blocking: boolean, message: string) {
    if (blocking) {
      this.setData({ phase: "error", errorMessage: message, refreshError: "" });
      return;
    }
    this.setData({ refreshError: message });
  },

  acknowledgeArchive(accounts: readonly Account[]) {
    const accountId = this.archiveNoticeAccountId;
    if (accountId === null) {
      return;
    }
    if (archiveArrivalFeedback(accounts, accountId) !== "success") {
      this.setData({ refreshError: "账户状态未更新，请稍后重试" });
      return;
    }
    this.archiveNoticeAccountId = null;
    wx.showToast({ title: "账户已归档", icon: "success" });
  },

  onRetry() {
    void this.loadAccounts();
  },

  onOpenGroups() {
    if (this.loadGuard.loggingOut) {
      return;
    }
    wx.navigateTo({ url: accountGroupManagePath() });
  },

  onToggleSort() {
    if (!this.data.grouped || this.loadGuard.loggingOut) {
      return;
    }
    this.drag = null;
    const sorting = !this.data.sorting;
    if (sorting) {
      this.sortCollapsedKeys = [];
    }
    this.setData({ sorting, draggingKey: "", dragOffset: 0, sections: this.viewSections(sorting) });
  },

  onPageScroll(event: WechatMiniprogram.Page.IPageScrollOption) {
    if (Number.isFinite(event.scrollTop) && event.scrollTop >= 0) {
      this.pageScrollTop = event.scrollTop;
    }
  },

  onToggleSection(event: WechatMiniprogram.BaseEvent) {
    const key = String(event.currentTarget.dataset["key"] ?? "");
    if (key.length === 0) {
      return;
    }
    if (this.data.sorting) {
      this.sortCollapsedKeys = toggleCollapsed(this.sortCollapsedKeys, key);
    } else {
      this.collapsedKeys = toggleCollapsed(this.collapsedKeys, key);
    }
    this.setData({ sections: this.viewSections(this.data.sorting) });
  },

  onDragStart(event: WechatMiniprogram.TouchEvent) {
    if (!this.data.sorting) {
      return;
    }
    const touch = event.touches[0];
    if (touch === undefined) {
      return;
    }
    const kind = event.currentTarget.dataset["kind"] === "group" ? "group" : "account";
    const id = Number(event.currentTarget.dataset["id"]);
    const sectionKey = String(event.currentTarget.dataset["section"] ?? "");
    if (!Number.isSafeInteger(id) || id <= 0) {
      return;
    }
    const section = this.sectionPlans.find((item) => item.key === sectionKey);
    const startIndex = kind === "group"
      ? this.sectionPlans.filter((item) => item.groupId !== null).findIndex((item) => item.groupId === id)
      : section?.accountIds.indexOf(id) ?? -1;
    if (startIndex < 0) {
      return;
    }
    this.rememberViewport();
    this.drag = { kind, id, sectionKey, startY: touch.clientY, startIndex, startScroll: this.pageScrollTop, overIndex: startIndex };
    this.setData({ draggingKey: `${kind}:${id}`, dragOffset: 0 });
  },

  onDragMove(event: WechatMiniprogram.TouchEvent) {
    const drag = this.drag;
    const touch = event.touches[0];
    if (drag === null || touch === undefined) {
      return;
    }
    const scroll = edgeScrollDelta(touch.clientY, this.viewportHeight, EDGE_PX, EDGE_STEP);
    if (scroll !== 0) {
      this.pageScrollTop = Math.max(0, this.pageScrollTop + scroll);
      wx.pageScrollTo({ scrollTop: this.pageScrollTop, duration: 0 });
    }
    const length = drag.kind === "group"
      ? this.sectionPlans.filter((section) => section.groupId !== null).length
      : this.sectionPlans.find((section) => section.key === drag.sectionKey)?.accountIds.length ?? 0;
    drag.overIndex = indexFromDelta(drag.startIndex, touch.clientY - drag.startY + (this.pageScrollTop - drag.startScroll), ROW_HEIGHT, length);
    this.setData({
      dragOffset: touch.clientY - drag.startY + (this.pageScrollTop - drag.startScroll),
      sections: this.viewSections(true, this.dragShifts(drag)),
    });
  },

  async onDragEnd(event: WechatMiniprogram.TouchEvent) {
    const drag = this.drag;
    this.drag = null;
    this.setData({ draggingKey: "", dragOffset: 0, sections: this.viewSections(this.data.sorting) });
    const touch = event.changedTouches[0];
    if (drag === null || touch === undefined) {
      return;
    }
    const rects = await measureDropTargets(this);
    const targetKey = hitKey(rects, touch.clientY);
    try {
      if (drag.kind === "group") {
        const groupIds = this.sectionPlans.flatMap((section) => section.groupId === null ? [] : [section.groupId]);
        const toIndex = drag.overIndex;
        const next = planGroupDrop(groupIds, drag.id, toIndex);
        if (next === null) {
          return;
        }
        await accountApi.reorderGroups(next);
      } else {
        const toKey = targetKey ?? drag.sectionKey;
        const toIndex = toKey === drag.sectionKey ? drag.overIndex : 0;
        const placement = planAccountDrop(this.sectionPlans, drag.id, drag.sectionKey, toKey, toIndex);
        if (placement === null) {
          return;
        }
        await accountApi.placeAccount(drag.id, placement.groupId, placement.orderedIds);
      }
      await this.loadAccounts();
    } catch (error) {
      if (isUnauthorized(error)) {
        return;
      }
      await this.loadAccounts();
      this.setData({ refreshError: "排序没有保存，已恢复为服务器上的顺序" });
    }
  },

  onOpenAccount(event: WechatMiniprogram.BaseEvent) {
    if (this.loadGuard.loggingOut || this.data.sorting) {
      return;
    }
    const accountId = Number(event.currentTarget.dataset["id"]);
    if (!Number.isSafeInteger(accountId) || accountId <= 0) {
      return;
    }
    wx.navigateTo({ url: accountHomePath(accountId) });
  },

  onCreateAccount() {
    if (this.loadGuard.loggingOut) {
      return;
    }
    wx.navigateTo({ url: accountCreatePath() });
  },

  onOpenArchive() {
    if (this.loadGuard.loggingOut) {
      return;
    }
    wx.navigateTo({ url: accountArchivePath() });
  },

  onOpenAll() {
    if (this.loadGuard.loggingOut) return;
    wx.navigateTo({ url: allTransactionsPath({}) });
  },

  onToggleFab() {
    this.setData({ fabOpen: !this.data.fabOpen });
  },

  onFabExpense() { this.beginEntry("EXPENSE"); },
  onFabIncome() { this.beginEntry("INCOME"); },
  onFabTransfer() { this.beginEntry("TRANSFER"); },

  beginEntry(kind: EntryKind) {
    if (this.loadGuard.loggingOut || this.accounts.length === 0) return;
    this.setData({ fabOpen: false });
    wx.navigateTo({ url: entryChooserPath(kind) });
  },

  viewSections(sorting: boolean, shifts: ReadonlyMap<string, number> = new Map()) {
    const collapsed = sorting ? this.sortCollapsedKeys : this.collapsedKeys;
    return this.sectionPlans.map((section) => toSection(section, this.accounts, collapsed, shifts));
  },

  dragShifts(drag: ActiveDrag): Map<string, number> {
    const shifts = new Map<string, number>();
    if (drag.kind === "group") {
      const groups = this.sectionPlans.filter((section) => section.groupId !== null);
      groups.forEach((section, index) => {
        shifts.set(section.key, rowShiftUnits(index, drag.startIndex, drag.overIndex) * ROW_HEIGHT);
      });
      return shifts;
    }
    const section = this.sectionPlans.find((item) => item.key === drag.sectionKey);
    section?.accountIds.forEach((id, index) => {
      shifts.set(`account:${id}`, rowShiftUnits(index, drag.startIndex, drag.overIndex) * ROW_HEIGHT);
    });
    return shifts;
  },

  rememberViewport() {
    const reader = (wx as unknown as { getWindowInfo?: () => { windowHeight: number } }).getWindowInfo;
    if (reader === undefined) {
      return;
    }
    const height = reader().windowHeight;
    if (Number.isFinite(height) && height > 0) {
      this.viewportHeight = height;
    }
  },

  async loadSummary() {
    const generation = ++this.summaryGeneration;
    try {
      const report = await ledgerStatisticsApi.load({ preset: "MONTH", accountScope: "ACTIVE" });
      if (!canApplyLoadResult(this.loadGuard, this.loadGuard.generation) || generation !== this.summaryGeneration) return;
      this.setData({
        summaryReady: true,
        summaryError: "",
        netLabel: formatMoneyLabel(report.netWorth.closing),
        incomeLabel: formatMoneyLabel(report.income.net),
        expenseLabel: formatMoneyLabel(report.expense.net),
        netTone: moneyToneClass(report.netWorth.closing),
        incomeTone: moneyToneClass(report.income.net),
        expenseTone: moneyToneClass(report.expense.net),
      });
    } catch (error) {
      if (!canApplyLoadResult(this.loadGuard, this.loadGuard.generation) || isUnauthorized(error) || generation !== this.summaryGeneration) return;
      this.setData({ summaryReady: false, summaryError: "本月汇总暂时没有读取成功" });
    }
  },

});

function toSection(
  section: HomeSectionPlan,
  accounts: readonly Account[],
  collapsedKeys: readonly string[],
  shifts: ReadonlyMap<string, number>,
): SectionView {
  const byId = new Map(accounts.map((account) => [account.id, account]));
  return {
    key: section.key,
    groupId: section.groupId,
    title: section.title,
    showSubtotal: section.subtotal !== null,
    subtotalLabel: section.subtotal === null ? "" : formatMoneyLabel(section.subtotal),
    expanded: !collapsedKeys.includes(section.key),
    shiftPx: shifts.get(section.key) ?? 0,
    accounts: section.accountIds.flatMap((id) => {
      const account = byId.get(id);
      return account === undefined ? [] : [toRow(account, shifts.get(`account:${id}`) ?? 0)];
    }),
    archivedIds: [...section.archivedIds],
  };
}

function measureDropTargets(page: WechatMiniprogram.Page.TrivialInstance): Promise<DropRect[]> {
  return new Promise((resolve) => {
    wx.createSelectorQuery().in(page).selectAll(".drop-target").boundingClientRect().exec((result) => {
      const rects = Array.isArray(result?.[0]) ? result[0] : [];
      resolve(rects.flatMap((rect) => {
        const key = String(rect.dataset?.["key"] ?? "");
        if (key.length === 0 || typeof rect.top !== "number" || typeof rect.bottom !== "number") {
          return [];
        }
        return [{ key, top: rect.top, bottom: rect.bottom }];
      }));
    });
  });
}

function toRow(account: Account, shiftPx: number): AccountRow {
  return {
    id: account.id,
    name: account.name,
    kindLabel: accountKindLabel(account.kind),
    balanceLabel: formatCurrentBalance(account),
    balanceToneClass: moneyToneClass(account.currentBalance),
    shiftPx,
  };
}
