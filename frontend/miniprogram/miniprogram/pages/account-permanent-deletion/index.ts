import { beginLoad, canApplyLoadResult, createLoadGuard } from "../../account/load-guard";
import { accountArchivePath, parseAccountSettingsQuery } from "../../account/navigation";
import {
  affectedAccountRows,
  canConfirmPermanentDeletion,
  classifyPermanentDeletionExecute,
  mayEnterPermanentDeletion,
  PERMANENT_DELETION_IRREVERSIBLE_HINT,
  PERMANENT_DELETION_NO_COUNTERPART_HINT,
  permanentDeletionExecuteErrorMessage,
  type PermanentDeletionPreview,
} from "../../account/permanent-deletion";
import { ApiError } from "../../http/client";
import { isUnauthorized } from "../../services/ledger";
import { accountApi } from "../../services/runtime";

Page({
  loadGuard: createLoadGuard(),
  accountId: 0,
  previewToken: "",

  data: {
    phase: "loading" as "invalid" | "loading" | "ready" | "error" | "blocked",
    irreversibleHint: PERMANENT_DELETION_IRREVERSIBLE_HINT,
    noCounterpartHint: PERMANENT_DELETION_NO_COUNTERPART_HINT,
    transactionCount: 0,
    adjustmentCount: 0,
    transferCount: 0,
    analysisSnapshotCount: 0,
    analysisGenerationCount: 0,
    affectedRows: [] as ReturnType<typeof affectedAccountRows>,
    showNoCounterpart: false,
    confirmEnabled: false,
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
    void this.bootstrap();
  },

  onUnload() {
    this.loadGuard.pageAlive = false;
  },

  onBack() {
    if (this.data.submitting) {
      return;
    }
    wx.navigateBack();
  },

  onRetry() {
    void this.bootstrap();
  },

  async bootstrap() {
    const generation = beginLoad(this.loadGuard);
    if (generation === null) {
      return;
    }
    this.previewToken = "";
    this.setData({ phase: "loading", submitting: false, errorMessage: "", confirmEnabled: false });
    try {
      const account = await accountApi.loadAccount(this.accountId);
      if (!canApplyLoadResult(this.loadGuard, generation)) {
        return;
      }
      if (account === null) {
        this.setData({ phase: "blocked", errorMessage: "账户不存在或无权访问" });
        return;
      }
      if (!mayEnterPermanentDeletion(account.lifecycle)) {
        this.setData({ phase: "blocked", errorMessage: "只有已归档账户可以永久删除" });
        return;
      }
      await this.loadPreview(generation);
    } catch (error) {
      if (!canApplyLoadResult(this.loadGuard, generation) || isUnauthorized(error)) {
        return;
      }
      this.setData({ phase: "error", errorMessage: previewLoadErrorMessage(error) });
    }
  },

  async loadPreview(generation: number) {
    try {
      const preview = await accountApi.previewPermanentDeletion(this.accountId);
      if (!canApplyLoadResult(this.loadGuard, generation)) {
        return;
      }
      this.applyPreview(preview);
    } catch (error) {
      if (!canApplyLoadResult(this.loadGuard, generation) || isUnauthorized(error)) {
        return;
      }
      this.setData({ phase: "error", errorMessage: previewLoadErrorMessage(error) });
    }
  },

  applyPreview(preview: PermanentDeletionPreview) {
    this.previewToken = preview.previewToken;
    this.setData({
      phase: "ready",
      transactionCount: preview.transactionCount,
      adjustmentCount: preview.adjustmentCount,
      transferCount: preview.transferCount,
      analysisSnapshotCount: preview.analysisSnapshotCount,
      analysisGenerationCount: preview.analysisGenerationCount,
      affectedRows: affectedAccountRows(preview),
      showNoCounterpart: preview.affectedAccounts.length === 0,
      confirmEnabled: canConfirmPermanentDeletion(false),
      submitting: false,
      errorMessage: "",
    });
  },

  onConfirm() {
    if (!this.data.confirmEnabled || this.data.submitting || this.data.phase !== "ready") {
      return;
    }
    wx.showModal({
      title: "永久删除账户",
      content: PERMANENT_DELETION_IRREVERSIBLE_HINT,
      confirmText: "永久删除",
      confirmColor: "#b42318",
      cancelText: "取消",
      success: (result) => {
        if (!result.confirm || this.data.submitting) {
          return;
        }
        void this.execute();
      },
    });
  },

  async execute() {
    const token = this.previewToken;
    if (token.length === 0) {
      this.setData({ errorMessage: "预览已失效，请重新加载" });
      return;
    }
    this.setData({ submitting: true, confirmEnabled: false, errorMessage: "" });
    try {
      await accountApi.executePermanentDeletion(this.accountId, token);
      wx.showToast({ title: "已永久删除", icon: "success" });
      wx.reLaunch({ url: accountArchivePath() });
    } catch (error) {
      if (isUnauthorized(error)) {
        return;
      }
      const outcome = classifyPermanentDeletionExecute(error);
      if (outcome === "preview_stale") {
        this.previewToken = "";
        this.setData({
          submitting: false,
          confirmEnabled: false,
          errorMessage: permanentDeletionExecuteErrorMessage(outcome),
        });
        void this.refreshPreviewAfterStale();
        return;
      }
      this.setData({
        submitting: false,
        confirmEnabled: canConfirmPermanentDeletion(false),
        errorMessage: permanentDeletionExecuteErrorMessage(outcome),
      });
    }
  },

  async refreshPreviewAfterStale() {
    const generation = beginLoad(this.loadGuard);
    if (generation === null) {
      return;
    }
    await this.loadPreview(generation);
  },
});

function previewLoadErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 404) {
      return "账户不存在或无权访问";
    }
    if (error.code === "ACCOUNT_NOT_ARCHIVED") {
      return "只有已归档账户可以永久删除";
    }
  }
  return "删除影响预览失败，请稍后重试";
}
