import { createLoadGuard } from "../../account/load-guard";
import type { AdjustmentRecord } from "../../balance-adjustment/contract";
import {
  adjustmentDetailAccess,
  adjustmentDetailView,
  classifyAdjustmentDelete,
  confirmAdjustmentDelete,
  type AdjustmentDeletePhase,
} from "../../balance-adjustment/detail";
import { parseAdjustmentDetailContext, type AdjustmentDetailContext } from "../../balance-adjustment/navigation";
import { ApiError } from "../../http/client";
import { balanceAdjustmentApi } from "../../services/runtime";

type Screen = "invalid" | "loading" | "error" | "missing" | "detail" | "delete-uncertain";

Page({
  loadGuard: createLoadGuard(),
  context: null as AdjustmentDetailContext | null,
  adjustment: null as AdjustmentRecord | null,
  generation: 0,
  deletePhase: "idle" as AdjustmentDeletePhase,
  screen: "loading" as Screen,

  data: {
    screen: "loading" as Screen,
    amountLabel: "",
    businessTimeLabel: "",
    remarkLabel: "",
    actionError: "",
    deleteLocked: false,
    deleteHint: "",
  },

  onLoad(query: Record<string, string | undefined>) {
    const context = parseAdjustmentDetailContext(query);
    if (context === null) {
      this.screen = "invalid";
      this.setData({ screen: "invalid" });
      return;
    }
    this.context = context;
    void this.loadAdjustment();
  },

  onUnload() {
    this.loadGuard.pageAlive = false;
    wx.disableAlertBeforeUnload();
  },

  onRetry() {
    void this.loadAdjustment();
  },

  async loadAdjustment() {
    const context = this.context;
    if (context === null) {
      return;
    }
    const generation = ++this.generation;
    this.screen = "loading";
    this.setData({ screen: "loading" });
    try {
      const adjustment = await balanceAdjustmentApi.getAdjustment(context.accountId, context.adjustmentId);
      if (!this.loadGuard.pageAlive || generation !== this.generation) {
        return;
      }
      this.adjustment = adjustment;
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

  onDelete() {
    if (this.screen !== "detail" || !adjustmentDetailAccess(this.deletePhase).delete || this.adjustment === null) {
      return;
    }
    wx.showModal({
      title: "确认删除？",
      content: "删除后会撤销这条余额调整对当前余额的影响。",
      confirmText: "删除",
      cancelText: "取消",
      confirmColor: "#b42318",
      success: (result) => {
        if (confirmAdjustmentDelete(result.confirm) !== "send" || this.screen !== "detail" || !adjustmentDetailAccess(this.deletePhase).delete) {
          return;
        }
        void this.performDelete();
      },
    });
  },

  onRetryDelete() {
    if (this.screen !== "delete-uncertain" || !adjustmentDetailAccess(this.deletePhase).retry) {
      return;
    }
    void this.performDelete();
  },

  async performDelete() {
    const context = this.context;
    const access = adjustmentDetailAccess(this.deletePhase);
    if (context === null || (!access.delete && !access.retry)) {
      return;
    }
    this.deletePhase = "deleting";
    this.publishDeleteLock();
    try {
      await balanceAdjustmentApi.deleteAdjustment(context.accountId, context.adjustmentId);
      if (!this.loadGuard.pageAlive) {
        return;
      }
      this.leave();
    } catch (error) {
      if (!this.loadGuard.pageAlive) {
        return;
      }
      const outcome = classifyAdjustmentDelete(error);
      if (outcome === "absent") {
        this.leave();
        return;
      }
      if (outcome === "unauthorized") {
        return;
      }
      if (outcome === "uncertain") {
        this.deletePhase = "uncertain";
        this.screen = "delete-uncertain";
        this.syncAlert();
        this.setData({ screen: "delete-uncertain", deleteLocked: false, deleteHint: "" });
        return;
      }
      this.deletePhase = "idle";
      this.screen = "detail";
      this.syncAlert();
      this.setData({ screen: "detail", actionError: "删除失败，请稍后重试", deleteLocked: false, deleteHint: "" });
    }
  },

  onBack() {
    if (this.deletePhase === "deleting") {
      return;
    }
    wx.disableAlertBeforeUnload();
    wx.navigateBack();
  },

  publish() {
    if (this.adjustment === null) {
      return;
    }
    const view = adjustmentDetailView(this.adjustment);
    this.screen = "detail";
    this.deletePhase = "idle";
    wx.disableAlertBeforeUnload();
    this.setData({
      screen: "detail",
      amountLabel: view.amountLabel,
      businessTimeLabel: view.businessTimeLabel,
      remarkLabel: view.remarkLabel,
      actionError: "",
      deleteLocked: false,
      deleteHint: "",
    });
  },

  showMissing() {
    this.screen = "missing";
    this.adjustment = null;
    wx.disableAlertBeforeUnload();
    this.setData({ screen: "missing" });
  },

  leave() {
    this.deletePhase = "idle";
    wx.disableAlertBeforeUnload();
    wx.navigateBack();
  },

  publishDeleteLock() {
    this.syncAlert();
    this.setData({ deleteLocked: true, deleteHint: "正在删除…" });
  },

  syncAlert() {
    if (this.deletePhase === "deleting") {
      wx.enableAlertBeforeUnload({ message: "正在删除，离开后请到流水中确认是否删除成功" });
      return;
    }
    if (this.deletePhase === "uncertain") {
      wx.enableAlertBeforeUnload({ message: "删除结果尚未确认，离开后请到流水中确认" });
      return;
    }
    wx.disableAlertBeforeUnload();
  },
});

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}
