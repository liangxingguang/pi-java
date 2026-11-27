package com.pijava.coding.agent.codemode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Set;

/** Writes spilled text and image files for codemode results. */
@FunctionalInterface
public interface CodemodeOutputFiles {
    /**
     * Writes {@code data} with a {@code pi-codemode-} prefix and returns its path.
     *
     * @param data bytes to write
     * @param extension file extension including the dot
     * @return the path written
     * @throws IOException on a write failure
     */
    String save(byte[] data, String extension) throws IOException;

    /** Writes UTF-8 text. */
    default String saveText(String text) throws IOException {
        return save(text.getBytes(StandardCharsets.UTF_8), ".txt");
    }

    /** The default temp-file saver with owner-only POSIX permissions where supported. */
    static CodemodeOutputFiles tempFiles() {
        return tempFiles(Path.of(System.getProperty("java.io.tmpdir")));
    }

    /** A saver writing into {@code directory}. */
    static CodemodeOutputFiles tempFiles(Path directory) {
        return (data, extension) -> {
            Path path = Files.createTempFile(directory, "pi-codemode-", extension);
            try {
                if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                    Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
                    Files.setPosixFilePermissions(path, ownerOnly);
                }
            } catch (IOException | UnsupportedOperationException ignored) {
                // Best effort: Windows has no POSIX mode.
            }
            Files.write(path, data);
            return path.toString();
        };
    }

    /** Human-readable byte size, matching pi's {@code formatSize}. */
    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
