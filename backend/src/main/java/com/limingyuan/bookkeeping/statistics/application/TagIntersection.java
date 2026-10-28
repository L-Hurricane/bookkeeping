package com.limingyuan.bookkeeping.statistics.application;

import java.util.List;
import java.util.Set;

/** A transaction matches only when each required tag subtree contains one of its explicit tags. */
public final class TagIntersection {
    private TagIntersection() { }

    public static boolean matchesAll(Set<Long> explicitTagIds, List<Set<Long>> subtrees) {
        if (subtrees.isEmpty()) return false;
        for (Set<Long> subtree : subtrees) {
            boolean hit = false;
            for (long tagId : explicitTagIds) {
                if (subtree.contains(tagId)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) return false;
        }
        return true;
    }
}
