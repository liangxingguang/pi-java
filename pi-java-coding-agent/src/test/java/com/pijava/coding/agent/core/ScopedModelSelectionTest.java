package com.pijava.coding.agent.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedModelSelectionTest {

    @Test
    void toggleFromAllCreatesExplicitListWithoutTarget() {
        var result = ScopedModelSelection.toggle(
            new EnabledModelSet.All(), List.of("a", "b", "c"), "b");

        assertThat(result).isEqualTo(new EnabledModelSet.Explicit(List.of("a", "c")));
    }

    @Test
    void explicitEmptyIsNotAll() {
        var empty = new EnabledModelSet.Explicit(List.of());

        assertThat(ScopedModelSelection.isEnabled(empty, "a")).isFalse();
        assertThat(ScopedModelSelection.isEnabled(new EnabledModelSet.All(), "a")).isTrue();
    }

    @Test
    void toggleExplicitAddsRemovesAndNormalizesOnlyCompleteCoverage() {
        var all = List.of("a", "b");

        assertThat(ScopedModelSelection.toggle(
            new EnabledModelSet.Explicit(List.of("a")), all, "b"))
            .isEqualTo(new EnabledModelSet.All());
        assertThat(ScopedModelSelection.toggle(
            new EnabledModelSet.Explicit(List.of("a", "old")), all, "b"))
            .isEqualTo(new EnabledModelSet.Explicit(List.of("a", "old", "b")));
        assertThat(ScopedModelSelection.toggle(
            new EnabledModelSet.Explicit(List.of("a", "b")), all, "a"))
            .isEqualTo(new EnabledModelSet.Explicit(List.of("b")));
    }

    @Test
    void enableAndClearAllRespectFilteredTargets() {
        var all = List.of("a", "b", "c");

        assertThat(ScopedModelSelection.enableAll(
            new EnabledModelSet.Explicit(List.of("a")), all, List.of("b")))
            .isEqualTo(new EnabledModelSet.Explicit(List.of("a", "b")));
        assertThat(ScopedModelSelection.enableAll(new EnabledModelSet.All(), all, null))
            .isEqualTo(new EnabledModelSet.All());
        assertThat(ScopedModelSelection.clearAll(new EnabledModelSet.All(), all, null))
            .isEqualTo(new EnabledModelSet.Explicit(List.of()));
        assertThat(ScopedModelSelection.clearAll(new EnabledModelSet.All(), all, List.of("b")))
            .isEqualTo(new EnabledModelSet.Explicit(List.of("a", "c")));
        assertThat(ScopedModelSelection.clearAll(
            new EnabledModelSet.Explicit(List.of("a", "old")), all, null))
            .isEqualTo(new EnabledModelSet.Explicit(List.of()));
    }

    @Test
    void moveReordersOnlyExplicitEnabledIdsWithinBounds() {
        var explicit = new EnabledModelSet.Explicit(List.of("a", "b", "old"));

        assertThat(ScopedModelSelection.move(explicit, "a", 1))
            .isEqualTo(new EnabledModelSet.Explicit(List.of("b", "a", "old")));
        assertThat(ScopedModelSelection.move(explicit, "a", -1)).isEqualTo(explicit);
        assertThat(ScopedModelSelection.move(explicit, "missing", 1)).isEqualTo(explicit);
        assertThat(ScopedModelSelection.move(new EnabledModelSet.All(), "a", 1))
            .isEqualTo(new EnabledModelSet.All());
    }

    @Test
    void sortedIdsKeepsExplicitOrderAndUnavailableIds() {
        assertThat(ScopedModelSelection.sortedIds(
            new EnabledModelSet.Explicit(List.of("old", "a")), List.of("a", "b")))
            .containsExactly("old", "a", "b");
        assertThat(ScopedModelSelection.sortedIds(new EnabledModelSet.All(), List.of("a", "b")))
            .containsExactly("a", "b");
    }
}
