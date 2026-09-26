import { createLoadGuard } from "../../account/load-guard";
import type { CategoryNode } from "../../category/category";
import { ApiError } from "../../http/client";
import { categoryApi, tagApi } from "../../services/runtime";
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
import { createTransactionForm, validateTransactionAmount } from "../../transaction/form";
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
import type { TransactionType } from "../../transaction/transaction";

// Phase 0E-3 wires Transaction submission.
Page({
  loadGuard: createLoadGuard(),
  form: createTransactionForm(new Date()),
  accountCreatedTime: "",
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
  },

  onLoad(query: Record<string, string | undefined>) {
    const context = parseCreatePageContext(query);
    if (context === null) {
      this.setData({ contextInvalid: true });
      return;
    }
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
    const next = transactionType(event.currentTarget.dataset["type"]);
    if (next === null) {
      return;
    }
    const previous = this.form.type;
    const switched = switchCreateType(this.form, next);
    this.form = switched.form;
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
    this.form = { ...this.form, amount: event.detail.value };
    this.setData({ amount: event.detail.value });
  },

  onAmountBlur() {
    const validation = validateTransactionAmount(this.form.amount);
    this.setData({ amountError: amountFeedback(this.form.amount, validation) ?? "" });
  },

  onNameInput(event: WechatMiniprogram.Input) {
    this.form = { ...this.form, name: event.detail.value };
    this.setData({ name: event.detail.value });
  },

  onRemarkInput(event: WechatMiniprogram.Input) {
    this.form = { ...this.form, remark: event.detail.value };
    this.setData({ remark: event.detail.value });
  },

  onOpenCategories() {
    if (this.categories[this.form.type].phase !== "ready") {
      return;
    }
    this.setData({ selectorOpen: true });
  },

  onCloseCategories() {
    this.setData({ selectorOpen: false });
  },

  onChooseCategory(event: WechatMiniprogram.BaseEvent) {
    const raw = event.currentTarget.dataset["id"];
    const categoryId = raw === "" || raw === undefined ? null : Number(raw);
    this.form = { ...this.form, categoryId: categoryId !== null && Number.isSafeInteger(categoryId) ? categoryId : null };
    this.setData({ selectorOpen: false });
    this.publishForm();
  },

  onToggleTag(event: WechatMiniprogram.BaseEvent) {
    const tagId = Number(event.currentTarget.dataset["id"]);
    this.form = {
      ...this.form,
      selectedTagIds: toggleTagSelection(this.form.selectedTagIds, tagId, this.tags[this.form.type].items),
    };
    this.publishForm();
  },

  onOpenTagComposer() {
    const type = this.form.type;
    this.composers[type] = openComposer(this.composers[type]);
    this.publishForm();
  },

  onTagDraft(event: WechatMiniprogram.Input) {
    const type = this.form.type;
    this.composers[type] = editComposer(this.composers[type], event.detail.value);
    this.setData({ tagDraft: event.detail.value });
  },

  async onCreateTag() {
    if (this.tagCreateInFlight) {
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
    this.timeDraft = { date: event.detail.value, time: this.timeDraft?.time ?? "" };
    this.setData({ timeDate: event.detail.value });
  },

  onTimeClock(event: WechatMiniprogram.Input) {
    this.timeDraft = { date: this.timeDraft?.date ?? "", time: event.detail.value };
    this.setData({ timeClock: event.detail.value });
  },

  onTimeNow() {
    this.timeDraft = applyNow(new Date());
    this.setData({ timeDate: this.timeDraft.date, timeClock: this.timeDraft.time });
  },

  onTimeToday() {
    const draft = this.timeDraft ?? { date: "", time: "" };
    this.timeDraft = applyToday(draft, new Date());
    this.setData({ timeDate: this.timeDraft.date });
  },

  onTimeYesterday() {
    const draft = this.timeDraft ?? { date: "", time: "" };
    this.timeDraft = applyYesterday(draft, new Date());
    this.setData({ timeDate: this.timeDraft.date });
  },

  onConfirmTime() {
    const draft = this.timeDraft ?? { date: this.data.timeDate, time: this.data.timeClock };
    const resolved = resolveBusinessTimeEdit("confirm", this.form.businessTime, draft, this.accountCreatedTime);
    this.form = { ...this.form, businessTime: resolved.businessTime };
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

  onCancel() {
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
