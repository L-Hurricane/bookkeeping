Page({
  onOpenCategories() {
    wx.navigateTo({ url: "/pages/category-manage/index" });
  },

  onOpenTags() {
    wx.navigateTo({ url: "/pages/tag-manage/index" });
  },
});
