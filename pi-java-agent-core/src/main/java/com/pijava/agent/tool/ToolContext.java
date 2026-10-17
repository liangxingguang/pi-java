package com.pijava.agent.tool;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Filesystem and shell context required by built-in tools.
 * Injected by AgentHarness, resolved per-turn from harness configuration.
 *
 * <p>Instances are <b>effectively immutable</b> (record-style fields + final
 * executor/fs references). {@link ShellExecutor} and {@link FileSystem}
 * implementations must document their own thread-safety guarantees.</p>
 *
 * <p>Aligned with pi's {@code ExecutionToolContext} and {@code ExecutionEnv}.
 * The image policy (B85, 批 9) merges pi's per-construction {@code ReadToolOptions}
 * and per-execution {@code ctx.model}; it is a late-bound supplier because the
 * context is built once per session while the current model can cycle.</p>
 */
public class ToolContext {

    private final String cwd;
    private final Map<String, String> env;
    private final ShellExecutor shell;
    private final FileSystem fs;
    private final Supplier<ImagePolicy> imagePolicy;
    private final Supplier<SessionEnvironment> sessionEnvironment;

    /** Create a tool execution context (env is defensively copied). */
    public ToolContext(String cwd, Map<String, String> env,
                       ShellExecutor shell, FileSystem fs) {
        this(cwd, env, shell, fs, ImagePolicy::defaults);
    }

    /** Create a tool execution context with a late-bound image policy (B85). */
    public ToolContext(String cwd, Map<String, String> env,
                       ShellExecutor shell, FileSystem fs,
                       Supplier<ImagePolicy> imagePolicy) {
        this(cwd, env, shell, fs, imagePolicy, () -> null);
    }

    private ToolContext(String cwd, Map<String, String> env,
                        ShellExecutor shell, FileSystem fs,
                        Supplier<ImagePolicy> imagePolicy,
                        Supplier<SessionEnvironment> sessionEnvironment) {
        this.cwd = cwd;
        this.env = Map.copyOf(env);
        this.shell = shell;
        this.fs = fs;
        this.imagePolicy = imagePolicy != null ? imagePolicy : ImagePolicy::defaults;
        this.sessionEnvironment = sessionEnvironment != null ? sessionEnvironment : () -> null;
    }

    /** Current working directory. */
    public String cwd() { return cwd; }

    /** Environment variables (merged with inherited env). */
    public Map<String, String> env() { return env; }

    /** Shell executor for bash tool. */
    public ShellExecutor shell() { return shell; }

    /** Filesystem abstraction for read/write tools. */
    public FileSystem fs() { return fs; }

    /**
     * 当前图片入模策略（{@code autoResize} 总闸 ＋ 当前模型 resize 档 ＋ 视觉位），
     * 每次调用现读（设置文件改动 / setModel 后下一次工具执行即生效）。
     */
    public ImagePolicy imagePolicy() { return imagePolicy.get(); }

    /**
     * 复制本上下文的 cwd/env/shell/fs，换上图片策略晚绑定口
     * （coding-agent 装配时用，见 docs/18 §4.3/§4.5）。
     */
    public ToolContext withImagePolicy(Supplier<ImagePolicy> policy) {
        return new ToolContext(cwd, env, shell, fs, policy, sessionEnvironment);
    }

    /**
     * 当前会话身份（B242 的 PI_* 注入载荷；pi bash.ts:199-208 的 ctx 三面），
     * 每次调用现读；未装配 ⇒ {@code null} ≙ pi 的 ctx 缺席（条件不设）。
     */
    public SessionEnvironment sessionEnvironment() { return sessionEnvironment.get(); }

    /**
     * 复制本上下文，换上会话身份晚绑定口（coding-agent 装配时用，docs/24 §8.3）。
     */
    public ToolContext withSessionEnvironment(Supplier<SessionEnvironment> supplier) {
        return new ToolContext(cwd, env, shell, fs, imagePolicy, supplier);
    }
}
