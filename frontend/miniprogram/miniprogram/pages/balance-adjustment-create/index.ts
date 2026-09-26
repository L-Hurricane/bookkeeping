import { createLoadGuard } from "../../account/load-guard";
import { parseAdjustmentCreateContext, type AdjustmentCreateContext } from "../../balance-adjustment/navigation";
import { FUTURE_ADJUSTMENT_HINT, previewAdjustment, ZERO_ADJUSTMENT_MESSAGE } from "../../balance-adjustment/preview";
import {
  ADJUSTMENT_PREPARE_MESSAGE,
  ADJUSTMENT_UNCERTAIN_MESSAGE,
  adjustmentFormLocked,
  applyAdjustmentFailure,
  applyAdjustmentResult,
  buildAdjustmentSnapshot,
  canSaveAdjustment,
  classifyAdjustmentError,
  editingAdjustment,
  handoffCreatedAdjustment,
  openAdjustment,
  retryAdjustment,
  type AdjustmentSaveState,
} from "../../balance-adjustment/submission";
import { ApiError } from "../../http/client";
import { formatMoneyLabel } from "../../ledger/ledger";
import type { LedgerLocateIntent } from "../../ledger/post-save";
import { accountApi, balanceAdjustmentApi } from "../../services/runtime";
import {
  applyNow,
  applyToday,
  applyYesterday,
  businessTimeDisplay,
  businessTimeDraft,
  currentBeijingBusinessTime,
  isBusinessTimeAllowed,
  isFutureBusinessTime,
  resolveBusinessTimeEdit,
  type BusinessTimeDraft,
} from "../../transaction/business-time";
import { createIdempotencyKey, readRandomBytes } from "../../transaction/idempotency";

type Screen = "invalid" | "loading" | "error" | "ready";

