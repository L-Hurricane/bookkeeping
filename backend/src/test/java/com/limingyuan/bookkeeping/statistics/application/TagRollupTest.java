package com.limingyuan.bookkeeping.statistics.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TagRollupTest {
    @Test
    void oneTransactionOnAParentAndChildCountsOnceInTheParentSubtree() {
        List<TagRollup.Node> nodes = List.of(
                new TagRollup.Node(1, null, "旅行"),
                new TagRollup.Node(2, 1L, "北京"));
        TagRollup.Tree tree = TagRollup.rollup(nodes, List.of(
                new TagRollup.Fact(10, new BigDecimal("-100.00"), List.of(1L, 2L))));
        TagRollup.NodeView travel = tree.roots().get(0);
        assertEquals(1, travel.transactionCount());
        assertEquals(new BigDecimal("-100.00"), travel.amount());
        assertEquals(1, travel.children().get(0).transactionCount());
        assertEquals(0, tree.untagged().transactionCount());
    }

    @Test
    void overlappingTagsCanEachExceedTheWholeNet() {
        List<TagRollup.Node> nodes = List.of(
                new TagRollup.Node(1, null, "旅行"),
                new TagRollup.Node(2, null, "家庭"));
        TagRollup.Tree tree = TagRollup.rollup(nodes, List.of(
                new TagRollup.Fact(10, new BigDecimal("-100.00"), List.of(1L, 2L))));
        assertEquals(new BigDecimal("-100.00"), tree.net());
        assertEquals("-100.00", StatisticsShare.of(tree.roots().get(0).amount(), tree.net()));
        assertEquals("-100.00", StatisticsShare.of(tree.roots().get(1).amount(), tree.net()));
    }

    @Test
    void aTransactionWithoutACurrentTagIsUntagged() {
        TagRollup.Tree tree = TagRollup.rollup(List.of(new TagRollup.Node(1, null, "旅行")), List.of(
                new TagRollup.Fact(10, new BigDecimal("-30.00"), List.of())));
        assertEquals(0, tree.roots().size());
        assertEquals(1, tree.untagged().transactionCount());
        assertEquals(new BigDecimal("-30.00"), tree.untagged().amount());
    }
}
