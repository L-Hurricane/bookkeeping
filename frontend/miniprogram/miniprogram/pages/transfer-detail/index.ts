import { findAccount, type Account } from "../../account/account";
import { createLoadGuard } from "../../account/load-guard";
import { ApiError } from "../../http/client";
import { accountApi, transferApi } from "../../services/runtime";
import type { TransferView } from "../../transfer/contract";
import {
  classifyTransferDelete,
  confirmTransferDelete,
  transferArchiveHint,
  transferDetailAccess,
  transferDetailReadOnly,
  TRANSFER_DELETE_CONFIRM,
  type TransferDeletePhase,
} from "../../transfer/detail";
import { parseTransferDetailContext, type TransferDetailContext } from "../../transfer/navigation";
import { positiveTransferAmount } from "../../transfer/money";
import { transferPostSavePayload } from "../../transfer/post-save";
import {
  buildTransferSnapshot,
  canSaveTransfer,
  classifyTransferError,
  transferFailureMessage,
  transferFormLocked,
  TRANSFER_UNCERTAIN_HINT,
  editingTransfer,
} from "../../transfer/submission";
import {
  changeFromBusinessTime,
  changeToBusinessTime,
  initialTransferTimes,
  resyncTransferTimes,
  unsyncTransferTimes,
} from "../../transfer/time-sync";
import {
  applyNow,
  applyToday,
  applyYesterday,
  businessTimeDisplay,
  businessTimeDraft,
  businessTimeFromParts,
  type BusinessTimeDraft,
} from "../../transaction/business-time";

type Screen = "invalid" | "loading" | "error" | "missing" | "ready";
type TimeEditorSide = "from" | "to";

