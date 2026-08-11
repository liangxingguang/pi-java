package com.pijava.agent.tool;

import java.time.Instant;

/** File metadata. */
public record FileInfo(
    String path,
    String kind,       // "file" | "dir" | "symlink"
    long size,
    Instant modifiedAt
) {}
