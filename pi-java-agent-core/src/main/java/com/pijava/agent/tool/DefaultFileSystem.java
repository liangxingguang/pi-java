package com.pijava.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Real filesystem implementation using Java NIO.
 */
public class DefaultFileSystem implements FileSystem {

    @Override
    public List<String> readLines(String path, long offset, long limit) throws IOException {
        var filePath = Path.of(path);
        var allLines = Files.readAllLines(filePath);
        long start = Math.max(0, offset);
        long end = limit > 0 ? Math.min(allLines.size(), start + limit) : allLines.size();
        return allLines.subList((int) start, (int) end);
    }

    @Override
    public byte[] readBinary(String path) throws IOException {
        return Files.readAllBytes(Path.of(path));
    }

    @Override
    public void writeFile(String path, String content) throws IOException {
        var filePath = Path.of(path);
        var parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(filePath, content);
    }

    @Override
    public FileInfo fileInfo(String path) throws IOException {
        var filePath = Path.of(path);
        var attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
        String kind;
        if (attrs.isSymbolicLink()) {
            kind = "symlink";
        } else if (attrs.isDirectory()) {
            kind = "dir";
        } else {
            kind = "file";
        }
        return new FileInfo(
            filePath.toAbsolutePath().toString(),
            kind,
            attrs.size(),
            attrs.lastModifiedTime().toInstant()
        );
    }

    @Override
    public String resolvePath(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    @Override
    public List<FileInfo> listDir(String path, boolean recursive) throws IOException {
        var result = new ArrayList<FileInfo>();
        var dirPath = Path.of(path);
        if (recursive) {
            try (var stream = Files.walk(dirPath)) {
                stream.filter(p -> !p.equals(dirPath)).forEach(p -> {
                    try {
                        result.add(toFileInfo(p));
                    } catch (IOException ignored) {
                        // skip unreadable files
                    }
                });
            }
        } else {
            try (var stream = Files.list(dirPath)) {
                stream.forEach(p -> {
                    try {
                        result.add(toFileInfo(p));
                    } catch (IOException ignored) {
                        // skip unreadable files
                    }
                });
            }
        }
        return result;
    }

    private FileInfo toFileInfo(Path p) throws IOException {
        var attrs = Files.readAttributes(p, BasicFileAttributes.class);
        String kind = attrs.isDirectory() ? "dir" : "file";
        return new FileInfo(p.toAbsolutePath().toString(), kind, attrs.size(),
            attrs.lastModifiedTime().toInstant());
    }
}
