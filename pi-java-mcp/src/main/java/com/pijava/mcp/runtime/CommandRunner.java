package com.pijava.mcp.runtime;

import org.jspecify.annotations.Nullable;

/**
 * Runs one shell command for a {@code !cmd} config value
 * (pi {@code resolve-config-value.ts:198-206}).
 *
 * <p>pi first tries the shell configured in settings.json and only falls back to a default
 * shell; a host that has those settings injects its own runner here.</p>
 */
@FunctionalInterface
public interface CommandRunner {

    /**
     * Run the command and return its standard output.
     *
     * @param command the shell command, without the leading {@code "!"}
     * @return the command's standard output, or {@code null} when it could not be run
     */
    @Nullable String run(String command);
}
