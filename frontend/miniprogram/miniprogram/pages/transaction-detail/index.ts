import {
  defaultSelectableAccountId,
  findAccount,
  type Account,
} from "../../account/account";
import { stashLedgerHandoff } from "../../account/ledger-handoff";
import { createLoadGuard } from "../../account/load-guard";
import { moneyToneClass } from "../../account/money-tone";
import { accountHomePath } from "../../account/navigation";
import type { CategoryNode } from "../../category/category";
import { ApiError } from "../../http/client";
import { handoffPostSave } from "../../ledger/post-save";
import { accountApi, categoryApi, tagApi, transactionApi } from "../../services/runtime";
import type { Tag } from "../../tag/tag";
import {
  applyDatePickerValue,
  applyNow,
  applyTimePickerValue,
  applyToday,
  applyYesterday,
  businessTimeDisplay,
  businessTimeDraft,
  businessTimePickerMinute,
  isBusinessTimeAllowed,
  isFutureBusinessTime,
  normalizeDateOnBlur,
  normalizeTimeOnBlur,
  resolveBusinessTimeEdit,
  timeEditorFields,
  type BusinessTimeDraft,
} from "../../transaction/business-time";
import {
  applyUpdateOutcome,
  beginUpdate,
  buildUpdateTransactionSnapshot,
  classifyDeleteError,
  classifyDetailError,
  classifyUpdateError,
  decideDelete,
  detailActionAccess,
  DELETE_UNCERTAIN_MESSAGE,
  abandonEditSession,
  applyCreateTagToEditSession,
  claimTagCreate,
  editCategoryText,
  editFormFromTransaction,
  editTagChoices,
  editingUpdate,
  enterEditSession,
  finishTagCreate,
  initialTagEditSession,
  resolveEditCancel,
  retryUpdate,
  tagCreateInFlight,
  transactionDetailView,
  updateValidationMessage,
  type UpdateState,
} from "../../transaction/edit";
import { createTransactionForm, validateTransactionAmount } from "../../transaction/form";
import { parseDetailPageContext, type DetailPageContext } from "../../transaction/navigation";
import {
  amountFeedback,
  beginBucket,
  categoryRows,
  createTagFailureMessage,
  editComposer,
  emptyBucket,
  finishBucket,
  mergeTagLists,
  openComposer,
  prepareCreateTag,
  replaceItems,
  toggleTagSelection,
  type CategoryRow,
  type TypeBucket,
} from "../../transaction/reference";
import { UNCERTAIN_MESSAGE } from "../../transaction/submission";
import type { TransactionView } from "../../transaction/transaction";

type Screen = "invalid" | "loading" | "error" | "missing" | "detail" | "edit" | "delete-uncertain";

