package com.pijava.coding.agent.extension.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Saves the full text of a truncated result, or a binary resource, and returns the file path
 * (pi {@code McpOutputSaver}, {@code tools.ts:65-69}).
 *
 * <p>The extension is injectable so tests need not write to the real temporary directory.</p>
 */
@FunctionalInterface
public interface McpOutputSaver {

    /**
     * Save {@code data} and return where it went.
     *
     * @param data the bytes to write
     * @param extension the file extension, including the dot
     * @return the path written
     * @throws IOException when the file could not be written
     */
    String save(byte[] data, String extension) throws IOException;

    /**
     * Save text as UTF-8.
     *
     * @param text the text to write
     * @param extension the file extension, including the dot
     * @return the path written
     * @throws IOException when the file could not be written
     */
    default String saveText(String text, String extension) throws IOException {
        return save(text.getBytes(StandardCharsets.UTF_8), extension);
    }

    /**
     * The default saver: a fresh file in the system temporary directory
     * ({@code tools.ts:71-76}).
     *
     * @return the saver
     */
    static McpOutputSaver tempFiles() {
        return tempFiles(Path.of(System.getProperty("java.io.tmpdir")));
    }

    /**
     * A saver writing into {@code directory}.
     *
     * @param directory where the files go
     * @return the saver
     */
    static McpOutputSaver tempFiles(Path directory) {
        return (data, extension) -> {
            var path = Files.createTempFile(directory, "pi-mcp-", extension);
            // Results can carry private data, so only the user may read the file.
            try {
                if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                    Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
                    Files.setPosixFilePermissions(path, ownerOnly);
                }
            } catch (IOException | UnsupportedOperationException ignored) {
                // Best effort: Windows has no POSIX mode, and pi's mode is only a default.
            }
            Files.write(path, data);
            return path.toString();
        };
    }
}
