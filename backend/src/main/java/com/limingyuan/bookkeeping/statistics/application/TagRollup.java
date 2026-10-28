package com.limingyuan.bookkeeping.statistics.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A tag total is the deduplicated union of transactions linked to that tag or a descendant. */
public final class TagRollup {
    private TagRollup() { }

    public record Node(long id, Long parentId, String name) { }
    public record Fact(long transactionId, BigDecimal signedAmount, List<Long> tagIds) { }
    public record NodeView(long id, String name, int transactionCount, BigDecimal amount, List<NodeView> children) { }
    public record Tree(List<NodeView> roots, CategoryRollup.Totals untagged, BigDecimal net) { }

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
        Map<Long, Fact> byTransaction = new LinkedHashMap<>();
        Map<Long, Set<Long>> explicit = new HashMap<>();
        CategoryRollup.Totals untagged = CategoryRollup.Totals.zero();
        BigDecimal net = new BigDecimal("0.00");
        for (Fact fact : facts) {
            byTransaction.put(fact.transactionId(), fact);
            net = net.add(fact.signedAmount());
            List<Long> known = fact.tagIds().stream().filter(byId::containsKey).distinct().toList();
            if (known.isEmpty()) {
                untagged = untagged.plus(fact.signedAmount());
            } else {
                for (long tagId : known) explicit.computeIfAbsent(tagId, ignored -> new LinkedHashSet<>()).add(fact.transactionId());
            }
        }
        Map<Long, Set<Long>> memo = new HashMap<>();
        List<NodeView> views = new ArrayList<>();
        for (Node root : roots) {
            NodeView view = build(root, children, explicit, byTransaction, memo, new HashSet<>());
            if (view != null) views.add(view);
        }
        return new Tree(List.copyOf(views), untagged, net);
    }

    public static Set<Long> subtreeIds(List<Node> nodes, long rootId) {
        return CategoryRollup.subtreeIds(nodes.stream().map(node -> new CategoryRollup.Node(node.id(), node.parentId(), node.name())).toList(), rootId);
    }

    private static NodeView build(Node node, Map<Long, List<Node>> children, Map<Long, Set<Long>> explicit,
            Map<Long, Fact> facts, Map<Long, Set<Long>> memo, Set<Long> visiting) {
        if (!visiting.add(node.id())) return null;
        Set<Long> ids = new LinkedHashSet<>(explicit.getOrDefault(node.id(), Set.of()));
        List<NodeView> childViews = new ArrayList<>();
        for (Node child : children.getOrDefault(node.id(), List.of())) {
            NodeView childView = build(child, children, explicit, facts, memo, visiting);
            if (childView != null) childViews.add(childView);
            ids.addAll(transactions(child.id(), children, explicit, memo, new HashSet<>()));
        }
        visiting.remove(node.id());
        memo.put(node.id(), ids);
        if (ids.isEmpty()) return null;
        BigDecimal amount = new BigDecimal("0.00");
        for (long id : ids) amount = amount.add(facts.get(id).signedAmount());
        return new NodeView(node.id(), node.name(), ids.size(), amount, List.copyOf(childViews));
    }

    private static Set<Long> transactions(long id, Map<Long, List<Node>> children, Map<Long, Set<Long>> explicit,
            Map<Long, Set<Long>> memo, Set<Long> visiting) {
        Set<Long> cached = memo.get(id);
        if (cached != null) return cached;
        if (!visiting.add(id)) return Set.of();
        Set<Long> ids = new LinkedHashSet<>(explicit.getOrDefault(id, Set.of()));
        for (Node child : children.getOrDefault(id, List.of())) {
            ids.addAll(transactions(child.id(), children, explicit, memo, visiting));
        }
        visiting.remove(id);
        memo.put(id, ids);
        return ids;
    }
}
