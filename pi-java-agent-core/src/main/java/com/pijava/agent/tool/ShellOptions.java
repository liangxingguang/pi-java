package com.pijava.agent.tool;

import java.util.Map;
import java.util.OptionalLong;

public record ShellOptions(
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