Page({
  loadGuard: createLoadGuard(),
  context: null as DetailPageContext | null,
  transaction: null as TransactionView | null,
  detailGeneration: 0,
  form: createTransactionForm(new Date()),
  update: editingUpdate(),
  updateCompleted: false,
  accounts: [] as Account[],
  selectedAccountId: 0,
  selectedAccountCreatedTime: "",
  categories: emptyBucket<CategoryNode>(),
  tags: emptyBucket<Tag>(),
  tagSession: initialTagEditSession(),
  timeDraft: null as BusinessTimeDraft | null,
  deletePhase: "idle" as "idle" | "deleting" | "uncertain",
  screen: "loading" as Screen,

  data: {
    screen: "loading" as Screen,
    errorMessage: "",
    actionError: "",
    typeLabel: "",
    title: "",
    amountLabel: "",
    amountToneClass: "money-zero",
    categoryLabel: "",
    tagNames: [] as string[],
    businessTimeLabel: "",
    remarkLabel: "",
    amount: "",
    name: "",
    remark: "",
    amountError: "",
    accountText: "选择账户",
    accountPhase: "idle" as "idle" | "loading" | "ready" | "error",
    accountRows: [] as { id: number; label: string }[],
    accountSelectorOpen: false,
    accountError: "",
    categoryText: "未分类",
    categoryPhase: "idle" as TypeBucket<CategoryNode>["phase"],
    categoryRows: [] as CategoryRow[],
    selectorOpen: false,
    tagPhase: "idle" as TypeBucket<Tag>["phase"],
    tagChips: [] as { id: number; name: string; selected: boolean }[],
    tagComposer: false,
    tagDraft: "",
    tagError: "",
    tagInputLocked: false,
    tagCreateInFlight: false,
    formLocked: false,
    saveDisabled: false,
    submitError: "",
    uncertain: false,
    businessTimeText: "",
    timeError: "",
    futureHint: "",
    timeEditorOpen: false,
    timeDate: "",
    timeClock: "",
    timePickerMinute: "00:00",
    timeDraftError: "",
    deleteLocked: false,
    deleteHint: "",
  },

  onLoad(query: Record<string, string | undefined>) {
    const context = parseDetailPageContext(query);
    if (context === null) {
      this.screen = "invalid";
      this.setData({ screen: "invalid" });
      return;
    }
    this.context = context;
    void this.loadDetail();
  },

  onUnload() {
    if (this.deletePhase === "deleting" || this.deletePhase === "uncertain" || this.update.phase === "submitting" || this.update.phase === "uncertain") {
      emitListStale(this);
    }
    this.loadGuard.pageAlive = false;
    wx.disableAlertBeforeUnload();
  },

  async loadDetail() {
    const context = this.context;
    if (context === null) {
      return;
    }
    const generation = ++this.detailGeneration;
    this.screen = "loading";
    this.setData({ screen: "loading", errorMessage: "" });
    try {
      const transaction = await transactionApi.getTransaction(context.accountId, context.transactionId);
      if (!this.loadGuard.pageAlive || generation !== this.detailGeneration) {
        return;
      }
      this.transaction = transaction;
      this.publishDetail();
    } catch (error) {
      const failure = classifyDetailError(error);
      if (!this.loadGuard.pageAlive || generation !== this.detailGeneration || failure === "unauthorized") {
        return;
      }
      if (failure === "missing") {
        this.showMissing();
        return;
      }
      this.screen = "error";
      const errorMessage = error instanceof Error && error.name === "TransactionResponseError" ? "交易数据不符合约定" : "交易读取失败，请稍后重试";
      this.setData({ screen: "error", errorMessage });
    }
  },

  onRetryDetail() {
    void this.loadDetail();
  },

  onEdit() {
    if (this.screen !== "detail" || !detailActionAccess(this.deletePhase).edit || this.transaction === null || this.context === null) {
      return;
    }
    this.form = editFormFromTransaction(this.transaction);
    this.selectedAccountId = this.transaction.accountId;
    this.selectedAccountCreatedTime = this.context.accountCreatedTime;
    this.update = editingUpdate();
    this.updateCompleted = false;
    this.tagSession = enterEditSession(this.tagSession);
    this.screen = "edit";
    this.timeDraft = null;
    wx.setNavigationBarTitle({ title: "编辑交易" });
    this.publishEdit();
    void this.loadAccounts();
    void this.loadCategories();
    void this.loadTags();
  },

  onDelete() {
    if (this.screen !== "detail" || !detailActionAccess(this.deletePhase).delete || this.transaction === null) {
      return;
    }
    wx.showModal({
      title: "确认删除？",
      content: "删除后会撤销该交易对余额和统计的影响。",
      confirmText: "删除",
      cancelText: "取消",
      confirmColor: "#b42318",
      success: (result) => {
        if (decideDelete(result.confirm) !== "send" || this.screen !== "detail" || !detailActionAccess(this.deletePhase).delete) {
          return;
        }
        void this.performDelete();
      },
    });
  },

  onRetryDelete() {
    if (this.screen !== "delete-uncertain" || !detailActionAccess(this.deletePhase).retry) {
      return;
    }
    void this.performDelete();
  },

  async performDelete() {
    const context = this.context;
    const access = detailActionAccess(this.deletePhase);
    if (context === null || (!access.delete && !access.retry)) {
      return;
    }
    this.deletePhase = "deleting";
    this.publishDeleteLock();
    try {
      await transactionApi.deleteTransaction(context.accountId, context.transactionId);
      if (!this.loadGuard.pageAlive) {
        return;
      }
      this.leaveAfterDelete();
    } catch (error) {
      if (!this.loadGuard.pageAlive) {
        return;
      }
      const outcome = classifyDeleteError(error);
      if (outcome.kind === "absent") {
        this.leaveAfterDelete();
        return;
      }
      if (outcome.kind === "unauthorized") {
        return;
      }
      if (outcome.kind === "uncertain") {
        this.deletePhase = "uncertain";
        this.screen = "delete-uncertain";
        this.syncDeleteAlert();
        this.setData({ screen: "delete-uncertain", submitError: DELETE_UNCERTAIN_MESSAGE, deleteLocked: false, deleteHint: "" });
        return;
      }
      this.deletePhase = "idle";
      this.screen = "detail";
      this.syncDeleteAlert();
      this.setData({ screen: "detail", actionError: outcome.message, deleteLocked: false, deleteHint: "" });
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
    if (!this.canEdit() || this.categories.phase !== "ready") {
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
    this.setData({ selectorOpen: false, submitError: "" });
    this.publishEdit();
  },

  onToggleTag(event: WechatMiniprogram.BaseEvent) {
    if (!this.canEdit()) {
      return;
    }
    const tagId = Number(event.currentTarget.dataset["id"]);
    this.form = { ...this.form, selectedTagIds: toggleTagSelection(this.form.selectedTagIds, tagId, this.currentTagChoices()) };
    this.setData({ submitError: "" });
    this.publishEdit();
  },

  onOpenTagComposer() {
    if (!this.canEdit()) {
      return;
    }
    this.tagSession = { ...this.tagSession, composer: openComposer(this.tagSession.composer) };
    this.publishEdit();
  },

  onTagDraft(event: WechatMiniprogram.Input) {
    if (!this.canEdit()) {
      return;
    }
    this.tagSession = { ...this.tagSession, composer: editComposer(this.tagSession.composer, event.detail.value) };
    this.setData({ tagDraft: event.detail.value });
  },

  async onCreateTag() {
    if (!this.canEdit() || tagCreateInFlight(this.tagSession)) {
      return;
    }
    const plan = prepareCreateTag(this.tagSession.composer.draft, this.currentTagChoices());
    if (plan.status === "empty" || plan.status === "duplicate") {
      const message = plan.status === "empty" ? "标签名称不能为空" : "标签已存在，可直接选择";
      this.tagSession = { ...this.tagSession, composer: { ...this.tagSession.composer, error: message } };
      this.publishEdit();
      return;
    }
    const claimed = claimTagCreate(this.tagSession);
    const request = claimed.request;
    this.tagSession = claimed.session;
    this.publishEdit();
    try {
      const created = await tagApi.createTag(this.form.type, plan.name);
      if (!this.loadGuard.pageAlive) {
        return;
      }
      const applied = applyCreateTagToEditSession(this.tagSession, request, this.tags, this.form.selectedTagIds, { status: "success", tag: created });
      this.tags = applied.bucket;
      this.tagSession = applied.session;
      this.form = { ...this.form, selectedTagIds: applied.selectedTagIds };
    } catch (error) {
      if (!this.loadGuard.pageAlive || isUnauthorized(error)) {
        return;
      }
      const message = error instanceof ApiError ? createTagFailureMessage(error.status, error.code) : createTagFailureMessage(0, undefined);
      this.tagSession = applyCreateTagToEditSession(this.tagSession, request, this.tags, this.form.selectedTagIds, { status: "failure", message }).session;
    } finally {
      if (this.loadGuard.pageAlive) {
        this.tagSession = finishTagCreate(this.tagSession, request);
        if (this.screen === "edit") {
          this.publishEdit();
        }
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
    this.setData({ selectorOpen: false, timeEditorOpen: true, timeDraftError: "", ...timeEditorFields(draft) });
  },

  onTimeDate(event: WechatMiniprogram.Input) {
    if (!this.canEdit()) {
      return;
    }
    this.timeDraft = { date: event.detail.value, time: this.timeDraft?.time ?? "" };
    this.setData({ timeDate: event.detail.value });
  },

  onTimeDateBlur() {
    if (!this.canEdit()) {
      return;
    }
    const draft = this.timeDraft ?? { date: this.data.timeDate, time: this.data.timeClock };
    const date = normalizeDateOnBlur(draft.date);
    this.timeDraft = { date, time: draft.time };
    this.setData({ timeDate: date });
  },

  onPickTimeDate(event: WechatMiniprogram.PickerChange) {
    if (!this.canEdit()) {
      return;
    }
    const draft = this.timeDraft ?? { date: this.data.timeDate, time: this.data.timeClock };
    this.timeDraft = applyDatePickerValue(String(event.detail.value), draft);
    this.setData(timeEditorFields(this.timeDraft));
  },

  onTimeClock(event: WechatMiniprogram.Input) {
    if (!this.canEdit()) {
      return;
    }
    this.timeDraft = { date: this.timeDraft?.date ?? "", time: event.detail.value };
    this.setData({ timeClock: event.detail.value, timePickerMinute: businessTimePickerMinute(event.detail.value) });
  },

  onTimeClockBlur() {
    if (!this.canEdit()) {
      return;
    }
    const draft = this.timeDraft ?? { date: this.data.timeDate, time: this.data.timeClock };
    const time = normalizeTimeOnBlur(draft.time);
    this.timeDraft = { date: draft.date, time };
    this.setData(timeEditorFields(this.timeDraft));
  },

  onPickTimeClock(event: WechatMiniprogram.PickerChange) {
    if (!this.canEdit()) {
      return;
    }
    const draft = this.timeDraft ?? { date: this.data.timeDate, time: this.data.timeClock };
    this.timeDraft = applyTimePickerValue(String(event.detail.value), draft);
    this.setData(timeEditorFields(this.timeDraft));
  },

  onTimeNow() {
    if (!this.canEdit()) {
      return;
    }
    this.timeDraft = applyNow(new Date());
    this.setData(timeEditorFields(this.timeDraft));
  },

  onTimeToday() {
    if (!this.canEdit()) {
      return;
    }
    this.timeDraft = applyToday(this.timeDraft ?? { date: "", time: "" }, new Date());
    this.setData(timeEditorFields(this.timeDraft));
  },

  onTimeYesterday() {
    if (!this.canEdit()) {
      return;
    }
    this.timeDraft = applyYesterday(this.timeDraft ?? { date: "", time: "" }, new Date());
    this.setData(timeEditorFields(this.timeDraft));
  },

  onConfirmTime() {
    if (!this.canEdit() || this.context === null) {
      return;
    }
    const draft = this.timeDraft ?? { date: this.data.timeDate, time: this.data.timeClock };
    const resolved = resolveBusinessTimeEdit("confirm", this.form.businessTime, draft, this.context.accountCreatedTime);
    this.form = { ...this.form, businessTime: resolved.businessTime };
    this.timeDraft = resolved.open ? draft : null;
    this.setData({ timeEditorOpen: resolved.open, timeDraftError: resolved.error, submitError: "" });
    if (!resolved.open) {
      this.publishEdit();
    }
  },

  onCancelTime() {
    if (this.context === null) {
      return;
    }
    const resolved = resolveBusinessTimeEdit("cancel", this.form.businessTime, this.timeDraft ?? { date: "", time: "" }, this.context.accountCreatedTime);
    this.form = { ...this.form, businessTime: resolved.businessTime };
    this.timeDraft = null;
    this.setData({ timeEditorOpen: false, timeDraftError: "" });
  },

  onRetryCategories() {
    void this.loadCategories();
  },

  onRetryTags() {
    void this.loadTags();
  },

  async onSave() {
    if (this.context === null || this.transaction === null || this.update.phase !== "editing" || tagCreateInFlight(this.tagSession)) {
      if (tagCreateInFlight(this.tagSession) && this.update.phase === "editing") {
        this.setData({ submitError: "请等待标签创建完成" });
      }
      return;
    }
    const built = buildUpdateTransactionSnapshot(
      this.form,
      this.context.accountId,
      this.selectedAccountId > 0 ? this.selectedAccountId : this.context.accountId,
      this.transaction.id,
      this.transaction.type,
      this.selectedAccountCreatedTime.length > 0 ? this.selectedAccountCreatedTime : this.context.accountCreatedTime,
    );
    if (!built.ok) {
      this.setData({ submitError: updateValidationMessage(built.reason) });
      return;
    }
    const next = beginUpdate(this.update, built.value);
    if (next === null) {
      return;
    }
    this.update = next;
    this.publishEdit();
    await this.sendUpdate();
  },

  async onRetryUpdate() {
    const next = retryUpdate(this.update);
    if (next === null) {
      return;
    }
    this.update = next;
    this.publishEdit();
    await this.sendUpdate();
  },

  onCancelEdit() {
    if (this.update.phase === "submitting") {
      wx.showModal({ title: "请稍候", content: "正在保存，请稍候", showCancel: false, confirmText: "知道了" });
      return;
    }
    if (this.update.phase === "uncertain") {
      wx.showModal({
        title: UNCERTAIN_MESSAGE,
        content: "离开后请到流水中确认本次修改是否已保存。",
        confirmText: "离开",
        cancelText: "继续确认",
        success: (result) => {
          if (result.confirm) {
            wx.disableAlertBeforeUnload();
            emitListStale(this);
            wx.navigateBack();
          }
        },
      });
      return;
    }
    wx.showModal({
      title: "放弃本次修改？",
      confirmText: "放弃修改",
      cancelText: "继续编辑",
      success: (result) => {
        if (resolveEditCancel(result.confirm ? "abandon" : "continue") === "detail") {
          this.returnToDetail();
        }
      },
    });
  },

  onBack() {
    if (this.deletePhase === "deleting") {
      return;
    }
    wx.disableAlertBeforeUnload();
    wx.navigateBack();
  },

  onHold() {
    return;
  },

  async loadCategories() {
    if (this.transaction === null) {
      return;
    }
    const started = beginBucket(this.categories);
    this.categories = started.bucket;
    if (this.screen === "edit") {
      this.setData({ categoryPhase: "loading" });
    }
    try {
      const items = await categoryApi.listCategories(this.transaction.type);
      this.finishCategories(started.generation, items);
    } catch (error) {
      if (isUnauthorized(error)) {
        return;
      }
      this.finishCategories(started.generation, null);
    }
  },

  finishCategories(generation: number, items: CategoryNode[] | null) {
    const next = finishBucket(this.categories, generation, this.loadGuard.pageAlive, items, replaceItems);
    if (next === null) {
      return;
    }
    this.categories = next;
    if (this.screen === "edit") {
      this.publishEdit();
    }
  },

  async loadTags() {
    if (this.transaction === null) {
      return;
    }
    const started = beginBucket(this.tags);
    this.tags = started.bucket;
    if (this.screen === "edit") {
      this.setData({ tagPhase: "loading" });
    }
    try {
      const items = await tagApi.listTags(this.transaction.type);
      this.finishTags(started.generation, items);
    } catch (error) {
      if (isUnauthorized(error)) {
        return;
      }
      this.finishTags(started.generation, null);
    }
  },

  finishTags(generation: number, items: Tag[] | null) {
    const next = finishBucket(this.tags, generation, this.loadGuard.pageAlive, items, mergeTagLists);
    if (next === null) {
      return;
    }
    this.tags = next;
    if (this.screen === "edit") {
      this.publishEdit();
    }
  },

  async sendUpdate() {
    const snapshot = this.update.attempt;
    if (snapshot === null || this.update.phase !== "submitting") {
      return;
    }
    try {
      const transaction = await transactionApi.updateTransaction(snapshot);
      if (!this.loadGuard.pageAlive || this.update.attempt !== snapshot) {
        return;
      }
      this.transaction = transaction;
      this.update = applyUpdateOutcome(this.update, { kind: "success", transaction });
    } catch (error) {
      if (!this.loadGuard.pageAlive || this.update.attempt !== snapshot) {
        return;
      }
      const outcome = classifyUpdateError(error);
      if (outcome.kind === "missing") {
        this.showMissing();
        return;
      }
      if (outcome.kind === "unauthorized") {
        return;
      }
      this.update = applyUpdateOutcome(this.update, outcome);
    }
    this.publishEdit();
    this.returnAfterUpdate();
  },

  returnAfterUpdate() {
    const decision = handoffPostSave(this.update.phase, this.update.postSave, this.updateCompleted);
    if (decision.result === null) {
      return;
    }
    this.updateCompleted = true;
    wx.disableAlertBeforeUnload();
    const result = decision.result;
    const sourceAccountId = this.context?.accountId ?? 0;
    if (result.accountId > 0 && result.accountId !== sourceAccountId) {
      stashLedgerHandoff(result.accountId, result);
      wx.reLaunch({ url: accountHomePath(result.accountId) });
      return;
    }
    emitTransactionUpdated(this, result);
    wx.navigateBack();
  },

  async loadAccounts() {
    this.setData({ accountPhase: "loading", accountError: "" });
    try {
      const accounts = await accountApi.listAccounts();
      if (!this.loadGuard.pageAlive || this.screen !== "edit") {
        return;
      }
      this.accounts = accounts;
      const preferred = this.selectedAccountId > 0 ? this.selectedAccountId : this.transaction?.accountId ?? null;
      const selectedId = defaultSelectableAccountId(accounts, preferred);
      if (selectedId !== null) {
        this.applySelectedAccount(selectedId);
      }
      this.setData({
        accountPhase: "ready",
        accountRows: accounts.map((account) => ({
          id: account.id,
          label: `${account.name}（${account.kind === "CREDIT" ? "信用" : "普通"}）`,
        })),
        accountError: selectedId === null && accounts.length > 1 ? "请选择账户" : "",
      });
    } catch (error) {
      if (!this.loadGuard.pageAlive || isUnauthorized(error)) {
        return;
      }
      this.setData({ accountPhase: "error", accountError: "账户列表加载失败" });
    }
  },

  applySelectedAccount(accountId: number) {
    const account = findAccount(this.accounts, accountId);
    if (account === null) {
      return;
    }
    this.selectedAccountId = account.id;
    this.selectedAccountCreatedTime = account.createdTime;
    this.setData({
      accountText: `${account.name}（${account.kind === "CREDIT" ? "信用" : "普通"}）`,
      accountError: "",
    });
  },

  onOpenAccounts() {
    if (!this.canEdit() || this.data.accountPhase !== "ready" || this.accounts.length === 0) {
      return;
    }
    this.setData({ accountSelectorOpen: true });
  },

  onCloseAccounts() {
    this.setData({ accountSelectorOpen: false });
  },

  onChooseAccount(event: WechatMiniprogram.BaseEvent) {
    if (!this.canEdit()) {
      return;
    }
    const accountId = Number(event.currentTarget.dataset["id"]);
    if (!Number.isSafeInteger(accountId) || accountId <= 0) {
      return;
    }
    this.applySelectedAccount(accountId);
    this.setData({ accountSelectorOpen: false, submitError: "" });
  },

  onRetryAccounts() {
    void this.loadAccounts();
  },

  returnToDetail() {
    this.tagSession = abandonEditSession(this.tagSession);
    this.screen = "detail";
    this.update = editingUpdate();
    this.timeDraft = null;
    wx.setNavigationBarTitle({ title: "交易详情" });
    wx.disableAlertBeforeUnload();
    this.publishDetail();
  },

  showMissing() {
    this.tagSession = abandonEditSession(this.tagSession);
    this.screen = "missing";
    this.transaction = null;
    this.update = editingUpdate();
    wx.setNavigationBarTitle({ title: "交易详情" });
    wx.disableAlertBeforeUnload();
    this.setData({ screen: "missing" });
  },

  leaveAfterDelete() {
    this.deletePhase = "idle";
    wx.disableAlertBeforeUnload();
    emitListStale(this);
    wx.navigateBack();
  },

  publishDeleteLock() {
    this.syncDeleteAlert();
    this.setData({ deleteLocked: true, deleteHint: "正在删除…" });
  },

  syncDeleteAlert() {
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

  publishDetail() {
    if (this.transaction === null) {
      return;
    }
    const view = transactionDetailView(this.transaction);
    this.screen = "detail";
    this.deletePhase = "idle";
    wx.disableAlertBeforeUnload();
    this.setData({
      screen: "detail",
      actionError: "",
      typeLabel: view.typeLabel,
      title: view.title,
      amountLabel: view.amountLabel,
      amountToneClass: moneyToneClass(this.transaction.amount),
      categoryLabel: view.categoryLabel,
      tagNames: [...view.tagNames],
      businessTimeLabel: view.businessTimeLabel,
      remarkLabel: view.remarkLabel,
      deleteLocked: false,
      deleteHint: "",
    });
  },

  publishEdit() {
    const context = this.context;
    if (context === null) {
      return;
    }
    const editing = this.update.phase === "editing";
    const creatingTag = tagCreateInFlight(this.tagSession);
    this.setData({
      screen: "edit",
      typeLabel: this.form.type === "INCOME" ? "收入" : "支出",
      amount: this.form.amount,
      name: this.form.name,
      remark: this.form.remark,
      categoryText: editCategoryText(this.categories.items, this.form.categoryId, this.transaction?.category ?? null),
      categoryPhase: this.categories.phase,
      categoryRows: categoryRows(this.categories.items),
      tagPhase: this.tags.phase,
      tagChips: this.currentTagChoices().map((tag) => ({
        id: tag.id,
        name: tag.name,
        selected: this.form.selectedTagIds.includes(tag.id),
      })),
      tagComposer: this.tagSession.composer.open,
      tagDraft: this.tagSession.composer.draft,
      tagError: this.tagSession.composer.error,
      tagInputLocked: creatingTag,
      tagCreateInFlight: creatingTag,
      formLocked: !editing,
      saveDisabled: !editing || creatingTag,
      submitError: this.update.error,
      uncertain: this.update.phase === "uncertain",
      businessTimeText: businessTimeDisplay(this.form.businessTime) ?? "",
      timeError: isBusinessTimeAllowed(
        this.form.businessTime,
        this.selectedAccountCreatedTime.length > 0 ? this.selectedAccountCreatedTime : context.accountCreatedTime,
      )
        ? ""
        : "请输入有效的日期和时间",
      futureHint: isFutureBusinessTime(this.form.businessTime, new Date())
        ? "未来交易保存后仍会立即影响当前余额；流水与统计归入所选自然月。"
        : "",
    });
    this.syncEditAlert();
  },

  syncEditAlert() {
    const message = editAlertMessage(this.update.phase);
    if (message === null) {
      wx.disableAlertBeforeUnload();
      return;
    }
    wx.enableAlertBeforeUnload({ message });
  },

  canEdit(): boolean {
    return this.screen === "edit" && this.update.phase === "editing";
  },

  currentTagChoices(): Tag[] {
    return editTagChoices(this.transaction?.tags ?? [], this.tags.items, this.form.type);
  },
});

function editAlertMessage(phase: UpdateState["phase"]): string | null {
  if (phase === "editing") {
    return "放弃本次修改？";
  }
  if (phase === "submitting") {
    return "正在保存，离开后请到流水中确认是否保存成功";
  }
  if (phase === "uncertain") {
    return "保存结果尚未确认。离开后请到流水中确认本次修改是否已保存。";
  }
  return null;
}

function emitListStale(page: object): void {
  const owner = page as { getOpenerEventChannel?: () => { emit?: (name: string, data: Record<string, never>) => void } };
  const channel = owner.getOpenerEventChannel?.();
  if (channel === undefined || typeof channel.emit !== "function") {
    return;
  }
  channel.emit("listStale", {});
}

function emitTransactionUpdated(
  page: object,
  result: { accountId: number; transactionId: number; targetMonth: string },
): void {
  const owner = page as {
    getOpenerEventChannel?: () => {
      emit?: (name: string, data: { accountId: number; transactionId: number; targetMonth: string }) => void;
    };
  };
  const channel = owner.getOpenerEventChannel?.();
  if (channel === undefined || typeof channel.emit !== "function") {
    return;
  }
  channel.emit("transactionUpdated", {
    accountId: result.accountId,
    transactionId: result.transactionId,
    targetMonth: result.targetMonth,
  });
}

function isUnauthorized(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}
