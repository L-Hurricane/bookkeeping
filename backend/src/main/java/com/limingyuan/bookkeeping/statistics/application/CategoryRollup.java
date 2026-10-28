package com.limingyuan.bookkeeping.statistics.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Each transaction is counted once on its current category and every ancestor. */
public final class CategoryRollup {
    private CategoryRollup() { }

    public record Node(long id, Long parentId, String name) { }
    public record Fact(Long categoryId, BigDecimal signedAmount) { }
    public record Totals(int transactionCount, BigDecimal amount) {
        public static Totals zero() { return new Totals(0, new BigDecimal("0.00")); }
        public Totals plus(BigDecimal signed) { return new Totals(transactionCount + 1, amount.add(signed)); }
        public Totals plus(Totals other) {
            return new Totals(transactionCount + other.transactionCount, amount.add(other.amount));
        }
    }
    public record NodeView(long id, String name, Totals totals, List<NodeView> children) { }
    public record Tree(List<NodeView> roots, Totals uncategorized, BigDecimal net) { }

    public static Tree rollup(List<Node> nodes, List<Fact> facts) {
        Map<Long, Node> byId = new LinkedHashMap<>();
        for (Node node : nodes) byId.put(node.id(), node);
        Map<Long, List<Node>> children = new HashMap<>();
        List<Node> roots = new ArrayList<>();
        for (Node node : nodes) {
            if (node.parentId() != null && byId.containsKey(node.parentId())) {
                children.computeIfAbsent(node.parentId(), ignored -> new ArrayList<>()).add(node);
            } else {
                roots.add(node);
            }
        }
        Map<Long, Totals> direct = new HashMap<>();
        Totals uncategorized = Totals.zero();
        BigDecimal net = new BigDecimal("0.00");
        for (Fact fact : facts) {
            net = net.add(fact.signedAmount());
            if (fact.categoryId() == null || !byId.containsKey(fact.categoryId())) {
                uncategorized = uncategorized.plus(fact.signedAmount());
            } else {
                direct.merge(fact.categoryId(), Totals.zero().plus(fact.signedAmount()), Totals::plus);
            }
        }
        List<NodeView> views = new ArrayList<>();
        for (Node root : roots) {
            NodeView view = build(root, children, direct, new HashSet<>());
            if (view != null) views.add(view);
        }
        return new Tree(List.copyOf(views), uncategorized, net);
    }

    public static Set<Long> subtreeIds(List<Node> nodes, long rootId) {
        Map<Long, List<Long>> children = new HashMap<>();
        boolean found = false;
        for (Node node : nodes) {
            if (node.id() == rootId) found = true;
            if (node.parentId() != null) children.computeIfAbsent(node.parentId(), ignored -> new ArrayList<>()).add(node.id());
        }
        if (!found) return Set.of();
        Set<Long> ids = new HashSet<>();
        collect(rootId, children, ids, new HashSet<>());
        return ids;
    }

    private static NodeView build(Node node, Map<Long, List<Node>> children, Map<Long, Totals> direct, Set<Long> visiting) {
        if (!visiting.add(node.id())) return null;
        Totals totals = direct.getOrDefault(node.id(), Totals.zero());
        List<NodeView> childViews = new ArrayList<>();
        for (Node child : children.getOrDefault(node.id(), List.of())) {
            NodeView childView = build(child, children, direct, visiting);
            if (childView != null) {
                childViews.add(childView);
                totals = totals.plus(childView.totals());
            }
        }
        visiting.remove(node.id());
        if (totals.transactionCount() == 0) return null;
        return new NodeView(node.id(), node.name(), totals, List.copyOf(childViews));
    }

    private static void collect(long id, Map<Long, List<Long>> children, Set<Long> ids, Set<Long> visiting) {
        if (!visiting.add(id)) return;
        ids.add(id);
        for (long child : children.getOrDefault(id, List.of())) collect(child, children, ids, visiting);
        visiting.remove(id);
    }
}
