import { findAccount, type Account } from "../../account/account";
import { createLoadGuard } from "../../account/load-guard";
import { ApiError } from "../../http/client";
import { accountApi, transferApi } from "../../services/runtime";
import { initialTransferAccountIds } from "../../transfer/defaults";
import { parseTransferCreateContext, type TransferCreateContext } from "../../transfer/navigation";
import { positiveTransferAmount } from "../../transfer/money";
import {
  applyTransferCreateResult,
  applyTransferFailure,
  buildTransferSnapshot,
  canSaveTransfer,
  classifyTransferError,
  editingTransfer,
  handoffCreatedTransfer,
  openTransfer,
  retryTransfer,
  transferAlertMessage,
  transferFormLocked,
  TRANSFER_UNCERTAIN_HINT,
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
  currentBeijingBusinessTime,
  type BusinessTimeDraft,
} from "../../transaction/business-time";
import { createIdempotencyKey, readRandomBytes } from "../../transaction/idempotency";

type Screen = "invalid" | "loading" | "error" | "ready";
type TimeEditorSide = "from" | "to";

Page({
  loadGuard: createLoadGuard(),
  context: null as TransferCreateContext | null,
  accounts: [] as Account[],
  fromAccountId: 0,
  toAccountId: 0,
  amount: "",
  times: initialTransferTimes(currentBeijingBusinessTime(new Date())),
  save: editingTransfer(),
  preparing: false,
  completed: false,
  timeDraft: null as BusinessTimeDraft | null,
  timeEditorSide: "from" as TimeEditorSide,
  screen: "loading" as Screen,

  data: {
    screen: "loading" as Screen,
    fromAccountText: "请选择账户",
    toAccountText: "请选择账户",
    accountRows: [] as { id: number; label: string }[],
    fromSelectorOpen: false,
    toSelectorOpen: false,
    amount: "",
    amountError: "",
    fromTimeText: "",
    toTimeText: "",
    syncHint: "两侧时间保持同步",
    syncAction: "解除同步",
    formLocked: false,
    saveDisabled: true,
    submitError: "",
    uncertain: false,
    uncertainHint: TRANSFER_UNCERTAIN_HINT,
    timeEditorOpen: false,
    timeEditorSide: "from" as TimeEditorSide,
    timeDate: "",
    timeClock: "",
    timeDraftError: "",
  },

  onLoad(query: Record<string, string | undefined>) {
    const context = parseTransferCreateContext(query);
    if (context === null) {
      this.screen = "invalid";
      this.setData({ screen: "invalid" });
      return;
    }
    this.context = context;
    this.fromAccountId = context.sourceAccountId;
    void this.loadAccounts();
  },

  onUnload() {
    this.loadGuard.pageAlive = false;
    wx.disableAlertBeforeUnload();
  },

  onRetryAccounts() {
    void this.loadAccounts();
  },

  async loadAccounts() {
    const context = this.context;
    if (context === null) {
      return;
    }
    this.screen = "loading";
    this.setData({ screen: "loading" });
    try {
      const accounts = await accountApi.listAccounts();
      if (!this.loadGuard.pageAlive) {
        return;
      }
      if (accounts.length === 0) {
        this.screen = "error";
        this.setData({ screen: "error" });
        return;
      }
      this.accounts = accounts;
      const selected = initialTransferAccountIds(accounts, context.sourceAccountId);
      this.fromAccountId = selected.fromAccountId;
      this.toAccountId = selected.toAccountId;
      this.screen = "ready";
      this.publish();
    } catch (error) {
      if (!this.loadGuard.pageAlive || isUnauthorized(error)) {
        return;
      }
      this.screen = "error";
      this.setData({ screen: "error" });
    }
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
    if (!Number.isSafeInteger(id) || id <= 0) {
      return;
    }
    this.fromAccountId = id;
    if (this.toAccountId === id) {
      this.toAccountId = 0;
    }
    this.setData({ fromSelectorOpen: false });
    this.clearSaveError();
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
    if (!Number.isSafeInteger(id) || id <= 0) {
      return;
    }
    this.toAccountId = id;
    if (this.fromAccountId === id) {
      this.fromAccountId = 0;
    }
    this.setData({ toSelectorOpen: false });
    this.clearSaveError();
    this.publish();
  },

  onAmount(event: WechatMiniprogram.Input) {
    if (!this.canEditFields()) {
      return;
    }
    this.amount = event.detail.value;
    this.clearSaveError();
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
    if (context === null || this.preparing || this.save.phase !== "editing") {
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
      this.setData({ submitError: built.message, amountError: built.message.includes("金额") ? built.message : "" });
      return;
    }
    if (!canSaveTransfer(this.save.phase, this.preparing, true)) {
      return;
    }
    this.preparing = true;
    this.publish();
    const opened = await openTransfer(this.save, built.value, () => createIdempotencyKey((length) => readRandomBytes(length, wx)));
    if (!this.loadGuard.pageAlive) {
      return;
    }
    if (opened.error.length > 0 || opened.state.phase !== "submitting") {
      this.preparing = false;
      this.save = opened.state;
      this.publish();
      if (opened.error.length > 0) {
        this.setData({ submitError: opened.error });
      }
      return;
    }
    this.save = opened.state;
    this.preparing = false;
    this.publish();
    await this.sendAttempt();
  },

  async onRetrySave() {
    const next = retryTransfer(this.save);
    if (next === null) {
      return;
    }
    this.save = next;
    this.publish();
    await this.sendAttempt();
  },

  async sendAttempt() {
    const attempt = this.save.attempt;
    const context = this.context;
    if (attempt === null || context === null || this.save.phase !== "submitting") {
      return;
    }
    try {
      const transfer = await transferApi.createTransfer(attempt.snapshot, attempt.idempotencyKey);
      if (!this.loadGuard.pageAlive || this.save.attempt !== attempt) {
        return;
      }
      this.save = applyTransferCreateResult(this.save, transfer, context.sourceAccountId);
    } catch (error) {
      if (!this.loadGuard.pageAlive || this.save.attempt !== attempt) {
        return;
      }
      const kind = classifyTransferError(error);
      if (kind === "unauthorized") {
        return;
      }
      this.save = applyTransferFailure(this.save, kind, error);
    }
    this.publish();
    this.returnAfterSave();
  },

  returnAfterSave() {
    const result = handoffCreatedTransfer(this.save.phase, this.save.postSave, this.completed).result;
    if (result === null) {
      return;
    }
    this.completed = true;
    emitTransferCreated(this, result);
    wx.disableAlertBeforeUnload();
    wx.navigateBack();
  },

  onCancel() {
    if (this.preparing || this.save.phase === "submitting") {
      wx.showModal({ title: "请稍候", content: "正在保存，请稍候", showCancel: false, confirmText: "知道了" });
      return;
    }
    if (this.save.phase === "uncertain") {
      wx.showModal({
        title: "保存结果尚未确认",
        content: "离开后请到流水中确认本次转账是否已保存。",
        confirmText: "离开",
        cancelText: "继续确认",
        success: (result) => {
          if (result.confirm) {
            wx.disableAlertBeforeUnload();
            wx.navigateBack();
          }
        },
      });
      return;
    }
    if (this.save.phase === "conflict" || this.save.phase === "success") {
      wx.disableAlertBeforeUnload();
      wx.navigateBack();
      return;
    }
    wx.showModal({
      title: "放弃本次转账？",
      confirmText: "放弃",
      cancelText: "继续编辑",
      success: (result) => {
        if (result.confirm) {
          wx.disableAlertBeforeUnload();
          wx.navigateBack();
        }
      },
    });
  },

  onBack() {
    wx.disableAlertBeforeUnload();
    wx.navigateBack();
  },

  onHold() {
    return;
  },

  canEditFields(): boolean {
    return this.screen === "ready" && !transferFormLocked(this.save.phase, this.preparing);
  },

  clearSaveError() {
    if (this.save.phase === "editing") {
      this.save = { ...this.save, error: "" };
    }
  },

  publish() {
    const from = findAccount(this.accounts, this.fromAccountId);
    const to = findAccount(this.accounts, this.toAccountId);
    const snapshot = buildTransferSnapshot(
      this.fromAccountId,
      this.toAccountId,
      this.amount,
      this.times.fromBusinessTime,
      this.times.toBusinessTime,
    );
    const amountError =
      this.amount.trim().length > 0 && positiveTransferAmount(this.amount) === null ? "请输入大于 0 的金额" : "";
    this.setData({
      screen: "ready",
      fromAccountText: accountLabel(from, "请选择转出账户"),
      toAccountText: accountLabel(to, "请选择转入账户"),
      accountRows: this.accounts.map((account) => ({
        id: account.id,
        label: `${account.name}（${account.kind === "CREDIT" ? "信用" : "普通"}）`,
      })),
      amount: this.amount,
      amountError,
      fromTimeText: businessTimeDisplay(this.times.fromBusinessTime) ?? "",
      toTimeText: businessTimeDisplay(this.times.toBusinessTime) ?? "",
      syncHint: this.times.synced ? "两侧时间保持同步" : "两侧时间已分开编辑",
      syncAction: this.times.synced ? "解除同步" : "恢复同步",
      formLocked: transferFormLocked(this.save.phase, this.preparing),
      saveDisabled: !canSaveTransfer(this.save.phase, this.preparing, snapshot.ok),
      submitError: this.save.error,
      uncertain: this.save.phase === "uncertain",
    });
    this.syncAlert();
  },

  syncAlert() {
    const message = transferAlertMessage(this.save.phase, this.preparing);
    if (message === null) {
      wx.disableAlertBeforeUnload();
      return;
    }
    wx.enableAlertBeforeUnload({ message });
  },
});

function accountLabel(account: Account | null, fallback: string): string {
  if (account === null) {
    return fallback;
  }
  return `${account.name}（${account.kind === "CREDIT" ? "信用" : "普通"}）`;
}

function emitTransferCreated(page: object, result: ReturnType<typeof handoffCreatedTransfer>["result"]): void {
  const owner = page as { getOpenerEventChannel?: () => { emit?: (name: string, data: NonNullable<typeof result>) => void } };
  const channel = owner.getOpenerEventChannel?.();
  if (channel === undefined || typeof channel.emit !== "function" || result === null) {
    return;
  }
  channel.emit("transferCreated", result);
}

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}
