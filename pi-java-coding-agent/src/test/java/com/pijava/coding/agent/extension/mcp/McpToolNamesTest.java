package com.pijava.coding.agent.extension.mcp;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code createMcpToolName}（{@code tools.ts:88-97}）。
 */
class McpToolNamesTest {

    @Test
    void buildsTheNamespacedName() {
        assertThat(McpToolNames.create("files", "echo")).isEqualTo("mcp__files__echo");
    }

    @Test
    void replacesEverythingButLettersDigitsAndUnderscore() {
        // Note this is NOT McpServerConfigs.namespace, which only turns `-` into `_`.
        assertThat(McpToolNames.create("my-server", "read-file"))
                .isEqualTo("mcp__my_server__read_file");
        assertThat(McpToolNames.create("a.b", "c/d e")).isEqualTo("mcp__a_b__c_d_e");
    }

    @Test
    void keepsNamesUpToTheLengthLimit() {
        var tool = "t".repeat(64 - "mcp__s__".length());
        var name = McpToolNames.create("s", tool);
        assertThat(name).hasSize(64).isEqualTo("mcp__s__" + tool);
    }

    @Test
    void shortensNamesPastTheLengthLimitWithAHashSuffix() {
        var tool = "t".repeat(64);
        var name = McpToolNames.create("s", tool);
        assertThat(name).hasSize(64);
        assertThat(name).startsWith("mcp__s__" + "t".repeat(64 - "mcp__s__".length() - 9));
        assertThat(name).matches(".{55}_[0-9a-f]{8}");
    }

    @Test
    void aTakenNameGetsTheHashSuffixEvenWhenItIsShort() {
        // JS slice() would clamp; Java substring would throw — the length is the min of the two.
        var name = McpToolNames.create("s", "echo", Set.of("mcp__s__echo")::contains);
        assertThat(name).isEqualTo("mcp__s__echo_" + suffixOf("s", "echo"));
        assertThat(name).hasSize("mcp__s__echo".length() + 1 + 8);
    }

    @Test
    void theHashSeparatesServerAndToolWithANul() {
        // With a printable separator the pairs ("a","bc") and ("a b","c") would hash the same.
        assertThat(suffixOf("a", "bc")).isNotEqualTo(suffixOf("a b", "c"));
    }

    /** The hash suffix, observed by forcing the taken path. */
    private static String suffixOf(String server, String tool) {
        var name = McpToolNames.create(server, tool, any -> true);
        return name.substring(name.length() - 8);
    }
}
