package com.pijava.agent.tool;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Filesystem abstraction for read/write tools.
 * Implementations: real filesystem (production) or in-memory (tests).
 *
 * <p>{@code glob()} and {@code grep()} are NOT on this interface —
 * those are tool-level operations, not filesystem primitives.
 * GrepTool and FindTool use {@code listDir()} + own logic.</p>
 */
public interface FileSystem {
    /** Read a text file. Returns lines as a list. */
    List<String> readLines(String path, long offset, long limit) throws IOException;

    /** Read a binary file. Returns raw bytes. */
    byte[] readBinary(String path) throws IOException;

    /**
     * Open a file for streaming reads (pi {@code ReadOperations.createReadStream}，read.ts:65）。
     * The default falls back to {@link #readBinary} so custom filesystems need not change; the
     * real filesystem overrides it with a sequential stream.
     */
    default InputStream openRead(String path) throws IOException {
        return new ByteArrayInputStream(readBinary(path));
    }

    /** Write content to a file, creating parent directories. */
    void writeFile(String path, String content) throws IOException;

    /** Get file metadata. */
    FileInfo fileInfo(String path) throws IOException;

    /** Resolve a path (relative → absolute, symlink → target). */
    String resolvePath(String path);

    /** List directory contents. */
    List<FileInfo> listDir(String path, boolean recursive) throws IOException;
}
