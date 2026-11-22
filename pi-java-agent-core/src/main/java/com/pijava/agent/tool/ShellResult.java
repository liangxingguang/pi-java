package com.pijava.agent.tool;

/**
 * Result of one shell execution.
 *
 * @param timeoutShellExit when the timeout fired but the shell itself had already exited
 *        (its background processes kept the pipes open), the shell's exit code and how many
 *        seconds after the start it exited; otherwise {@code null}
 */
public record ShellResult(
    String output,          // stdout + stderr combined
    int exitCode,
    boolean timedOut,
    boolean truncated,      // output exceeded max lines/bytes
    long outputLines,
    long outputBytes,
    TimeoutShellExit timeoutShellExit
) {
    /** The shell's own exit, observed before the timeout deadline（pi {@code ShellTimeoutError.shellExit}）。 */
    public record TimeoutShellExit(int exitCode, double afterSeconds) {}

    /** Result without the shell-exit detail (the common case). */
    public ShellResult(String output, int exitCode, boolean timedOut, boolean truncated,
            long outputLines, long outputBytes) {
        this(output, exitCode, timedOut, truncated, outputLines, outputBytes, null);
    }
}
