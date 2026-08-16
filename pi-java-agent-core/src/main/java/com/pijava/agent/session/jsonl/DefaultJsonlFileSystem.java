package com.pijava.agent.session.jsonl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Real-filesystem implementation of {@link JsonlSessionRepoFileSystem}.
 */
public final class DefaultJsonlFileSystem implements JsonlSessionRepoFileSystem {

    @Override
    public String absolutePath(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    @Override
    public String joinPath(List<String> parts) {
        Path result = Path.of(parts.getFirst());
        for (int i = 1; i < parts.size(); i++) {
            result = result.resolve(parts.get(i));
        }
        return result.toString();
    }

    @Override
    public String readTextFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JsonlFileException("Failed to read " + path, e);
        }
    }

    @Override
    public List<String> readTextLines(Path path, int maxLines) {
        try {
            List<String> lines = new ArrayList<>();
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                while (lines.size() < maxLines && (line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (IOException e) {
            throw new JsonlFileException("Failed to read " + path, e);
        }
    }

    @Override
    public void writeFile(Path path, String content) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new JsonlFileException("Failed to write " + path, e);
        }
    }

    @Override
    public void appendFile(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new JsonlFileException("Failed to append " + path, e);
        }
    }

    @Override
    public void renameFile(Path from, Path to) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new JsonlFileException("Failed to rename " + from + " to " + to, e);
        }
    }

    @Override
    public long fileInfoMtimeMs(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            throw new JsonlFileException("Failed to stat " + path, e);
        }
    }

    @Override
    public List<DirEntry> listDir(Path path) {
        try (var stream = Files.list(path)) {
            return stream.map(p -> new DirEntry(
                p.getFileName().toString(),
                p,
                Files.isDirectory(p) ? "directory" : "file",
                mtimeOrZero(p))).toList();
        } catch (IOException e) {
            throw new JsonlFileException("Failed to list " + path, e);
        }
    }

    @Override
    public boolean exists(Path path) {
        return Files.exists(path);
    }

    @Override
    public void createDir(Path path, boolean recursive) {
        try {
            if (recursive) {
                Files.createDirectories(path);
            } else {
                Files.createDirectory(path);
            }
        } catch (IOException e) {
            throw new JsonlFileException("Failed to create directory " + path, e);
        }
    }

    @Override
    public void remove(Path path, boolean force) {
        try {
            if (force) {
                deleteRecursively(path);
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new JsonlFileException("Failed to remove " + path, e);
        }
    }

    private static long mtimeOrZero(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (var child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
            Files.delete(path);
        } else {
            Files.delete(path);
        }
    }

    /** File-system failure wrapper for the JSONL backend. */
    public static final class JsonlFileException extends RuntimeException {
        public JsonlFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
