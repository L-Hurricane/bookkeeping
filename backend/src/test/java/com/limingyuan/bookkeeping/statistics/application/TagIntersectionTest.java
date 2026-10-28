package com.limingyuan.bookkeeping.statistics.application;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TagIntersectionTest {
    @Test void oneTransactionMatchesEveryRequiredSubtreeOnce() {
        Set<Long> travel = Set.of(1L, 2L, 3L);
        Set<Long> child = Set.of(2L, 3L);
        Set<Long> wife = Set.of(4L);
        assertThat(TagIntersection.matchesAll(Set.of(1L), List.of(travel))).isTrue();
        assertThat(TagIntersection.matchesAll(Set.of(2L), List.of(travel, child))).isTrue();
        assertThat(TagIntersection.matchesAll(Set.of(1L, 2L), List.of(travel, child))).isTrue();
        assertThat(TagIntersection.matchesAll(Set.of(1L), List.of(travel, child))).isFalse();
        assertThat(TagIntersection.matchesAll(Set.of(2L, 4L), List.of(travel, child, wife))).isTrue();
        assertThat(TagIntersection.matchesAll(Set.of(4L), List.of(travel, wife))).isFalse();
        assertThat(TagIntersection.matchesAll(Set.of(), List.of(travel))).isFalse();
    }
}
