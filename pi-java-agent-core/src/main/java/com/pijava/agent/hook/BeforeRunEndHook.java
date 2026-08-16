package com.pijava.agent.hook;

/** Hook triggered at the end of a run (stop/error/length). Can inject final messages. */
@FunctionalInterface
public interface BeforeRunEndHook {
    /** Invoked at the end of a run; may inject final messages. */
    void beforeRunEnd(RunEndContext ctx);
}
