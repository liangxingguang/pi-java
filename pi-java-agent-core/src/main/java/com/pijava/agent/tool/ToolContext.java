package com.pijava.agent.tool;

import java.util.Map;

/**
 * Filesystem and shell context required by built-in tools.
 * Injected by AgentHarness, resolved per-turn from harness configuration.
 *
 * <p>Instances are <b>effectively immutable</b> (record-style fields + final
 * executor/fs references). {@link ShellExecutor} and {@link FileSystem}
 * implementations must document their own thread-safety guarantees.</p>
 *
 * <p>Aligned with pi's {@code ExecutionToolContext} and {@code ExecutionEnv}.
 */
public class ToolContext {

    private final String cwd;
    private final Map<String, String> env;
    private final ShellExecutor shell;
    private final FileSystem fs;

    public ToolContext(String cwd, Map<String, String> env,
                       ShellExecutor shell, FileSystem fs) {
        this.cwd = cwd;
        this.env = Map.copyOf(env);
        this.shell = shell;
        this.fs = fs;
    }

    /** Current working directory. */
    public String cwd() { return cwd; }

    /** Environment variables (merged with inherited env). */
    public Map<String, String> env() { return env; }

    /** Shell executor for bash tool. */
    public ShellExecutor shell() { return shell; }

    /** Filesystem abstraction for read/write tools. */
    public FileSystem fs() { return fs; }
}
