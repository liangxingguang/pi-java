package com.pijava.agent.tool;
import com.pijava.ai.AbortSignal;

import java.util.Map;
import java.util.OptionalLong;

public record ShellOptions(
    String cwd,
    Map<String, String> env,
    boolean inheritEnv,
    /** pi {@code resolveTimeoutMs}（bash.ts:27-37）：毫秒粒度，{@code empty} ＝ 无超时。 */
    OptionalLong timeoutMillis,
    AbortSignal signal,
    /**
     * 执行中接收输出增量的汇（包⑧，{@code 原 docs/35}）；{@code null} ＝ 不订阅
     * （行为与加这个组件之前逐字相同）。
     *
     * <p>⚠️ 放在这里而不是给 {@code ShellExecutor.execute} 加参数：实现者只有
     * {@code DefaultShellExecutor} 一个、而 {@code ShellOptions} 已经是「这次执行的
     * 全部选项」的载体（{@code AbortSignal} 同样在此）⇒ 加组件只动 3 处构造点，
     * 接口与调用点都不动。</p>
     */
    ShellOutputSink outputSink,
    /**
     * 继承环境后、{@link #env} 并入**之前**要删的键（pi 的 delete-then-set，
     * bash.ts:194-198 的 {@code delete env.PI_*} ×5）—— 防继承环境污染/伪造
     * （B242）。默认空集。
     */
    java.util.Set<String> envRemovals
) {
    /** Defensively copies {@code env} / {@code envRemovals}. */
    public ShellOptions {
        env = Map.copyOf(env);
        envRemovals = envRemovals == null ? java.util.Set.of() : java.util.Set.copyOf(envRemovals);
    }

    /** 不带输出汇的选项（既有调用点的默认形状）。 */
    public ShellOptions(String cwd, Map<String, String> env, boolean inheritEnv,
                        OptionalLong timeoutMillis, AbortSignal signal) {
        this(cwd, env, inheritEnv, timeoutMillis, signal, null);
    }

    /** 不带删键集的选项（既有调用点的默认形状）。 */
    public ShellOptions(String cwd, Map<String, String> env, boolean inheritEnv,
                        OptionalLong timeoutMillis, AbortSignal signal,
                        ShellOutputSink outputSink) {
        this(cwd, env, inheritEnv, timeoutMillis, signal, outputSink, null);
    }
}
