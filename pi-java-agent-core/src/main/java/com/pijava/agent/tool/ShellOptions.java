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
    ShellOutputSink outputSink
) {
    /** Defensively copies {@code env}. */
    public ShellOptions {
        env = Map.copyOf(env);
    }

    /** 不带输出汇的选项（既有调用点的默认形状）。 */
    public ShellOptions(String cwd, Map<String, String> env, boolean inheritEnv,
                        OptionalLong timeoutMillis, AbortSignal signal) {
        this(cwd, env, inheritEnv, timeoutMillis, signal, null);
    }
}
