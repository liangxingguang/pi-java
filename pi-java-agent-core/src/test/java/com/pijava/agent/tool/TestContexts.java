package com.pijava.agent.tool;

import java.nio.file.Path;
import java.util.Map;

/**
 * Shared factory for {@link ToolContext} used across built-in tool tests.
 */
public final class TestContexts {

    private TestContexts() {}

    /** Create a ToolContext rooted at the given directory. */
    public static ToolContext at(Path cwd) {
        return new ToolContext(
            cwd.toString(),
            Map.of(),
            new DefaultShellExecutor(),
            new DefaultFileSystem()
        );
    }
}
