package com.pijava.agent.harness;

/**
 * pi {@code agent/src/stream-fn.ts:1-20} 逐字对位：进程级缺省 {@link StreamFn} 兜底。
 *
 * <p>pi 的安装点是 {@code coding-agent/src/core/sdk.ts:43-46} 的模块顶层副作用
 * （{@code setDefaultStreamFn(streamSimple)}，bundle 后随模块加载必然执行）；取用点是
 * {@code agent.ts:236} 的 {@code ?? getDefaultStreamFn()} 与 {@code agent-loop.ts} 两处 ——
 * 给省略 streamFn 的低层调用方（pre-0.81 扩展构造 Agent / 直调 loop）一个回落。</p>
 */
public final class DefaultStreamFn {

    private static volatile StreamFn defaultStreamFn;

    private DefaultStreamFn() {}

    /** pi {@code setDefaultStreamFn}（:11-13）；{@code null} ≙ undefined（卸载）。 */
    public static void set(StreamFn streamFn) {
        defaultStreamFn = streamFn;
    }

    /** pi {@code getDefaultStreamFn}（:15-19），错文逐字。 */
    public static StreamFn get() {
        StreamFn fn = defaultStreamFn;
        if (fn == null) {
            throw new IllegalStateException(
                "No default stream function configured. "
                    + "Pass streamFn explicitly or call setDefaultStreamFn().");
        }
        return fn;
    }
}
