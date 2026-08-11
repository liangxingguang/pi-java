package com.pijava.agent.tool;

import java.util.Map;
import java.util.OptionalLong;

/**
 * Shell command executor — wraps {@code ProcessBuilder}.
 * Phase 2b implements {@code DefaultShellExecutor} using Virtual Threads
 * + output capture with truncation.
 */
public interface ShellExecutor {
    /** Execute a command and return captured output. */
    ShellResult execute(String command, ShellOptions options) throws Exception;
}

record ShellOptions(
    String cwd,
    Map<String, String> env,
    boolean inheritEnv,
    OptionalLong timeoutSeconds,
    AbortSignal signal
) {
    public ShellOptions {
        env = Map.copyOf(env);
    }
}

record ShellResult(
    String output,          // stdout + stderr combined
    int exitCode,
    boolean timedOut,
    boolean truncated,      // output exceeded max lines/bytes
    long outputLines,
    long outputBytes
) {}
