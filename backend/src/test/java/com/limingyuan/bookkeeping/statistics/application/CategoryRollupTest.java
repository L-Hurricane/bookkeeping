package com.limingyuan.bookkeeping.statistics.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryRollupTest {
    @Test
    void rollsThreeLevelsOnceAndKeepsUncategorizedInTheNet() {
        List<CategoryRollup.Node> nodes = List.of(
                new CategoryRollup.Node(1, null, "餐饮"),
                new CategoryRollup.Node(2, 1L, "早餐"),
                new CategoryRollup.Node(3, 2L, "豆浆"),
                new CategoryRollup.Node(4, null, "交通"));
        List<CategoryRollup.Fact> facts = List.of(
                new CategoryRollup.Fact(3L, new BigDecimal("400.00")),
                new CategoryRollup.Fact(4L, new BigDecimal("300.00")),
                new CategoryRollup.Fact(null, new BigDecimal("300.00")));
        CategoryRollup.Tree tree = CategoryRollup.rollup(nodes, facts);
        assertEquals(new BigDecimal("1000.00"), tree.net());
        assertEquals(1, tree.roots().size() == 2 ? tree.roots().get(0).totals().transactionCount() : -1);
        CategoryRollup.NodeView food = tree.roots().get(0);
        assertEquals("餐饮", food.name());
        assertEquals(new BigDecimal("400.00"), food.totals().amount());
        assertEquals("豆浆", food.children().get(0).children().get(0).name());
        assertEquals(new BigDecimal("300.00"), tree.uncategorized().amount());
        assertEquals("40.00", StatisticsShare.of(food.totals().amount(), tree.net()));
        assertEquals("100.00", shareSum(tree));
    }

    @Test
    void negativeNetSumsToMinusOneHundred() {
        CategoryRollup.Tree tree = CategoryRollup.rollup(List.of(new CategoryRollup.Node(1, null, "餐饮")), List.of(
                new CategoryRollup.Fact(1L, new BigDecimal("-60.00")),
                new CategoryRollup.Fact(null, new BigDecimal("-40.00"))));
        assertEquals(new BigDecimal("-100.00"), tree.net());
        assertEquals("-60.00", StatisticsShare.of(tree.roots().get(0).totals().amount(), tree.net()));
        assertEquals("-40.00", StatisticsShare.of(tree.uncategorized().amount(), tree.net()));
    }

    @Test
    void mixedSignsUseTheSignedNet() {
        CategoryRollup.Tree tree = CategoryRollup.rollup(List.of(
                new CategoryRollup.Node(1, null, "工资"),
                new CategoryRollup.Node(2, null, "退款")), List.of(
                new CategoryRollup.Fact(1L, new BigDecimal("100.00")),
                new CategoryRollup.Fact(2L, new BigDecimal("-20.00"))));
        assertEquals(new BigDecimal("80.00"), tree.net());
        assertEquals("125.00", StatisticsShare.of(tree.roots().get(0).totals().amount(), tree.net()));
        assertEquals("-25.00", StatisticsShare.of(tree.roots().get(1).totals().amount(), tree.net()));
    }

    @Test
    void zeroNetHasNoShare() {
        CategoryRollup.Tree tree = CategoryRollup.rollup(List.of(new CategoryRollup.Node(1, null, "餐饮")), List.of(
                new CategoryRollup.Fact(1L, new BigDecimal("20.00")),
                new CategoryRollup.Fact(1L, new BigDecimal("-20.00"))));
        assertEquals(0, tree.net().signum());
        assertNull(StatisticsShare.of(tree.roots().get(0).totals().amount(), tree.net()));
        assertEquals(2, tree.roots().get(0).totals().transactionCount());
    }

    private static String shareSum(CategoryRollup.Tree tree) {
        BigDecimal sum = StatisticsShare.of(tree.roots().get(0).totals().amount(), tree.net()) == null
                ? BigDecimal.ZERO
                : new BigDecimal(StatisticsShare.of(tree.roots().get(0).totals().amount(), tree.net()))
                .add(new BigDecimal(StatisticsShare.of(tree.roots().get(1).totals().amount(), tree.net())))
                .add(new BigDecimal(StatisticsShare.of(tree.uncategorized().amount(), tree.net())));
        return sum.toPlainString();
    }
}
