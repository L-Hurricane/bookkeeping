import { beginLoad, canApplyLoadResult, createLoadGuard } from "../../account/load-guard";
import {
  deleteGroupConfirm,
  indexFromDelta,
  planGroupDrop,
  type AccountGroup,
} from "../../account/grouping";
import { ApiError } from "../../http/client";
import { isUnauthorized } from "../../services/ledger";
import { accountApi } from "../../services/runtime";

const ROW_HEIGHT = 88;

Page({
  loadGuard: createLoadGuard(),
  groups: [] as AccountGroup[],
  drag: null as null | { id: number; startY: number; startIndex: number },

  data: {
    phase: "loading" as "loading" | "ready" | "error",
    groups: [] as AccountGroup[],
    name: "",
    submitting: false,
    errorMessage: "",
    draggingId: 0,
    dragOffset: 0,
  },

  onShow() {
    void this.loadGroups();
  },

  onUnload() {
    this.loadGuard.pageAlive = false;
  },

  async loadGroups() {
    const generation = beginLoad(this.loadGuard);
    if (generation === null) {
      return;
    }
    const blocking = this.data.phase !== "ready";
    if (blocking) {
      this.setData({ phase: "loading", errorMessage: "" });
    }
    try {
      const groups = await accountApi.listGroups();
      if (!canApplyLoadResult(this.loadGuard, generation)) {
        return;
      }
      this.groups = groups;
      this.setData({ phase: "ready", groups, errorMessage: "" });
    } catch (error) {
      if (!canApplyLoadResult(this.loadGuard, generation) || isUnauthorized(error)) {
        return;
      }
      this.setData({ phase: "error", errorMessage: "分组读取失败，请稍后重试" });
    }
  },

  onRetry() {
    void this.loadGroups();
  },

  onNameInput(event: WechatMiniprogram.Input) {
    this.setData({ name: event.detail.value, errorMessage: "" });
  },

  async onCreate() {
    if (this.data.submitting) {
      return;
    }
    const name = this.data.name.trim();
    if (name.length === 0 || name.length > 64) {
      this.setData({ errorMessage: "分组名称去除首尾空白后不能为空，且最长 64 个字符" });
      return;
    }
    if (name === "未分组") {
      this.setData({ errorMessage: "分组名称不能为未分组" });
      return;
    }
    this.setData({ submitting: true, errorMessage: "" });
    try {
      await accountApi.createGroup(name);
      this.setData({ submitting: false, name: "" });
      await this.loadGroups();
    } catch (error) {
      if (isUnauthorized(error)) {
        return;
      }
      this.setData({ submitting: false, errorMessage: failureMessage(error, "分组没有创建") });
    }
  },

  onRename(event: WechatMiniprogram.BaseEvent) {
    const groupId = readId(event);
    const group = this.groups.find((item) => item.id === groupId);
    if (groupId === null || group === undefined || this.data.submitting) {
      return;
    }
    wx.showModal({
      title: "重命名分组",
      editable: true,
      placeholderText: group.name,
      content: group.name,
      success: (result) => {
        if (!result.confirm) {
          return;
        }
        void this.rename(groupId, result.content ?? "");
      },
    });
  },

  async rename(groupId: number, rawName: string) {
    const name = rawName.trim();
    if (name.length === 0 || name.length > 64 || name === "未分组") {
      this.setData({ errorMessage: "分组名称不能为未分组，且最长 64 个字符" });
      return;
    }
    this.setData({ submitting: true, errorMessage: "" });
    try {
      await accountApi.renameGroup(groupId, name);
      this.setData({ submitting: false });
      await this.loadGroups();
    } catch (error) {
      if (isUnauthorized(error)) {
        return;
      }
      this.setData({ submitting: false, errorMessage: failureMessage(error, "分组没有重命名") });
    }
  },

  onDelete(event: WechatMiniprogram.BaseEvent) {
    const groupId = readId(event);
    const group = this.groups.find((item) => item.id === groupId);
    if (groupId === null || group === undefined || this.data.submitting) {
      return;
    }
    wx.showModal({
      title: "删除分组",
      content: deleteGroupConfirm(group.activeCount, group.archivedCount),
      confirmText: "删除分组",
      cancelText: "取消",
      success: (result) => {
        if (!result.confirm) {
          return;
        }
        void this.remove(groupId);
      },
    });
  },

  async remove(groupId: number) {
    this.setData({ submitting: true, errorMessage: "" });
    try {
      await accountApi.deleteGroup(groupId);
      this.setData({ submitting: false });
      await this.loadGroups();
    } catch (error) {
      if (isUnauthorized(error)) {
        return;
      }
      this.setData({ submitting: false, errorMessage: failureMessage(error, "分组没有删除") });
      await this.loadGroups();
    }
  },

  onDragStart(event: WechatMiniprogram.TouchEvent) {
    const touch = event.touches[0];
    const id = Number(event.currentTarget.dataset["id"]);
    if (touch === undefined || !Number.isSafeInteger(id)) {
      return;
    }
    const startIndex = this.groups.findIndex((group) => group.id === id);
    if (startIndex < 0) {
      return;
    }
    this.drag = { id, startY: touch.clientY, startIndex };
    this.setData({ draggingId: id, dragOffset: 0 });
  },

  onDragMove(event: WechatMiniprogram.TouchEvent) {
    const drag = this.drag;
    const touch = event.touches[0];
    if (drag === null || touch === undefined) {
      return;
    }
    this.setData({ dragOffset: touch.clientY - drag.startY });
  },

  async onDragEnd(event: WechatMiniprogram.TouchEvent) {
    const drag = this.drag;
    this.drag = null;
    this.setData({ draggingId: 0, dragOffset: 0 });
    const touch = event.changedTouches[0];
    if (drag === null || touch === undefined || this.data.submitting) {
      return;
    }
    const toIndex = indexFromDelta(drag.startIndex, touch.clientY - drag.startY, ROW_HEIGHT, this.groups.length);
    const next = planGroupDrop(this.groups.map((group) => group.id), drag.id, toIndex);
    if (next === null) {
      return;
    }
    this.setData({ submitting: true, errorMessage: "" });
    try {
      const groups = await accountApi.reorderGroups(next);
      this.groups = groups;
      this.setData({ submitting: false, groups });
    } catch (error) {
      if (isUnauthorized(error)) {
        return;
      }
      await this.loadGroups();
      this.setData({ submitting: false, errorMessage: "排序没有保存，已恢复为服务器上的顺序" });
    }
  },
});

function readId(event: WechatMiniprogram.BaseEvent): number | null {
  const id = Number(event.currentTarget.dataset["id"]);
  if (!Number.isSafeInteger(id) || id <= 0) {
    return null;
  }
  return id;
}

function failureMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError && error.status === 409) {
    return "分组名称已存在";
  }
  if (error instanceof ApiError && error.status === 400) {
    return error.message.length > 0 ? error.message : fallback;
  }
  return `${fallback}，请稍后重试`;
}
