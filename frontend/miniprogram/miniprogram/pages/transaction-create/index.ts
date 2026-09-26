import { createLoadGuard } from "../../account/load-guard";
import type { CategoryNode } from "../../category/category";
import { ApiError } from "../../http/client";
import { categoryApi, tagApi, transactionApi } from "../../services/runtime";
import type { Tag } from "../../tag/tag";
import {
  applyNow,
  applyToday,
  applyYesterday,
  businessTimeDisplay,
  businessTimeDraft,
  isBusinessTimeAllowed,
  isFutureBusinessTime,
  resolveBusinessTimeEdit,
  type BusinessTimeDraft,
} from "../../transaction/business-time";
import { createTransactionForm, validateTransactionAmount, type PostSaveResult } from "../../transaction/form";
import { handoffPostSave } from "../../ledger/post-save";
import { createIdempotencyKey, readRandomBytes } from "../../transaction/idempotency";
import { parseCreatePageContext } from "../../transaction/navigation";
import {
  amountFeedback,
  beginBucket,
  categoryLabel,
  categoryRows,
  createTagFailureMessage,
  editComposer,
  emptyBucket,
  emptyComposer,
  finishBucket,
  mergeTagLists,
  openComposer,
  prepareCreateTag,
  rememberCreatedTag,
  replaceItems,
  retireComposer,
  resolveCancel,
  shouldLoadReference,
  settleCreateTagComposer,
  showsReference,
  switchCreateType,
  toggleTagSelection,
  visibleCreateTagError,
  type CategoryRow,
} from "../../transaction/reference";
import {
  alertBeforeUnloadMessage,
  applySubmitOutcome,
  canSave,
  classifySubmitError,
  editingSubmission,
  interactionLocked,
  openSubmission,
  prepareSave,
  PREPARE_MESSAGE,
  retryAttempt,
  UNCERTAIN_HINT,
  UNCERTAIN_MESSAGE,
  WAIT_TAG_MESSAGE,
  type SubmissionState,
} from "../../transaction/submission";
import type { TransactionType } from "../../transaction/transaction";

