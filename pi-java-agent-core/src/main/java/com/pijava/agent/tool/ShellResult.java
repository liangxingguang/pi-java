package com.pijava.agent.tool;

public record ShellResult(
    String output,          // stdout + stderr combined
    int exitCode,
    boolean timedOut,
    boolean truncated,      // output exceeded max lines/bytes
    long outputLines,
    long outputBytes
) {}
