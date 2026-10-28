package com.limingyuan.bookkeeping.category.domain;

import java.util.List;

/** PRD 8.3 + CR-001 section 2; prototype examples are not seed data. */
public final class FixedCategories {
    private FixedCategories() { }
    public record Root(CategoryType type, String name, List<String> children) { }
    public static final List<Root> ROOTS = List.of(
        new Root(CategoryType.INCOME, "工资", List.of()),
        new Root(CategoryType.INCOME, "奖金", List.of()),
        new Root(CategoryType.INCOME, "其他收入", List.of()),
        new Root(CategoryType.INCOME, "利息", List.of()),
        new Root(CategoryType.EXPENSE, "餐饮", List.of("早餐", "午餐", "晚餐", "其他")),
        new Root(CategoryType.EXPENSE, "交通", List.of("公共交通", "打车", "其他交通")),
        new Root(CategoryType.EXPENSE, "购物", List.of()),
        new Root(CategoryType.EXPENSE, "居住", List.of()),
        new Root(CategoryType.EXPENSE, "娱乐", List.of()),
        new Root(CategoryType.EXPENSE, "医疗", List.of()),
        new Root(CategoryType.EXPENSE, "其他支出", List.of())
    );
}
