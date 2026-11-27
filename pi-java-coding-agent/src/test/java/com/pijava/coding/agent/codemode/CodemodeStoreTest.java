package com.pijava.coding.agent.codemode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Branch replay tests for {@code codemode-store} entries. */
class CodemodeStoreTest {

    @Test
    void replaysSetAndDeleteInRootToLeafOrder() {
        List<Entry> branch = List.of(
            custom("1", Map.of("set", Map.of("a", 1, "b", 2), "delete", List.of())),
            custom("2", Map.of("set", Map.of("c", 3), "delete", List.of("a"))),
            custom("3", Map.of("set", Map.of("b", 4), "delete", List.of("c"))));
        assertThat(CodemodeStore.read(branch)).containsExactly(Map.entry("b", 4));
    }

    @Test
    void ignoresMalformedAndForeignEntries() {
        List<Entry> branch = List.of(
            new Entry.Custom("x", 1, null, Instant.EPOCH, "other", Map.of("set", Map.of("a", 1))),
            custom("y", Map.of("set", "not-a-map", "delete", List.of("a"))),
            custom("z", Map.of("set", Map.of("ok", true))));
        assertThat(CodemodeStore.read(branch)).containsExactly(Map.entry("ok", true));
    }

    private static Entry custom(String id, Map<String, Object> data) {
        long sequence = id.chars().asLongStream().reduce(0, (left, right) -> left * 31 + right);
        return new Entry.Custom(id, sequence, null,
            Instant.EPOCH, CodemodeStore.ENTRY_TYPE, data);
    }
}
