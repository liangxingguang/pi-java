package com.pijava.agent.tool;

/** Tool execution mode. */
public sealed interface ExecutionMode {
    /** Must execute sequentially (e.g. bash). */
    record Sequential() implements ExecutionMode {}
    /** Can execute concurrently with other parallel tools (e.g. read, grep, ls, glob). */
    record Parallel() implements ExecutionMode {}
}
