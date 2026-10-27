import { ApiError } from "../../http/client";
import { deletionConfirm, manageRows, normalizedName, parentChoices, type NamedNode, type ParentChoice } from "../../manage/tree";
import { isUnauthorized } from "../../services/ledger";
import { categoryApi } from "../../services/runtime";
import type { CategoryType } from "../../category/category";

Page({
  generation: 0,
  parents: [] as ParentChoice[],
  nodes: [] as NamedNode[],
  moveParents: [] as ParentChoice[],
  dragId: 0,
  dragStartY: 0,
  dragY: 0,
  dragging: false,

  data: {
    phase: "loading" as "loading" | "ready" | "error",
    type: "EXPENSE" as CategoryType,
    rows: [] as { id: number; name: string; depth: number; hasChildren: boolean }[],
    selectedId: 0,
    name: "",
    parentLabels: ["作为一级"],
    parentIndex: 0,
    confirm: "",
    errorMessage: "",
    submitting: false,
    composer: false,
    parentSheet: false,
    moveLabels: [] as string[],
  },

  onShow() {
    void this.reload();
  },

  onType(event: WechatMiniprogram.TouchEvent) {
    const type = event.currentTarget.dataset.type;
    if (this.data.submitting || (type !== "INCOME" && type !== "EXPENSE") || type === this.data.type) {
      return;
    }
    this.setData({ type, selectedId: 0, confirm: "", name: "", parentIndex: 0 });
    void this.reload();
  },

  onName(event: WechatMiniprogram.Input) {
    this.setData({ name: event.detail.value, errorMessage: "" });
  },

  onParent(event: WechatMiniprogram.PickerChange) {
    const index = Number(event.detail.value);
    this.setData({ parentIndex: Number.isInteger(index) ? index : 0 });
  },

  onSelect(event: WechatMiniprogram.TouchEvent) {
    const id = Number(event.currentTarget.dataset.id);
    this.setData({ selectedId: id, confirm: "", errorMessage: "" });
  },

  onOpenComposer() {
    this.setData({ composer: true, errorMessage: "" });
  },

  onHold() { },

  onCloseSheet() {
    this.setData({ parentSheet: false });
  },

  onHandleStart(event: WechatMiniprogram.TouchEvent) {
    this.dragId = Number(event.currentTarget.dataset.id);
    this.dragStartY = event.touches[0]?.clientY ?? 0;
    this.dragY = this.dragStartY;
    this.dragging = false;
  },

  onHandleMove(event: WechatMiniprogram.TouchEvent) {
    this.dragY = event.touches[0]?.clientY ?? this.dragY;
    if (Math.abs(this.dragY - this.dragStartY) > 16) this.dragging = true;
  },

  onHandleEnd() {
    if (!this.dragging) {
      this.openParents(this.dragId);
      return;
    }
    wx.createSelectorQuery().in(this).selectAll(".row").boundingClientRect((rects) => {
      const boxes = Array.isArray(rects) ? rects : [];
      const hit = boxes.findIndex((box) => this.dragY >= box.top && this.dragY <= box.bottom);
      const target = this.data.rows[hit];
      if (target === undefined || target.id === this.dragId) {
        this.setData({ selectedId: this.dragId, errorMessage: "" });
        return;
      }
      const choices = parentChoices(this.nodes, this.dragId);
      if (!choices.some((choice) => choice.id === target.id)) {
        this.setData({ selectedId: this.dragId, errorMessage: "不能移动到这个上级" });
        return;
      }
      this.setData({ selectedId: this.dragId, errorMessage: "" });
      void this.run(() => categoryApi.moveCategory(this.dragId, target.id));
    }).exec();
  },

  openParents(id: number) {
    this.moveParents = parentChoices(this.nodes, id);
    this.setData({
      selectedId: id,
      parentSheet: true,
      moveLabels: this.moveParents.map((choice) => choice.label),
      confirm: "",
      errorMessage: "",
    });
  },

  onChooseMove(event: WechatMiniprogram.TouchEvent) {
    const index = Number(event.currentTarget.dataset.index);
    const parent = this.moveParents[index];
    this.setData({ parentSheet: false });
    if (parent === undefined || this.data.selectedId === 0) return;
    void this.run(() => categoryApi.moveCategory(this.data.selectedId, parent.id));
  },

  onRenamePrompt() {
    const name = normalizedName(this.data.name);
    if (name === null) {
      this.setData({ composer: true, parentSheet: false, errorMessage: "请在上方输入新名称" });
      return;
    }
    this.setData({ parentSheet: false });
    void this.onRename();
  },

  async onCreate() {
    const name = normalizedName(this.data.name);
    if (name === null) {
      this.setData({ errorMessage: "名称去除首尾空白后不能为空，且最长 100 个字符" });
      return;
    }
    const parent = this.parents[this.data.parentIndex] ?? this.parents[0];
    await this.run(() => categoryApi.createCategory(this.data.type, name, parent?.id ?? null));
  },

  async onRename() {
    const name = normalizedName(this.data.name);
    if (this.data.selectedId === 0 || name === null) {
      this.setData({ errorMessage: "请选择分类并输入新名称" });
      return;
    }
    await this.run(() => categoryApi.renameCategory(this.data.selectedId, name));
  },

  async onMove() {
    if (this.data.selectedId === 0) {
      this.setData({ errorMessage: "请选择要移动的分类" });
      return;
    }
    const parent = this.parents[this.data.parentIndex] ?? this.parents[0];
    await this.run(() => categoryApi.moveCategory(this.data.selectedId, parent?.id ?? null));
  },

  async onDelete() {
    if (this.data.submitting || this.data.selectedId === 0) {
      this.setData({ errorMessage: "请选择要删除的分类" });
      return;
    }
    const selected = this.data.rows.find((row) => row.id === this.data.selectedId);
    if (selected === undefined) {
      return;
    }
    if (selected.hasChildren) {
      this.setData({ errorMessage: "有下级的分类不能删除" });
      return;
    }
    this.setData({ submitting: true, errorMessage: "" });
    try {
      const count = await categoryApi.categoryDeletionPreview(this.data.selectedId);
      if (this.data.confirm !== deletionConfirm("分类", selected.name, count)) {
        this.setData({ confirm: deletionConfirm("分类", selected.name, count), submitting: false });
        return;
      }
      await categoryApi.deleteCategory(this.data.selectedId, count);
      this.setData({ selectedId: 0, confirm: "", submitting: false });
      await this.reload();
    } catch (error) {
      this.fail(error);
    }
  },

  async reload() {
    const generation = ++this.generation;
    this.setData({ phase: "loading", errorMessage: "" });
    try {
      const categories = await categoryApi.listCategories(this.data.type);
      if (generation !== this.generation) {
        return;
      }
      this.nodes = categories;
      this.parents = parentChoices(categories, null);
      this.setData({
        phase: "ready",
        rows: manageRows(categories),
        parentLabels: this.parents.map((choice) => choice.label),
        parentIndex: 0,
        errorMessage: "",
      });
    } catch (error) {
      if (generation !== this.generation || isUnauthorized(error)) {
        return;
      }
      this.setData({ phase: "error", errorMessage: "分类读取失败，请稍后重试" });
    }
  },

  async run(action: () => Promise<unknown>) {
    if (this.data.submitting) {
      return;
    }
    this.setData({ submitting: true, errorMessage: "", confirm: "" });
    try {
      await action();
      this.setData({ submitting: false, name: "" });
      await this.reload();
    } catch (error) {
      this.fail(error);
    }
  },

  fail(error: unknown) {
    if (isUnauthorized(error)) {
      return;
    }
    const code = error instanceof ApiError ? error.code : "";
    this.setData({ submitting: false, errorMessage: categoryError(code) });
  },
});

function categoryError(code: string): string {
  switch (code) {
    case "CATEGORY_NAME_CONFLICT":
      return "同一类型下分类名称已存在";
    case "CATEGORY_HAS_CHILDREN":
      return "有下级的分类不能删除";
    case "CATEGORY_PARENT_HAS_TRANSACTIONS":
      return "已有直接交易的分类不能再添加下级";
    case "CATEGORY_DEPTH":
      return "分类最多三级";
    case "CATEGORY_CYCLE":
      return "分类不能移动到自己或自己的下级";
    case "CATEGORY_TYPE_MISMATCH":
      return "不能跨收入和支出移动分类";
    case "CATEGORY_DELETE_CONFLICT":
      return "直接交易数量已变化，请重新确认";
    default:
      return "分类操作失败，请稍后重试";
  }
}
