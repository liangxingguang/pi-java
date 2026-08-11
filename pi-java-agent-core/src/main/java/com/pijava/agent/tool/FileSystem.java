package com.pijava.agent.tool;

import java.io.IOException;
import java.util.List;

/**
 * Filesystem abstraction for read/write tools.
 * Implementations: real filesystem (production) or in-memory (tests).
 *
 * <p>{@code glob()} and {@code grep()} are NOT on this interface —
 * those are tool-level operations, not filesystem primitives.
 * GrepTool and GlobTool use {@code listDir()} + own logic.</p>
 */
public interface FileSystem {
    /** Read a text file. Returns lines as a list. */
    List<String> readLines(String path, long offset, long limit) throws IOException;

    /** Read a binary file. Returns raw bytes. */
    byte[] readBinary(String path) throws IOException;

    /** Write content to a file, creating parent directories. */
    void writeFile(String path, String content) throws IOException;

    /** Get file metadata. */
    FileInfo fileInfo(String path) throws IOException;

    /** Resolve a path (relative → absolute, symlink → target). */
    String resolvePath(String path);

    /** List directory contents. */
    List<FileInfo> listDir(String path, boolean recursive) throws IOException;
}
