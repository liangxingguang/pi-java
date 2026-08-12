package com.pijava.agent.hook;

/** Hook triggered at the start of {@code run()}, before the user entry is written. */
@FunctionalInterface
public interface BeforeRunHook {
    void beforeRun(RunContext ctx);
}