Page({
  loadGuard: createLoadGuard(),
  form: createTransactionForm(new Date()),
  accountId: 0,
  accountCreatedTime: "",
  submission: editingSubmission(),
  preparingSave: false,
  postSaveCompleted: false,
  categories: { EXPENSE: emptyBucket<CategoryNode>(), INCOME: emptyBucket<CategoryNode>() },
  tags: { EXPENSE: emptyBucket<Tag>(), INCOME: emptyBucket<Tag>() },
  composers: { EXPENSE: emptyComposer(), INCOME: emptyComposer() },
  tagRequest: null as { type: TransactionType; generation: number } | null,
  tagCreateInFlight: false,
  createdTagIds: [] as number[],
  timeDraft: null as BusinessTimeDraft | null,

  data: {
    contextInvalid: false,
    type: "EXPENSE" as TransactionType,
    amount: "",
    name: "",
    remark: "",
    categoryText: "未分类",
    amountError: "",
    timeError: "",
    futureHint: "",
    businessTimeText: "",
    timeEditorOpen: false,
    timeDate: "",
    timeClock: "",
    timeDraftError: "",
    categoryPhase: "idle" as "idle" | "loading" | "ready" | "error",
    categoryRows: [] as CategoryRow[],
    selectorOpen: false,
    tagPhase: "idle" as "idle" | "loading" | "ready" | "error",
    tagChips: [] as { id: number; name: string; selected: boolean }[],
    tagComposer: false,
    tagDraft: "",
    tagError: "",
    tagInputLocked: false,
    tagCreateInFlight: false,
    formLocked: false,
    saveDisabled: false,
    submitError: "",
    submissionPhase: "editing" as SubmissionState["phase"],
    uncertain: false,
    uncertainHint: UNCERTAIN_HINT,
  },

  onLoad(query: Record<string, string | undefined>) {
    const context = parseCreatePageContext(query);
    if (context === null) {
      this.setData({ contextInvalid: true });
      return;
    }
    this.accountId = context.accountId;
    this.accountCreatedTime = context.accountCreatedTime;
    this.form = createTransactionForm(new Date());
    this.publishForm();
    wx.enableAlertBeforeUnload({ message: "放弃本次记账？" });
    void this.loadCategories(this.form.type);
    void this.loadTags(this.form.type);
  },

  onUnload() {
    this.loadGuard.pageAlive = false;
    wx.disableAlertBeforeUnload();
  },

  onType(event: WechatMiniprogram.BaseEvent) {
    if (!this.canEdit()) {
      return;
    }
    const next = transactionType(event.currentTarget.dataset["type"]);
    if (next === null) {
      return;
    }
    const previous = this.form.type;
    const switched = switchCreateType(this.form, next);
    this.form = switched.form;
    this.forgetSubmitError();
    if (switched.clearTagDraft) {
      this.composers[previous] = retireComposer(this.composers[previous]);
      this.setData({ selectorOpen: false });
    }
    this.publishForm();
    if (shouldLoadReference(this.categories[next].phase)) {
      void this.loadCategories(next);
    }
    if (shouldLoadReference(this.tags[next].phase)) {
      void this.loadTags(next);
    }
  },

  onAmountInput(event: WechatMiniprogram.Input) {
    if (!this.canEdit()) {
      return;
    }
    this.form = { ...this.form, amount: event.detail.value };
    this.setData({ amount: event.detail.value, submitError: "" });
  },

  onAmountBlur() {
    if (!this.canEdit()) {
      return;
    }
    const validation = validateTransactionAmount(this.form.amount);
    this.setData({ amountError: amountFeedback(this.form.amount, validation) ?? "" });
  },

  onNameInput(event: WechatMiniprogram.Input) {
    if (!this.canEdit()) {
      return;
    }
    this.form = { ...this.form, name: event.detail.value };
    this.setData({ name: event.detail.value, submitError: "" });
  },

  onRemarkInput(event: WechatMiniprogram.Input) {
    if (!this.canEdit()) {
      return;
    }
    this.form = { ...this.form, remark: event.detail.value };
    this.setData({ remark: event.detail.value, submitError: "" });
  },

  onOpenCategories() {
    if (!this.canEdit() || this.categories[this.form.type].phase !== "ready") {
      return;
    }
    this.setData({ selectorOpen: true });
  },

  onCloseCategories() {
    this.setData({ selectorOpen: false });
  },

  onChooseCategory(event: WechatMiniprogram.BaseEvent) {
    if (!this.canEdit()) {
      return;
    }
    const raw = event.currentTarget.dataset["id"];
    const categoryId = raw === "" || raw === undefined ? null : Number(raw);
    this.form = { ...this.form, categoryId: categoryId !== null && Number.isSafeInteger(categoryId) ? categoryId : null };
    this.forgetSubmitError();
    this.setData({ selectorOpen: false });
    this.publishForm();
  },

  onToggleTag(event: WechatMiniprogram.BaseEvent) {
    if (!this.canEdit()) {
      return;
    }
    const tagId = Number(event.currentTarget.dataset["id"]);
    this.form = {
      ...this.form,
      selectedTagIds: toggleTagSelection(this.form.selectedTagIds, tagId, this.tags[this.form.type].items),
    };
    this.forgetSubmitError();
    this.publishForm();
  },

  onOpenTagComposer() {
    if (!this.canEdit()) {
      return;
    }
    const type = this.form.type;
    this.composers[type] = openComposer(this.composers[type]);
    this.publishForm();
  },

  onTagDraft(event: WechatMiniprogram.Input) {
    if (!this.canEdit()) {
      return;
    }
    const type = this.form.type;
    this.composers[type] = editComposer(this.composers[type], event.detail.value);
    this.setData({ tagDraft: event.detail.value });
  },

  async onCreateTag() {
    if (!this.canEdit() || this.tagCreateInFlight) {
      return;
    }
    const submittedType = this.form.type;
    const composer = this.composers[submittedType];
    const requestGeneration = composer.generation;
    const plan = prepareCreateTag(composer.draft, this.tags[submittedType].items);
    if (plan.status === "empty" || plan.status === "duplicate") {
      const message = plan.status === "empty" ? "标签名称不能为空" : "标签已存在，可直接选择";
      this.composers[submittedType] = { ...composer, error: message };
      this.publishForm();
      return;
    }
    this.tagRequest = { type: submittedType, generation: requestGeneration };
    this.tagCreateInFlight = true;
    this.publishForm();
    try {
      const created = await tagApi.createTag(submittedType, plan.name);
      if (!this.loadGuard.pageAlive) {
        return;
      }
      this.tags[submittedType] = rememberCreatedTag(this.tags[submittedType], created);
      this.createdTagIds = this.createdTagIds.includes(created.id) ? this.createdTagIds : [...this.createdTagIds, created.id];
      if (this.form.type === submittedType) {
        this.form = {
          ...this.form,
          selectedTagIds: applyOwnedSelection(this.form.selectedTagIds, this.form.type, created),
        };
        this.forgetSubmitError();
      }
      this.composers[submittedType] = settleCreateTagComposer(this.composers[submittedType], requestGeneration, { status: "success" });
      this.publishForm();
    } catch (error) {
      if (!this.loadGuard.pageAlive || isUnauthorized(error)) {
        return;
      }
      const message =
        error instanceof ApiError ? createTagFailureMessage(error.status, error.code) : createTagFailureMessage(0, undefined);
      this.composers[submittedType] = settleCreateTagComposer(this.composers[submittedType], requestGeneration, {
        status: "failure",
        message,
      });
      if (visibleCreateTagError(this.form.type, submittedType, message) !== null) {
        this.publishForm();
      }
    } finally {
      this.tagRequest = null;
      this.tagCreateInFlight = false;
      if (this.loadGuard.pageAlive) {
        this.publishForm();
      }
    }
  },

  onOpenTime() {
    if (!this.canEdit()) {
      return;
    }
    const draft = businessTimeDraft(this.form.businessTime);
    if (draft === null) {
      return;
    }
    this.timeDraft = draft;
    this.setData({
      selectorOpen: false,
      timeEditorOpen: true,
      timeDate: draft.date,
      timeClock: draft.time,
      timeDraftError: "",
    });
  },

  onTimeDate(event: WechatMiniprogram.Input) {
    if (!this.canEdit()) {
      return;
    }
    this.timeDraft = { date: event.detail.value, time: this.timeDraft?.time ?? "" };
    this.setData({ timeDate: event.detail.value });
  },

  onTimeClock(event: WechatMiniprogram.Input) {
    if (!this.canEdit()) {
      return;
    }
    this.timeDraft = { date: this.timeDraft?.date ?? "", time: event.detail.value };
    this.setData({ timeClock: event.detail.value });
  },

  onTimeNow() {
    if (!this.canEdit()) {
      return;
    }
    this.timeDraft = applyNow(new Date());
    this.setData({ timeDate: this.timeDraft.date, timeClock: this.timeDraft.time });
  },

  onTimeToday() {
    if (!this.canEdit()) {
      return;
    }
    const draft = this.timeDraft ?? { date: "", time: "" };
    this.timeDraft = applyToday(draft, new Date());
    this.setData({ timeDate: this.timeDraft.date });
  },

  onTimeYesterday() {
    if (!this.canEdit()) {
      return;
    }
    const draft = this.timeDraft ?? { date: "", time: "" };
    this.timeDraft = applyYesterday(draft, new Date());
    this.setData({ timeDate: this.timeDraft.date });
  },

  onConfirmTime() {
    if (!this.canEdit()) {
      return;
    }
    const draft = this.timeDraft ?? { date: this.data.timeDate, time: this.data.timeClock };
    const resolved = resolveBusinessTimeEdit("confirm", this.form.businessTime, draft, this.accountCreatedTime);
    this.form = { ...this.form, businessTime: resolved.businessTime };
    this.forgetSubmitError();
    this.timeDraft = resolved.open ? draft : null;
    this.setData({ timeEditorOpen: resolved.open, timeDraftError: resolved.error });
    if (!resolved.open) {
      this.publishForm();
    }
  },

  onCancelTime() {
    const resolved = resolveBusinessTimeEdit(
      "cancel",
      this.form.businessTime,
      this.timeDraft ?? { date: "", time: "" },
      this.accountCreatedTime,
    );
    this.form = { ...this.form, businessTime: resolved.businessTime };
    this.timeDraft = null;
    this.setData({ timeEditorOpen: false, timeDraftError: "" });
  },

  onRetryCategories() {
    void this.loadCategories(this.form.type);
  },

  onRetryTags() {
    void this.loadTags(this.form.type);
  },

  async onSave() {
    const intent = prepareSave(this.submission.phase, this.tagCreateInFlight, this.form, this.accountId, this.accountCreatedTime);
    if (intent.status === "blocked" || this.preparingSave) {
      return;
    }
    if (intent.status === "wait-tag") {
      this.setData({ submitError: WAIT_TAG_MESSAGE });
      return;
    }
    if (intent.status === "invalid") {
      this.setData({ submitError: intent.message });
      return;
    }
    this.preparingSave = true;
    this.publishLock();
    const opened = await openSubmission(this.submission, intent.snapshot, () =>
      createIdempotencyKey((length) => readRandomBytes(length, wx)),
    );
    if (!this.loadGuard.pageAlive) {
      return;
    }
    if (opened.error.length > 0 || opened.state.phase !== "submitting") {
      this.preparingSave = false;
      this.publishLock();
      this.setData({ submitError: PREPARE_MESSAGE });
      return;
    }
    this.submission = opened.state;
    this.preparingSave = false;
    this.publishSubmission();
    await this.sendAttempt();
  },

  async onRetryConfirm() {
    const next = retryAttempt(this.submission);
    if (next === null) {
      return;
    }
    this.submission = next;
    this.publishSubmission();
    await this.sendAttempt();
  },

  onCancel() {
    if (this.preparingSave) {
      wx.showModal({ title: "请稍候", content: "正在准备保存，请稍候", showCancel: false, confirmText: "知道了" });
      return;
    }
    const phase = this.submission.phase;
    if (phase === "submitting") {
      wx.showModal({ title: "正在保存", content: "正在保存，请稍候", showCancel: false, confirmText: "知道了" });
      return;
    }
    if (phase === "uncertain") {
      wx.showModal({
        title: UNCERTAIN_MESSAGE,
        content: "离开后请到流水中确认本次记录是否已保存。",
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
    if (phase === "success") {
      if (!this.postSaveCompleted) {
        this.returnAfterSave();
        return;
      }
      wx.disableAlertBeforeUnload();
      wx.navigateBack();
      return;
    }
    if (phase === "conflict") {
      wx.disableAlertBeforeUnload();
      wx.navigateBack();
      return;
    }
    wx.showModal({
      title: "放弃本次记账？",
      confirmText: "放弃",
      cancelText: "继续编辑",
      success: (result) => {
        const decision = resolveCancel(result.confirm ? "abandon" : "continue", this.form, this.createdTagIds);
        if (decision.action === "leave") {
          wx.disableAlertBeforeUnload();
          wx.navigateBack();
        }
      },
    });
  },

  onBackHome() {
    wx.navigateBack();
  },

  onHold() {
    return;
  },

  async loadCategories(type: TransactionType) {
    const started = beginBucket(this.categories[type]);
    this.categories[type] = started.bucket;
    if (showsReference(this.form.type, type)) {
      this.setData({ categoryPhase: "loading" });
    }
    try {
      const items = await categoryApi.listCategories(type);
      this.finishCategories(type, started.generation, items);
    } catch (error) {
      if (isUnauthorized(error)) {
        return;
      }
      this.finishCategories(type, started.generation, null);
    }
  },

  finishCategories(type: TransactionType, generation: number, items: CategoryNode[] | null) {
    const next = finishBucket(this.categories[type], generation, this.loadGuard.pageAlive, items, replaceItems);
    if (next === null) {
      return;
    }
    this.categories[type] = next;
    if (showsReference(this.form.type, type)) {
      this.publishForm();
    }
  },

  async loadTags(type: TransactionType) {
    const started = beginBucket(this.tags[type]);
    this.tags[type] = started.bucket;
    if (showsReference(this.form.type, type)) {
      this.setData({ tagPhase: "loading" });
    }
    try {
      const items = await tagApi.listTags(type);
      this.finishTags(type, started.generation, items);
    } catch (error) {
      if (isUnauthorized(error)) {
        return;
      }
      this.finishTags(type, started.generation, null);
    }
  },

  finishTags(type: TransactionType, generation: number, items: Tag[] | null) {
    const next = finishBucket(this.tags[type], generation, this.loadGuard.pageAlive, items, mergeTagLists);
    if (next === null) {
      return;
    }
    this.tags[type] = next;
    if (showsReference(this.form.type, type)) {
      this.publishForm();
    }
  },

  async sendAttempt() {
    const attempt = this.submission.attempt;
    if (attempt === null || this.submission.phase !== "submitting") {
      return;
    }
    try {
      const transaction = await transactionApi.createTransaction(attempt.snapshot, attempt.idempotencyKey);
      if (!this.loadGuard.pageAlive || this.submission.attempt !== attempt) {
        return;
      }
      this.submission = applySubmitOutcome(this.submission, { kind: "success", transaction });
    } catch (error) {
      if (!this.loadGuard.pageAlive || this.submission.attempt !== attempt) {
        return;
      }
      this.submission = applySubmitOutcome(this.submission, classifySubmitError(error));
    }
    this.publishSubmission();
    this.returnAfterSave();
  },

  publishLock() {
    this.setData({
      formLocked: interactionLocked(this.submission.phase, this.preparingSave),
      saveDisabled: !canSave(this.submission.phase, this.tagCreateInFlight, this.preparingSave),
    });
  },

  forgetSubmitError() {
    if (this.data.submitError !== "") {
      this.setData({ submitError: "" });
    }
  },

  publishSubmission() {
    this.setData({
      formLocked: interactionLocked(this.submission.phase, this.preparingSave),
      saveDisabled: !canSave(this.submission.phase, this.tagCreateInFlight, this.preparingSave),
      submitError: this.submission.error,
      submissionPhase: this.submission.phase,
      uncertain: this.submission.phase === "uncertain",
    });
    this.syncLeaveAlert();
  },

  syncLeaveAlert() {
    const message = alertBeforeUnloadMessage(this.submission.phase);
    if (message === null) {
      wx.disableAlertBeforeUnload();
      return;
    }
    wx.enableAlertBeforeUnload({ message });
  },

  returnAfterSave() {
    const decision = handoffPostSave(this.submission.phase, this.submission.postSave, this.postSaveCompleted);
    if (decision.result === null) {
      return;
    }
    this.postSaveCompleted = true;
    emitTransactionCreated(this, decision.result);
    wx.disableAlertBeforeUnload();
    wx.navigateBack();
  },

  canEdit(): boolean {
    return !interactionLocked(this.submission.phase, this.preparingSave);
  },

  publishForm() {
    const type = this.form.type;
    const categories = this.categories[type];
    const tags = this.tags[type];
    this.setData({
      type,
      amount: this.form.amount,
      name: this.form.name,
      remark: this.form.remark,
      categoryText: categoryLabel(categories.items, this.form.categoryId),
      categoryPhase: categories.phase,
      categoryRows: categoryRows(categories.items),
      tagPhase: tags.phase,
      tagChips: tags.items.map((tag) => ({
        id: tag.id,
        name: tag.name,
        selected: this.form.selectedTagIds.includes(tag.id),
      })),
      tagComposer: this.composers[type].open,
      tagDraft: this.composers[type].draft,
      tagError: this.composers[type].error,
      tagInputLocked: this.tagRequest?.type === type && this.tagRequest.generation === this.composers[type].generation,
      tagCreateInFlight: this.tagCreateInFlight,
      formLocked: interactionLocked(this.submission.phase, this.preparingSave),
      saveDisabled: !canSave(this.submission.phase, this.tagCreateInFlight, this.preparingSave),
      businessTimeText: businessTimeDisplay(this.form.businessTime) ?? "",
      timeError: isBusinessTimeAllowed(this.form.businessTime, this.accountCreatedTime) ? "" : "交易时间不得早于账户创建时间",
      futureHint: isFutureBusinessTime(this.form.businessTime, new Date())
        ? "未来交易保存后仍会立即影响当前余额；流水与统计归入所选自然月。"
        : "",
    });
  },
});

function applyOwnedSelection(selected: readonly number[], currentType: TransactionType, created: Tag): number[] {
  if (currentType !== created.type || selected.includes(created.id)) {
    return [...selected];
  }
  return [...selected, created.id];
}

function transactionType(value: unknown): TransactionType | null {
  return value === "INCOME" || value === "EXPENSE" ? value : null;
}

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}

function emitTransactionCreated(page: object, result: PostSaveResult): void {
  const owner = page as { getOpenerEventChannel?: () => { emit?: (name: string, data: PostSaveResult) => void } };
  const channel = owner.getOpenerEventChannel?.();
  if (channel === undefined || typeof channel.emit !== "function") {
    return;
  }
  channel.emit("transactionCreated", { transactionId: result.transactionId, targetMonth: result.targetMonth });
}
