import { RESTORE_CONFIRM } from "../../account/archive-copy";
import { accountKindLabel, formatCurrentBalance, type Account } from "../../account/account";
import { beginLoad, canApplyLoadResult, createLoadGuard } from "../../account/load-guard";
import { moneyToneClass } from "../../account/money-tone";
import { accountHomePath } from "../../account/navigation";
import { ApiError } from "../../http/client";
import { isUnauthorized } from "../../services/ledger";
import { accountApi } from "../../services/runtime";

interface ArchiveRow {
  readonly id: number;
  readonly name: string;
  readonly kindLabel: string;
  readonly balanceLabel: string;
  readonly balanceToneClass: string;
}

Page({
  loadGuard: createLoadGuard(),

  data: {
    phase: "loading" as "loading" | "ready" | "error",
    rows: [] as ArchiveRow[],
    restoringId: 0,
    errorMessage: "",
  },

  onShow() {
    void this.loadArchived();
  },

  onUnload() {
    this.loadGuard.pageAlive = false;
  },

  async loadArchived() {
    const generation = beginLoad(this.loadGuard);
    if (generation === null) {
      return;
    }
    this.setData({ phase: "loading", errorMessage: "" });
    try {
      const items = await accountApi.listAccounts("ARCHIVED");
      if (!canApplyLoadResult(this.loadGuard, generation)) {
        return;
      }
      this.setData({ phase: "ready", rows: items.map(toRow), restoringId: 0, errorMessage: "" });
    } catch (error) {
      if (!canApplyLoadResult(this.loadGuard, generation) || isUnauthorized(error)) {
        return;
      }
      this.setData({ phase: "error", restoringId: 0, errorMessage: "归档账户读取失败，请稍后重试" });
    }
  },

  onRetry() {
    void this.loadArchived();
  },

  onOpenAccount(event: WechatMiniprogram.BaseEvent) {
    if (this.data.restoringId !== 0) {
      return;
    }
    const accountId = readId(event);
    if (accountId === null) {
      return;
    }
    wx.navigateTo({ url: accountHomePath(accountId) });
  },

  onRestore(event: WechatMiniprogram.BaseEvent) {
    if (this.data.restoringId !== 0) {
      return;
    }
    const accountId = readId(event);
    if (accountId === null) {
      return;
    }
    wx.showModal({
      title: "恢复账户",
      content: RESTORE_CONFIRM,
      confirmText: "恢复",
      cancelText: "取消",
      success: (result) => {
        if (!result.confirm || this.data.restoringId !== 0) {
          return;
        }
        void this.restore(accountId);
      },
    });
  },

  async restore(accountId: number) {
    this.setData({ restoringId: accountId, errorMessage: "" });
    try {
      await accountApi.restoreAccount(accountId);
      wx.showToast({ title: "已恢复", icon: "success" });
      await this.loadArchived();
    } catch (error) {
      if (isUnauthorized(error)) {
        return;
      }
      this.setData({
        restoringId: 0,
        errorMessage: error instanceof ApiError && error.status === 404 ? "账户不存在或无权访问" : "恢复失败，请稍后重试",
      });
    }
  },
});

function toRow(account: Account): ArchiveRow {
  return {
    id: account.id,
    name: account.name,
    kindLabel: accountKindLabel(account.kind),
    balanceLabel: formatCurrentBalance(account),
    balanceToneClass: moneyToneClass(account.currentBalance),
  };
}

function readId(event: WechatMiniprogram.BaseEvent): number | null {
  const accountId = Number(event.currentTarget.dataset["id"]);
  if (!Number.isSafeInteger(accountId) || accountId <= 0) {
    return null;
  }
  return accountId;
}
