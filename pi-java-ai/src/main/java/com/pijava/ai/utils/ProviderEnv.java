package com.pijava.ai.utils;

import java.util.Map;

/**
 * pi {@code utils/provider-env.ts}：provider 作用域环境值的解析。
 *
 * <p>docs/19 #50：先查请求级覆盖，再回落进程环境。pi 的 Bun 沙箱兜底
 * （{@code /proc/self/environ}）不移植 —— JVM 的 {@code System.getenv} 就是进程环境来源。</p>
 */
public final class ProviderEnv {

    private ProviderEnv() {
    }

    /** Resolve {@code name} from scoped overrides, then process env; empty ⇒ absent. */
    public static String value(String name, Map<String, String> env) {
        if (env != null) {
            var scoped = env.get(name);
            // pi 用 `||`：空串为假 ⇒ 回落进程环境；空白串为真 ⇒ 不回落。
            if (scoped != null && !scoped.isEmpty()) {
                return scoped;
            }
        }
        var process = System.getenv(name);
        return process == null || process.isEmpty() ? null : process;
    }
}
