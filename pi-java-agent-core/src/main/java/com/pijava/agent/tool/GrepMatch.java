package com.pijava.agent.tool;

/** Grep match result. */
public record GrepMatch(
    String file,
    int line,
    String content
) {}
