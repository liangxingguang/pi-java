package com.pijava.agent.tool;

import java.util.List;

/**
 * Which tools a call may reach (pi {@code ToolDefinition.reachesTools},
 * {@code core/extensions/types.ts:635-648}).
 *
 * <p>For tools that reach other tools (such as {@code codemode}, which calls
 * them, and {@code tool_search}, which loads them): {@link All}, or the
 * {@link Words} of the input that may name them, where a word refers to a tool
 * or namespace whose name it contains, so words may over-match. Extensions that
 * register tools late, such as MCP servers that are still connecting, use it
 * from their {@code tool_call} handler to finish registering those tools before
 * the call runs.</p>
 */
public sealed interface ToolReach {

    /** The call may reach every tool. */
    record All() implements ToolReach {
    }

    /** The call may reach tools whose names contain one of these words. */
    record Words(List<String> words) implements ToolReach {

        /** Defensive copy. */
        public Words {
            words = List.copyOf(words);
        }
    }
}
