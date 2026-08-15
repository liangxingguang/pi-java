package com.pijava.agent.session.jsonl;

import java.nio.file.Path;
import java.util.List;

/**
 * File-system boundary for the JSONL backend (aligned with pi
 * {@code JsonlSessionRepoFileSystem}). Kept as an interface for fault-injection
 * testing of torn-tail and rename atomicity; production uses
 * {@link DefaultJsonlFileSystem}.
 */
public interface JsonlSessionRepoFileSystem {

    /** Absolute (normalized) representation of {@code path}. */
    String absolutePath(String path);

    /** Join path parts with the platform separator. */
    String joinPath(List<String> parts);

    /** Read the entire file as UTF-8 text. */
    String readTextFile(Path path);

    /** Read up to {@code maxLines} lines. */
    List<String> readTextLines(Path path, int maxLines);

    /** Write (overwrite) UTF-8 content. */
    void writeFile(Path path, String content);

    /** Append UTF-8 content. */
    void appendFile(Path path, String content);

    /** Rename {@code from} to {@code to} (atomic where supported). */
    void renameFile(Path from, Path to);

    /** Modification time in epoch ms. */
    long fileInfoMtimeMs(Path path);

    /** List a directory's entries. */
    List<DirEntry> listDir(Path path);

    /** Whether the path exists. */
    boolean exists(Path path);

    /** Create a directory. */
    void createDir(Path path, boolean recursive);

    /** Remove a file or (with {@code force}) a non-empty directory. */
    void remove(Path path, boolean force);

    /** A directory entry. {@code kind} is {@code "file"} or {@code "directory"}. */
    record DirEntry(String name, Path path, String kind, long mtimeMs) {}
}