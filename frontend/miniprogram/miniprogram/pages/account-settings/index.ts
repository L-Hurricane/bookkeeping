import { ARCHIVE_CONFIRM, RESTORE_CONFIRM } from "../../account/archive-copy";
import { accountKindLabel, type Account } from "../../account/account";
import { appendPlacement, groupNameFor, planHome, type AccountGroup } from "../../account/grouping";
import { beginLoad, canApplyLoadResult, createLoadGuard } from "../../account/load-guard";
import { accountListAfterArchivePath, accountPermanentDeletionPath, parseAccountSettingsQuery } from "../../account/navigation";
import { ApiError } from "../../http/client";
import { AccountResponseError } from "../../services/account";
import { isUnauthorized } from "../../services/ledger";
import { accountApi } from "../../services/runtime";

Page({
  loadGuard: createLoadGuard(),
  accountId: 0,
  account: null as Account | null,
  groups: [] as AccountGroup[],
  activeAccounts: [] as Account[],
  archivedAccounts: [] as Account[],

  data: {
    phase: "loading" as "invalid" | "loading" | "ready" | "error",
    kindLabel: "",
    name: "",
    lifecycle: "ACTIVE" as Account["lifecycle"],
    groupLabel: "未分组",
    groupChoices: [] as GroupChoice[],
    submitting: false,
    errorMessage: "",
  },

  onLoad(query: Record<string, string | undefined>) {
    const context = parseAccountSettingsQuery(query);
    if (context === null) {
      this.setData({ phase: "invalid" });
      return;
    }
    this.accountId = context.accountId;
    void this.loadAccount();
  },

  onUnload() {
    this.loadGuard.pageAlive = false;
  },

  async loadAccount() {
    const generation = beginLoad(this.loadGuard);
    if (generation === null) {
      return;
    }
    this.setData({ phase: "loading", errorMessage: "" });
    try {
      const [account, groups, active, archived] = await Promise.all([
        accountApi.loadAccount(this.accountId),
        accountApi.listGroups(),
        accountApi.listAccounts(),
        accountApi.listAccounts("ARCHIVED"),
      ]);
      if (!canApplyLoadResult(this.loadGuard, generation)) {
        return;
      }
      if (account === null) {
        this.setData({ phase: "error", errorMessage: "账户不存在或无权访问" });
        return;
      }
      this.account = account;
      this.groups = groups;
      this.activeAccounts = active;
      this.archivedAccounts = archived;
      this.setData({
        phase: "ready",
        kindLabel: accountKindLabel(account.kind),
        name: account.name,
        lifecycle: account.lifecycle,
        groupLabel: groupNameFor(account, groups),
        groupChoices: groupChoices(groups, account.groupId),
        errorMessage: "",
      });
    } catch (error) {
      if (!canApplyLoadResult(this.loadGuard, generation) || isUnauthorized(error)) {
        return;
      }
      this.setData({ phase: "error", errorMessage: "账户读取失败，请稍后重试" });
    }
  },

  onRetry() {
    void this.loadAccount();
  },

  onBack() {
    if (this.data.submitting) {
      return;
    }
    wx.navigateBack();
  },

  onNameInput(event: WechatMiniprogram.Input) {
    this.setData({ name: event.detail.value, errorMessage: "" });
  },

  onSelectGroup(event: WechatMiniprogram.BaseEvent) {
    const groupKey = String(event.currentTarget.dataset["group"] ?? "");
    this.setData({
      groupChoices: this.data.groupChoices.map((choice) => ({ ...choice, selected: choice.groupKey === groupKey })),
      errorMessage: "",
    });
  },

  async onSave() {
    if (this.data.submitting || this.data.phase !== "ready") {
      return;
    }
    const name = this.data.name.trim();
    if (name.length === 0) {
      this.setData({ errorMessage: "请输入账户名称" });
      return;
    }
    if (name.length > 64) {
      this.setData({ errorMessage: "账户名称最长 64 个字符" });
      return;
    }
    this.setData({ submitting: true, errorMessage: "" });
    const current = this.account;
    if (current === null) {
      this.setData({ submitting: false, errorMessage: "账户读取失败，请稍后重试" });
      return;
    }
    try {
      const selected = this.data.groupChoices.find((choice) => choice.selected);
      const nextGroupId = selected === undefined ? current.groupId : selected.groupId;
      let account = current;
      if (name !== account.name) {
        account = await accountApi.renameAccount(this.accountId, name);
        this.account = account;
      }
      if (account.lifecycle === "ACTIVE" && nextGroupId !== account.groupId) {
        const home = planHome(this.groups, this.activeAccounts, this.archivedAccounts);
        const section = home.sections.find((item) => item.groupId === nextGroupId) ?? {
          key: nextGroupId === null ? "ungrouped" : `group:${nextGroupId}`,
          groupId: nextGroupId,
          title: "",
          subtotal: null,
          accountIds: [],
          archivedIds: this.archivedAccounts.filter((item) => item.groupId === nextGroupId).map((item) => item.id),
        };
        const placement = appendPlacement(section, this.accountId);
        await accountApi.placeAccount(this.accountId, placement.groupId, placement.orderedIds);
      }
      this.setData({ submitting: false, name: account.name, lifecycle: account.lifecycle });
      wx.showToast({ title: "已保存", icon: "success" });
      setTimeout(() => {
        wx.navigateBack();
      }, 400);
    } catch (error) {
      if (isUnauthorized(error)) {
        return;
      }
      this.setData({ submitting: false, errorMessage: renameFailureMessage(error) });
    }
  },

  async onArchive() {
    await this.changeLifecycle("archive");
  },

  async onRestore() {
    await this.changeLifecycle("restore");
  },

  onPermanentDelete() {
    if (this.data.submitting || this.data.phase !== "ready" || this.data.lifecycle !== "ARCHIVED") {
      return;
    }
    wx.navigateTo({ url: accountPermanentDeletionPath(this.accountId) });
  },

  async changeLifecycle(action: "archive" | "restore") {
    if (this.data.submitting || this.data.phase !== "ready") {
      return;
    }
    const archiving = action === "archive";
    if (this.data.lifecycle !== (archiving ? "ACTIVE" : "ARCHIVED")) {
      return;
    }
    const confirmed = await ask(archiving ? "归档账户" : "恢复账户", archiving ? ARCHIVE_CONFIRM : RESTORE_CONFIRM, archiving ? "归档" : "恢复");
    if (!confirmed || this.data.submitting) {
      return;
    }
    this.setData({ submitting: true, errorMessage: "" });
    try {
      const account = archiving
        ? await accountApi.archiveAccount(this.accountId)
        : await accountApi.restoreAccount(this.accountId);
      this.account = account;
      if (archiving) {
        if (account.lifecycle !== "ARCHIVED") {
          this.setData({ submitting: false, errorMessage: "归档失败，请稍后重试" });
          return;
        }
        this.setData({ submitting: false, name: account.name, lifecycle: account.lifecycle, errorMessage: "" });
        wx.reLaunch({
          url: accountListAfterArchivePath(this.accountId),
          fail: () => {
            this.setData({ errorMessage: "已归档，但返回账户列表失败，请稍后重试" });
          },
        });
        return;
      }
      this.setData({ submitting: false, name: account.name, lifecycle: account.lifecycle, errorMessage: "" });
      wx.showToast({ title: "已恢复", icon: "success" });
    } catch (error) {
      if (isUnauthorized(error)) {
        return;
      }
      this.setData({
        submitting: false,
        errorMessage: error instanceof ApiError && error.status === 404 ? "账户不存在或无权访问" : archiving ? "归档失败，请稍后重试" : "恢复失败，请稍后重试",
      });
    }
  },
});

function ask(title: string, content: string, confirmText: string): Promise<boolean> {
  return new Promise((resolve) => {
    wx.showModal({
      title,
      content,
      confirmText,
      cancelText: "取消",
      success: (result) => resolve(result.confirm),
      fail: () => resolve(false),
    });
  });
}

interface GroupChoice {
  readonly groupKey: string;
  readonly groupId: number | null;
  readonly name: string;
  readonly selected: boolean;
}

function groupChoices(groups: readonly AccountGroup[], selectedId: number | null): GroupChoice[] {
  return [
    { groupKey: "none", groupId: null, name: "未分组", selected: selectedId === null },
    ...groups.map((group) => ({
      groupKey: `group:${group.id}`,
      groupId: group.id,
      name: group.name,
      selected: group.id === selectedId,
    })),
  ];
}

function renameFailureMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "ACCOUNT_NAME_CONFLICT") {
      return "账户名称已存在";
    }
    if (error.code === "VALIDATION_FAILED") {
      return error.message.length > 0 ? error.message : "请检查填写内容";
    }
    if (error.status === 404) {
      return "账户不存在或无权访问";
    }
  }
  if (error instanceof AccountResponseError) {
    return error.message;
  }
  return "保存失败，请稍后重试";
}
