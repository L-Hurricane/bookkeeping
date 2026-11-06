import {
  accountKindLabel,
  formatCurrentBalance,
  routeActiveAccounts,
  type Account,
} from "../../account/account";
import {
  hitKey,
  indexFromDelta,
  planAccountDrop,
  planGroupDrop,
  planHome,
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
}

interface SectionView {
  readonly key: string;
  readonly groupId: number | null;
  readonly title: string;
  readonly showSubtotal: boolean;
  readonly subtotalLabel: string;
  readonly expanded: boolean;
  readonly accounts: AccountRow[];
  readonly archivedIds: number[];
}

const ROW_HEIGHT = 72;

Page({
  loadGuard: createLoadGuard(),
  leaveToLogin: false,
  archiveNoticeAccountId: null as number | null,
  accounts: [] as Account[],
  sectionPlans: [] as HomeSectionPlan[],
  collapsedKeys: [] as string[],
  summaryGeneration: 0,
  drag: null as null | { kind: "group" | "account"; id: number; sectionKey: string; startY: number; startIndex: number },

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
        rows: home.grouped ? [] : route.accounts.map(toRow),
        sections: home.sections.map((section) => toSection(section, this.accounts, this.collapsedKeys)),
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
    this.setData({ sorting: !this.data.sorting, draggingKey: "", dragOffset: 0 });
  },

  onToggleSection(event: WechatMiniprogram.BaseEvent) {
    if (this.data.sorting) {
      return;
    }
    const key = String(event.currentTarget.dataset["key"] ?? "");
    if (key.length === 0) {
      return;
    }
    this.collapsedKeys = this.collapsedKeys.includes(key)
      ? this.collapsedKeys.filter((item) => item !== key)
      : [...this.collapsedKeys, key];
    this.setData({
      sections: this.sectionPlans.map((section) => toSection(section, this.accounts, this.collapsedKeys)),
    });
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
    this.drag = { kind, id, sectionKey, startY: touch.clientY, startIndex };
    this.setData({ draggingKey: `${kind}:${id}`, dragOffset: 0 });
  },

  onDragMove(event: WechatMiniprogram.TouchEvent) {
    const drag = this.drag;
    const touch = event.touches[0];
    if (drag === null || touch === undefined) {
      return;
    }
    this.setData({ dragOffset: touch.clientY - drag.startY });
  },

  async onDragEnd(event: WechatMiniprogram.TouchEvent) {
    const drag = this.drag;
    this.drag = null;
    this.setData({ draggingKey: "", dragOffset: 0 });
    const touch = event.changedTouches[0];
    if (drag === null || touch === undefined) {
      return;
    }
    const rects = await measureDropTargets(this);
    const targetKey = hitKey(rects, touch.clientY);
    try {
      if (drag.kind === "group") {
        const groupIds = this.sectionPlans.flatMap((section) => section.groupId === null ? [] : [section.groupId]);
        const toIndex = indexFromDelta(drag.startIndex, touch.clientY - drag.startY, ROW_HEIGHT, groupIds.length);
        const next = planGroupDrop(groupIds, drag.id, toIndex);
        if (next === null) {
          return;
        }
        await accountApi.reorderGroups(next);
      } else {
        const toKey = targetKey ?? drag.sectionKey;
        const target = this.sectionPlans.find((section) => section.key === toKey);
        const toIndex = toKey === drag.sectionKey
          ? indexFromDelta(drag.startIndex, touch.clientY - drag.startY, ROW_HEIGHT, target?.accountIds.length ?? 0)
          : 0;
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

function toSection(section: HomeSectionPlan, accounts: readonly Account[], collapsedKeys: readonly string[]): SectionView {
  const byId = new Map(accounts.map((account) => [account.id, account]));
  return {
    key: section.key,
    groupId: section.groupId,
    title: section.title,
    showSubtotal: section.subtotal !== null,
    subtotalLabel: section.subtotal === null ? "" : formatMoneyLabel(section.subtotal),
    expanded: !collapsedKeys.includes(section.key),
    accounts: section.accountIds.flatMap((id) => {
      const account = byId.get(id);
      return account === undefined ? [] : [toRow(account)];
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

function toRow(account: Account): AccountRow {
  return {
    id: account.id,
    name: account.name,
    kindLabel: accountKindLabel(account.kind),
    balanceLabel: formatCurrentBalance(account),
    balanceToneClass: moneyToneClass(account.currentBalance),
  };
}
