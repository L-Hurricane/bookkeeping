import { ApiError } from "../../http/client";
import { deletionConfirm, manageRows, normalizedName, parentChoices, type ParentChoice } from "../../manage/tree";
import { isUnauthorized } from "../../services/ledger";
import { categoryApi } from "../../services/runtime";
import type { CategoryType } from "../../category/category";

const RULE = "分类按收入、支出分开。同一类型下名称唯一，最多三级，只有当前末级可以记账。";

Page({
  generation: 0,
  parents: [] as ParentChoice[],

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
    rule: RULE,
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
      this.parents = parentChoices(categories, this.data.selectedId || null);
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
