import { ApiError } from "../../http/client";
import { deletionConfirm, manageRows, normalizedName, parentChoices, type ParentChoice } from "../../manage/tree";
import { isUnauthorized } from "../../services/ledger";
import { tagApi } from "../../services/runtime";

const RULE = "标签不区分收入和支出，名称全局唯一，最多三级。上级和下级都可以单独关联交易。";

Page({
  generation: 0,
  parents: [] as ParentChoice[],

  data: {
    phase: "loading" as "loading" | "ready" | "error",
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
    await this.run(() => tagApi.createTag("EXPENSE", name, parent?.id ?? null));
  },

  async onRename() {
    const name = normalizedName(this.data.name);
    if (this.data.selectedId === 0 || name === null) {
      this.setData({ errorMessage: "请选择标签并输入新名称" });
      return;
    }
    await this.run(() => tagApi.renameTag(this.data.selectedId, "EXPENSE", name));
  },

  async onMove() {
    if (this.data.selectedId === 0) {
      this.setData({ errorMessage: "请选择要移动的标签" });
      return;
    }
    const parent = this.parents[this.data.parentIndex] ?? this.parents[0];
    await this.run(() => tagApi.moveTag(this.data.selectedId, "EXPENSE", parent?.id ?? null));
  },

  async onDelete() {
    if (this.data.submitting || this.data.selectedId === 0) {
      this.setData({ errorMessage: "请选择要删除的标签" });
      return;
    }
    const selected = this.data.rows.find((row) => row.id === this.data.selectedId);
    if (selected === undefined) {
      return;
    }
    if (selected.hasChildren) {
      this.setData({ errorMessage: "有下级的标签不能删除" });
      return;
    }
    this.setData({ submitting: true, errorMessage: "" });
    try {
      const count = await tagApi.tagDeletionPreview(this.data.selectedId);
      const confirm = deletionConfirm("标签", selected.name, count);
      if (this.data.confirm !== confirm) {
        this.setData({ confirm, submitting: false });
        return;
      }
      await tagApi.deleteTag(this.data.selectedId, count);
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
      const tags = await tagApi.listTagTree("EXPENSE");
      if (generation !== this.generation) {
        return;
      }
      this.parents = parentChoices(tags, this.data.selectedId || null);
      this.setData({
        phase: "ready",
        rows: manageRows(tags),
        parentLabels: this.parents.map((choice) => choice.label),
        parentIndex: 0,
        errorMessage: "",
      });
    } catch (error) {
      if (generation !== this.generation || isUnauthorized(error)) {
        return;
      }
      this.setData({ phase: "error", errorMessage: "标签读取失败，请稍后重试" });
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
    this.setData({ submitting: false, errorMessage: tagError(code) });
  },
});

function tagError(code: string): string {
  switch (code) {
    case "TAG_NAME_CONFLICT":
      return "标签名称已存在";
    case "TAG_HAS_CHILDREN":
      return "有下级的标签不能删除";
    case "TAG_DEPTH":
      return "标签最多三级";
    case "TAG_CYCLE":
      return "标签不能移动到自己或自己的下级";
    case "TAG_DELETE_CONFLICT":
      return "直接关联交易数量已变化，请重新确认";
    default:
      return "标签操作失败，请稍后重试";
  }
}