Page({
  loadGuard: createLoadGuard(),
  context: null as AdjustmentCreateContext | null,
  balanceGeneration: 0,
  save: editingAdjustment("0.00"),
  preparing: false,
  completed: false,
  target: "",
  remark: "",
  businessTime: "",
  timeDraft: null as BusinessTimeDraft | null,
  screen: "loading" as Screen,

  data: {
    screen: "loading" as Screen,
    currentBalanceLabel: "",
    target: "",
    targetError: "",
    previewCaption: "预计增加 / 减少",
    previewAmount: "—",
    zeroMessage: "",
    afterLabel: "—",
    businessTimeText: "",
    timeError: "",
    futureHint: "",
    remark: "",
    formLocked: false,
    saveDisabled: true,
    submitError: "",
    uncertain: false,
    timeEditorOpen: false,
    timeDate: "",
    timeClock: "",
    timeDraftError: "",
  },

  onLoad(query: Record<string, string | undefined>) {
    const context = parseAdjustmentCreateContext(query);
    if (context === null) {
      this.screen = "invalid";
      this.setData({ screen: "invalid" });
      return;
    }
    this.context = context;
    this.businessTime = currentBeijingBusinessTime(new Date());
    void this.loadBalance();
  },

  onUnload() {
    this.loadGuard.pageAlive = false;
    wx.disableAlertBeforeUnload();
  },

  onRetryBalance() {
    void this.loadBalance();
  },

  async loadBalance() {
    const context = this.context;
    if (context === null) {
      return;
    }
    const generation = ++this.balanceGeneration;
    this.screen = "loading";
    this.setData({ screen: "loading" });
    try {
      const accounts = await accountApi.listAccounts();
      if (!this.loadGuard.pageAlive || generation !== this.balanceGeneration) {
        return;
      }
      const account = accounts.length === 1 ? accounts[0] : undefined;
      if (account === undefined || account.id !== context.accountId) {
        this.showBalanceError();
        return;
      }
      this.save = editingAdjustment(account.currentBalance);
      this.screen = "ready";
      this.publish();
    } catch (error) {
      if (!this.loadGuard.pageAlive || generation !== this.balanceGeneration || isUnauthorized(error)) {
        return;
      }
      this.showBalanceError();
    }
  },

  showBalanceError() {
    this.screen = "error";
    this.setData({ screen: "error" });
  },

  onTarget(event: WechatMiniprogram.Input) {
    if (!this.canEditFields()) {
      return;
    }
    this.target = event.detail.value;
    if (this.save.phase === "editing") {
      this.save = { ...this.save, error: "" };
    }
    this.publish();
  },

  onRemark(event: WechatMiniprogram.Input) {
    if (!this.canEditFields()) {
      return;
    }
    this.remark = event.detail.value;
    if (this.save.phase === "editing") {
      this.save = { ...this.save, error: "" };
    }
    this.setData({ remark: event.detail.value, submitError: "" });
  },

  onOpenTime() {
    if (!this.canEditFields()) {
      return;
    }
    const draft = businessTimeDraft(this.businessTime);
    if (draft === null) {
      return;
    }
    this.timeDraft = draft;
    this.setData({ timeEditorOpen: true, timeDate: draft.date, timeClock: draft.time, timeDraftError: "" });
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
    if (this.context === null || this.timeDraft === null) {
      return;
    }
    const resolved = resolveBusinessTimeEdit("confirm", this.businessTime, this.timeDraft, this.context.accountCreatedTime);
    this.businessTime = resolved.businessTime;
    this.timeDraft = resolved.open ? this.timeDraft : null;
    this.setData({ timeEditorOpen: resolved.open, timeDraftError: adjustmentTimeError(resolved.error) });
    this.publish();
  },

  onCancelTime() {
    if (this.context === null) {
      return;
    }
    const resolved = resolveBusinessTimeEdit("cancel", this.businessTime, this.timeDraft ?? { date: "", time: "" }, this.context.accountCreatedTime);
    this.businessTime = resolved.businessTime;
    this.timeDraft = null;
    this.setData({ timeEditorOpen: false, timeDraftError: "" });
  },

  async onSave() {
    if (this.context === null || this.preparing || this.save.phase !== "editing") {
      return;
    }
    const built = buildAdjustmentSnapshot(this.target, this.businessTime, this.remark, this.context.accountId, this.context.accountCreatedTime);
    if (!built.ok) {
      this.setData({ submitError: built.message });
      return;
    }
    const preview = previewAdjustment(this.save.currentBalance, built.value.targetBalance);
    if (!preview.ok || preview.preview.zero || !canSaveAdjustment(this.save.phase, this.preparing, preview.preview.zero, true)) {
      if (preview.ok && preview.preview.zero) {
        this.save = { ...this.save, error: ZERO_ADJUSTMENT_MESSAGE };
        this.publish();
      }
      return;
    }
    this.preparing = true;
    this.publish();
    const opened = await openAdjustment(this.save, built.value, () => createIdempotencyKey((length) => readRandomBytes(length, wx)));
    if (!this.loadGuard.pageAlive) {
      return;
    }
    if (opened.error.length > 0 || opened.state.phase !== "submitting") {
      this.preparing = false;
      this.publish();
      this.setData({ submitError: ADJUSTMENT_PREPARE_MESSAGE });
      return;
    }
    this.save = opened.state;
    this.preparing = false;
    this.publish();
    await this.sendAttempt();
  },

  async onRetrySave() {
    const next = retryAdjustment(this.save);
    if (next === null) {
      return;
    }
    this.save = next;
    this.publish();
    await this.sendAttempt();
  },

  async sendAttempt() {
    const attempt = this.save.attempt;
    if (attempt === null || this.save.phase !== "submitting") {
      return;
    }
    try {
      const result = await balanceAdjustmentApi.createAdjustment(attempt.snapshot, attempt.idempotencyKey);
      if (!this.loadGuard.pageAlive || this.save.attempt !== attempt) {
        return;
      }
      this.save = applyAdjustmentResult(this.save, result);
      if (this.save.phase === "editing" && this.save.confirmedTarget !== null) {
        this.target = this.save.confirmedTarget;
      }
    } catch (error) {
      if (!this.loadGuard.pageAlive || this.save.attempt !== attempt) {
        return;
      }
      const kind = classifyAdjustmentError(error);
      if (kind === "unauthorized") {
        return;
      }
      this.save = applyAdjustmentFailure(this.save, kind, error);
    }
    this.publish();
    this.returnAfterSave();
  },

  returnAfterSave() {
    const result = handoffCreatedAdjustment(this.save.phase, this.save.postSave, this.completed).result;
    if (result === null || result.kind !== "adjustment") {
      return;
    }
    this.completed = true;
    emitAdjustmentCreated(this, result);
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
        title: ADJUSTMENT_UNCERTAIN_MESSAGE,
        content: "离开后请到流水中确认本次余额调整是否已保存。",
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
      title: "放弃余额调整？",
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
    return this.screen === "ready" && !adjustmentFormLocked(this.save.phase, this.preparing);
  },

  currentPreview() {
    if (this.screen !== "ready") {
      return null;
    }
    const preview = previewAdjustment(this.save.currentBalance, this.target);
    return preview.ok ? preview.preview : null;
  },

  publish() {
    const context = this.context;
    if (context === null) {
      return;
    }
    const preview = previewAdjustment(this.save.currentBalance, this.target);
    const ready = preview.ok ? preview.preview : null;
    const zero = ready?.zero === true;
    this.screen = "ready";
    this.setData({
      screen: "ready",
      currentBalanceLabel: formatMoneyLabel(this.save.currentBalance),
      target: this.target,
      targetError: preview.ok || this.target.trim().length === 0 ? "" : "请输入最多两位小数的金额",
      previewCaption: ready?.caption ?? "预计增加 / 减少",
      previewAmount: ready?.amountLabel ?? "—",
      zeroMessage: zero ? ZERO_ADJUSTMENT_MESSAGE : "",
      afterLabel: ready?.afterLabel ?? "—",
      businessTimeText: businessTimeDisplay(this.businessTime) ?? "",
      timeError: isBusinessTimeAllowed(this.businessTime, context.accountCreatedTime) ? "" : "调整时间不得早于账户创建时间",
      futureHint: isFutureBusinessTime(this.businessTime, new Date()) ? FUTURE_ADJUSTMENT_HINT : "",
      remark: this.remark,
      formLocked: adjustmentFormLocked(this.save.phase, this.preparing),
      saveDisabled: !canSaveAdjustment(this.save.phase, this.preparing, zero, ready !== null),
      submitError: this.save.error,
      uncertain: this.save.phase === "uncertain",
    });
    this.syncAlert();
  },

  syncAlert() {
    const message = adjustmentAlert(this.save.phase, this.preparing);
    if (message === null) {
      wx.disableAlertBeforeUnload();
      return;
    }
    wx.enableAlertBeforeUnload({ message });
  },
});

function adjustmentAlert(phase: AdjustmentSaveState["phase"], preparing: boolean): string | null {
  if (preparing || phase === "submitting") {
    return "正在保存，离开后请到流水中确认是否保存成功";
  }
  if (phase === "editing") {
    return "放弃余额调整？";
  }
  if (phase === "uncertain") {
    return "保存结果尚未确认。离开后请到流水中确认本次余额调整是否已保存。";
  }
  return null;
}

function adjustmentTimeError(error: string): string {
  return error === "交易时间不得早于账户创建时间" ? "调整时间不得早于账户创建时间" : error;
}

function emitAdjustmentCreated(page: object, result: LedgerLocateIntent): void {
  const owner = page as { getOpenerEventChannel?: () => { emit?: (name: string, data: typeof result) => void } };
  const channel = owner.getOpenerEventChannel?.();
  if (channel === undefined || typeof channel.emit !== "function") {
    return;
  }
  channel.emit("balanceAdjustmentCreated", result);
}

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}