Page({
  loadGuard: createLoadGuard(),
  context: null as TransferDetailContext | null,
  transfer: null as TransferView | null,
  accounts: [] as Account[],
  fromAccountId: 0,
  toAccountId: 0,
  fromAccountLabel: "",
  toAccountLabel: "",
  amount: "",
  times: initialTransferTimes("2026-01-01T00:00:00+08:00"),
  save: editingTransfer(),
  deletePhase: "idle" as TransferDeletePhase,
  readOnly: false,
  generation: 0,
  timeDraft: null as BusinessTimeDraft | null,
  timeEditorSide: "from" as TimeEditorSide,
  screen: "loading" as Screen,

  data: {
    screen: "loading" as Screen,
    readOnly: false,
    archiveHint: "",
    fromAccountText: "",
    toAccountText: "",
    accountRows: [] as { id: number; label: string }[],
    fromSelectorOpen: false,
    toSelectorOpen: false,
    amount: "",
    amountError: "",
    fromTimeText: "",
    toTimeText: "",
    syncHint: "",
    syncAction: "",
    formLocked: false,
    saveDisabled: true,
    actionError: "",
    uncertain: false,
    uncertainHint: TRANSFER_UNCERTAIN_HINT,
    showDelete: false,
    deleteLocked: false,
    deleteHint: "",
    deleteUncertain: false,
    timeEditorOpen: false,
    timeEditorSide: "from" as TimeEditorSide,
    timeDate: "",
    timeClock: "",
    timeDraftError: "",
  },

  onLoad(query: Record<string, string | undefined>) {
    const context = parseTransferDetailContext(query);
    if (context === null) {
      this.screen = "invalid";
      this.setData({ screen: "invalid" });
      return;
    }
    this.context = context;
    void this.loadDetail();
  },

  onUnload() {
    this.loadGuard.pageAlive = false;
  },

  onRetry() {
    void this.loadDetail();
  },

  async loadDetail() {
    const context = this.context;
    if (context === null) {
      return;
    }
    const generation = ++this.generation;
    this.screen = "loading";
    this.setData({ screen: "loading", actionError: "" });
    try {
      const [transfer, accounts, sourceAccount] = await Promise.all([
        transferApi.getTransfer(context.transferId),
        accountApi.listAccounts(),
        accountApi.loadAccount(context.sourceAccountId),
      ]);
      const fromAccount = await accountApi.loadAccount(transfer.fromAccountId);
      const toAccount = await accountApi.loadAccount(transfer.toAccountId);
      if (!this.loadGuard.pageAlive || generation !== this.generation) {
        return;
      }
      if (sourceAccount === null) {
        this.showMissing();
        return;
      }
      this.transfer = transfer;
      this.accounts = accounts;
      this.fromAccountId = transfer.fromAccountId;
      this.toAccountId = transfer.toAccountId;
      this.fromAccountLabel = accountName(fromAccount, transfer.fromAccountId);
      this.toAccountLabel = accountName(toAccount, transfer.toAccountId);
      this.amount = transfer.amount;
      this.times = {
        fromBusinessTime: transfer.fromBusinessTime,
        toBusinessTime: transfer.toBusinessTime,
        synced: transfer.fromBusinessTime === transfer.toBusinessTime,
      };
      this.readOnly = transferDetailReadOnly(
        sourceAccount.lifecycle,
        fromAccount?.lifecycle ?? "ARCHIVED",
        toAccount?.lifecycle ?? "ARCHIVED",
      );
      this.save = editingTransfer();
      this.deletePhase = "idle";
      this.screen = "ready";
      this.publish();
    } catch (error) {
      if (!this.loadGuard.pageAlive || generation !== this.generation || isUnauthorized(error)) {
        return;
      }
      if (error instanceof ApiError && error.status === 404) {
        this.showMissing();
        return;
      }
      this.screen = "error";
      this.setData({ screen: "error" });
    }
  },

  showMissing() {
    this.screen = "missing";
    this.setData({ screen: "missing" });
  },

  onOpenFromAccounts() {
    if (!this.canEditFields()) {
      return;
    }
    this.setData({ fromSelectorOpen: true });
  },

  onCloseFromAccounts() {
    this.setData({ fromSelectorOpen: false });
  },

  onChooseFromAccount(event: WechatMiniprogram.BaseEvent) {
    const id = Number(event.currentTarget.dataset["id"]);
    if (!this.canEditFields() || !Number.isSafeInteger(id) || id <= 0) {
      return;
    }
    this.fromAccountId = id;
    if (this.toAccountId === id) {
      this.toAccountId = 0;
    }
    this.setData({ fromSelectorOpen: false, actionError: "" });
    this.publish();
  },

  onOpenToAccounts() {
    if (!this.canEditFields()) {
      return;
    }
    this.setData({ toSelectorOpen: true });
  },

  onCloseToAccounts() {
    this.setData({ toSelectorOpen: false });
  },

  onChooseToAccount(event: WechatMiniprogram.BaseEvent) {
    const id = Number(event.currentTarget.dataset["id"]);
    if (!this.canEditFields() || !Number.isSafeInteger(id) || id <= 0) {
      return;
    }
    this.toAccountId = id;
    if (this.fromAccountId === id) {
      this.fromAccountId = 0;
    }
    this.setData({ toSelectorOpen: false, actionError: "" });
    this.publish();
  },

  onAmount(event: WechatMiniprogram.Input) {
    if (!this.canEditFields()) {
      return;
    }
    this.amount = event.detail.value;
    this.setData({ actionError: "" });
    this.publish();
  },

  onOpenFromTime() {
    this.openTimeEditor("from");
  },

  onOpenToTime() {
    this.openTimeEditor("to");
  },

  openTimeEditor(side: TimeEditorSide) {
    if (!this.canEditFields()) {
      return;
    }
    const value = side === "from" ? this.times.fromBusinessTime : this.times.toBusinessTime;
    const draft = businessTimeDraft(value);
    if (draft === null) {
      return;
    }
    this.timeEditorSide = side;
    this.timeDraft = draft;
    this.setData({
      timeEditorOpen: true,
      timeEditorSide: side,
      timeDate: draft.date,
      timeClock: draft.time,
      timeDraftError: "",
    });
  },

  onTimeDate(event: WechatMiniprogram.Input) {
    if (this.timeDraft === null || !this.canEditFields()) {
      return;
    }
    this.timeDraft = { ...this.timeDraft, date: event.detail.value };
    this.setData({ timeDate: event.detail.value });
  },

  onTimeClock(event: WechatMiniprogram.Input) {
    if (this.timeDraft === null || !this.canEditFields()) {
      return;
    }
    this.timeDraft = { ...this.timeDraft, time: event.detail.value };
    this.setData({ timeClock: event.detail.value });
  },

  onTimeNow() {
    if (!this.canEditFields()) {
      return;
    }
    this.timeDraft = applyNow(new Date());
    this.setData({ timeDate: this.timeDraft.date, timeClock: this.timeDraft.time });
  },

  onTimeToday() {
    if (this.timeDraft === null || !this.canEditFields()) {
      return;
    }
    this.timeDraft = applyToday(this.timeDraft, new Date());
    this.setData({ timeDate: this.timeDraft.date });
  },

  onTimeYesterday() {
    if (this.timeDraft === null || !this.canEditFields()) {
      return;
    }
    this.timeDraft = applyYesterday(this.timeDraft, new Date());
    this.setData({ timeDate: this.timeDraft.date });
  },

  onConfirmTime() {
    if (this.timeDraft === null) {
      return;
    }
    const next = businessTimeFromParts(this.timeDraft.date, this.timeDraft.time);
    if (next === null) {
      this.setData({ timeDraftError: "请输入有效的日期和时间" });
      return;
    }
    this.times =
      this.timeEditorSide === "from" ? changeFromBusinessTime(this.times, next) : changeToBusinessTime(this.times, next);
    this.timeDraft = null;
    this.setData({ timeEditorOpen: false, timeDraftError: "" });
    this.publish();
  },

  onCancelTime() {
    this.timeDraft = null;
    this.setData({ timeEditorOpen: false, timeDraftError: "" });
  },

  onToggleSync() {
    if (!this.canEditFields()) {
      return;
    }
    this.times = this.times.synced ? unsyncTransferTimes(this.times) : resyncTransferTimes(this.times);
    this.publish();
  },

  async onSave() {
    const context = this.context;
    const transfer = this.transfer;
    if (context === null || transfer === null || this.readOnly || this.save.phase !== "editing") {
      return;
    }
    const built = buildTransferSnapshot(
      this.fromAccountId,
      this.toAccountId,
      this.amount,
      this.times.fromBusinessTime,
      this.times.toBusinessTime,
    );
    if (!built.ok) {
      this.setData({ actionError: built.message });
      return;
    }
    this.save = { phase: "submitting", attempt: null, error: "", postSave: null };
    this.publish();
    try {
      const updated = await transferApi.updateTransfer(transfer.id, built.value);
      if (!this.loadGuard.pageAlive) {
        return;
      }
      this.transfer = updated;
      this.save = {
        phase: "success",
        attempt: null,
        error: "",
        postSave: transferPostSavePayload(context.sourceAccountId, updated),
      };
      emitTransferUpdated(this, this.save.postSave);
      emitListStale(this);
      wx.navigateBack();
    } catch (error) {
      if (!this.loadGuard.pageAlive || isUnauthorized(error)) {
        return;
      }
      const kind = classifyTransferError(error);
      if (kind === "definite") {
        this.save = editingTransfer();
        this.setData({ actionError: transferFailureMessage(error) });
      } else if (kind === "uncertain") {
        this.save = { phase: "uncertain", attempt: null, error: "", postSave: null };
      } else {
        this.save = editingTransfer();
      }
      this.publish();
    }
  },

  async onRetrySave() {
    if (this.save.phase !== "uncertain") {
      return;
    }
    this.save = editingTransfer();
    await this.onSave();
  },

  onDelete() {
    const access = transferDetailAccess(this.deletePhase);
    if (this.readOnly || !access.delete || this.context === null) {
      return;
    }
    wx.showModal({
      title: "确认删除？",
      content: TRANSFER_DELETE_CONFIRM,
      confirmText: "删除",
      cancelText: "取消",
      confirmColor: "#b42318",
      success: (result) => {
        if (confirmTransferDelete(result.confirm) !== "send") {
          return;
        }
        void this.performDelete();
      },
    });
  },

  onRetryDelete() {
    if (this.deletePhase !== "uncertain") {
      return;
    }
    void this.performDelete();
  },

  async performDelete() {
    const context = this.context;
    const transfer = this.transfer;
    if (context === null || transfer === null) {
      return;
    }
    this.deletePhase = "deleting";
    this.publish();
    try {
      await transferApi.deleteTransfer(transfer.id);
      if (!this.loadGuard.pageAlive) {
        return;
      }
      emitListStale(this);
      wx.navigateBack();
    } catch (error) {
      if (!this.loadGuard.pageAlive || isUnauthorized(error)) {
        return;
      }
      const kind = classifyTransferDelete(error);
      if (kind === "missing") {
        this.showMissing();
        return;
      }
      if (kind === "definite") {
        this.deletePhase = "idle";
        this.setData({ actionError: transferFailureMessage(error) });
      } else {
        this.deletePhase = "uncertain";
      }
      this.publish();
    }
  },

  onBack() {
    wx.navigateBack();
  },

  onHold() {
    return;
  },

  canEditFields(): boolean {
    return this.screen === "ready" && !this.readOnly && !transferFormLocked(this.save.phase, false);
  },

  publish() {
    const from = findAccount(this.accounts, this.fromAccountId);
    const to = findAccount(this.accounts, this.toAccountId);
    const fromText = from === null ? this.fromAccountLabel : accountLabel(from);
    const toText = to === null ? this.toAccountLabel : accountLabel(to);
    const snapshot = buildTransferSnapshot(
      this.fromAccountId,
      this.toAccountId,
      this.amount,
      this.times.fromBusinessTime,
      this.times.toBusinessTime,
    );
    const access = transferDetailAccess(this.deletePhase);
    const formLocked = this.readOnly || transferFormLocked(this.save.phase, false);
    this.setData({
      screen: "ready",
      readOnly: this.readOnly,
      archiveHint: transferArchiveHint(this.readOnly),
      fromAccountText: fromText,
      toAccountText: toText,
      accountRows: this.accounts.map((account) => ({
        id: account.id,
        label: `${account.name}（${account.kind === "CREDIT" ? "信用" : "普通"}）`,
      })),
      amount: this.amount,
      amountError:
        !this.readOnly && this.amount.trim().length > 0 && positiveTransferAmount(this.amount) === null
          ? "请输入大于 0 的金额"
          : "",
      fromTimeText: businessTimeDisplay(this.times.fromBusinessTime) ?? "",
      toTimeText: businessTimeDisplay(this.times.toBusinessTime) ?? "",
      syncHint: this.times.synced ? "两侧时间保持同步" : "两侧时间已分开编辑",
      syncAction: this.times.synced ? "解除同步" : "恢复同步",
      formLocked,
      saveDisabled: this.readOnly || !canSaveTransfer(this.save.phase, false, snapshot.ok),
      actionError: this.save.error,
      uncertain: this.save.phase === "uncertain",
      showDelete: !this.readOnly && access.delete,
      deleteLocked: !access.delete,
      deleteHint: this.deletePhase === "uncertain" ? "删除结果尚未确认，请重试" : "",
      deleteUncertain: this.deletePhase === "uncertain",
    });
  },
});

function accountName(account: Account | null, accountId: number): string {
  if (account === null) {
    return `账户 ${accountId}`;
  }
  return `${account.name}（${account.kind === "CREDIT" ? "信用" : "普通"}）`;
}

function accountLabel(account: Account): string {
  return `${account.name}（${account.kind === "CREDIT" ? "信用" : "普通"}）`;
}

function emitListStale(page: object): void {
  const owner = page as { getOpenerEventChannel?: () => { emit?: (name: string, data: Record<string, never>) => void } };
  const channel = owner.getOpenerEventChannel?.();
  if (channel === undefined || typeof channel.emit !== "function") {
    return;
  }
  channel.emit("listStale", {});
}

function emitTransferUpdated(page: object, result: ReturnType<typeof transferPostSavePayload> | null): void {
  const owner = page as {
    getOpenerEventChannel?: () => { emit?: (name: string, data: NonNullable<typeof result>) => void };
  };
  const channel = owner.getOpenerEventChannel?.();
  if (channel === undefined || typeof channel.emit !== "function" || result === null) {
    return;
  }
  channel.emit("transferUpdated", result);
}

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}
