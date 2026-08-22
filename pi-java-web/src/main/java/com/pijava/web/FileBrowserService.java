package com.pijava.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import com.pijava.agent.tool.DefaultFileSystem;
import com.pijava.agent.tool.FileSystem;
import com.pijava.web.WebProtocol.DirEntry;

/**
 * 文件浏览器（代码调试，Phase 7 Stage B）。工作区根 = 会话 cwd；
 * 所有路径经 {@link #resolve} 约束在工作区内（防目录穿越）。
 */
final class FileBrowserService {

    /** 单文件最大读取字节（超出截断，避免大文件压垮前端）。 */
    private static final long MAX_FILE_BYTES = 512 * 1024;

    private final Path root;
    private final FileSystem fs = new DefaultFileSystem();

    FileBrowserService(Path root) {
        this.root = root.normalize().toAbsolutePath();
    }

    /** 目录列表结果。 */
    record Listing(String path, List<DirEntry> entries) {
    }

    /** 文件读取结果。 */
    record Content(String path, String content, boolean truncated) {
    }

    /** 列目录（非递归；目录在前，名称升序）。 */
    Listing listDir(String rawPath) {
        var dir = resolve(rawPath);
        if (!java.nio.file.Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Not a directory: " + rawPath);
        }
        try {
            var entries = fs.listDir(dir.toString(), false).stream()
                .map(f -> new DirEntry(fileName(f.path()), f.kind(),
                    f.size(), f.modifiedAt().toEpochMilli()))
                .sorted(Comparator.comparing((DirEntry e) -> "dir".equals(e.kind()) ? 0 : 1)
                    .thenComparing(e -> e.name().toLowerCase()))
                .toList();
            return new Listing(dir.toString(), entries);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Failed to list directory: " + e.getMessage());
        }
    }

    /** 读文件（UTF-8；超 {@link #MAX_FILE_BYTES} 截断）。 */
    Content readFile(String rawPath) {
        var file = resolve(rawPath);
        if (!java.nio.file.Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Not a file: " + rawPath);
        }
        try {
            byte[] data = fs.readBinary(file.toString());
            boolean truncated = data.length > MAX_FILE_BYTES;
            int length = (int) Math.min(data.length, MAX_FILE_BYTES);
            String text = new String(data, 0, length, StandardCharsets.UTF_8);
            return new Content(file.toString(), text, truncated);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Failed to read file: " + e.getMessage());
        }
    }

    /** 解析路径并约束在工作区内（空/根 → 工作区根；拒绝越界）。 */
    private Path resolve(String rawPath) {
        String p = rawPath == null || rawPath.isBlank() ? "" : rawPath;
        String stripped = p.startsWith("/") ? p.substring(1) : p;
        Path norm = root.resolve(stripped).normalize().toAbsolutePath();
        if (!norm.startsWith(root)) {
            throw new IllegalArgumentException("Path outside workspace: " + rawPath);
        }
        return norm;
    }

    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
