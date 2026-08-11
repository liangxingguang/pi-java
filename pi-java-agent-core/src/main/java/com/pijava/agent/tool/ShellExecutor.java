package com.pijava.agent.tool;

/**
 * Shell command executor — wraps {@code ProcessBuilder}.
 * Phase 2b implements {@code DefaultShellExecutor} using Virtual Threads
 * + output capture with truncation.
 */
public interface ShellExecutor {
    /** Execute a command and return captured output. */
    ShellResult execute(String command, ShellOptions options) throws Exception;
}
