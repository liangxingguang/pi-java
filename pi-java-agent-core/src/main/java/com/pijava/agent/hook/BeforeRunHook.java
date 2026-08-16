package com.pijava.agent.hook;

/** Hook triggered at the start of {@code run()}, before the user entry is written. */
@FunctionalInterface
public interface BeforeRunHook {
    /** Invoked at the start of a run, before the user entry is written. */
    void beforeRun(RunContext ctx);
}
