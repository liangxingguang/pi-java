package com.pijava.mcp.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * {@link AuthJsonBackend} over one file, locked with {@link FileChannel#lock}
 * (pi {@code FileAuthStorageBackend}, {@code auth-storage.ts:49-114}).
 *
 * <p>pi retries the file lock ten times, 20 ms apart, so a brief overlap with another process
 * does not fail the caller ({@code auth-storage.ts:69-94}).</p>
 *
 * <p>A {@link FileLock} belongs to the whole JVM, so a second thread locking the same file gets
 * {@link OverlappingFileLockException} rather than blocking. A per-path {@link ReentrantLock}
 * serializes threads inside this process first; the file lock is what keeps other processes
 * out.</p>
 */
public final class FileAuthJsonBackend implements AuthJsonBackend {

    private static final int MAX_ATTEMPTS = 10;
    private static final long RETRY_MS = 20;

    /** The content a missing store is created with ({@code auth-storage.ts:63-67}). */
    private static final String EMPTY = "{}";

    /** Per-path locks; the same file must not be locked twice by this JVM. */
    private static final Map<Path, ReentrantLock> THREAD_LOCKS = new ConcurrentHashMap<>();

    private final Path path;

    /**
     * Create a backend over {@code path}.
     *
     * @param path the JSON file, usually {@code <agent-dir>/mcp-auth.json}
     */
    public FileAuthJsonBackend(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    /** The file this backend reads and writes. */
    public Path path() {
        return path;
    }

    @Override
    public <T> T withLock(Function<@Nullable String, LockResult<T>> edit) {
        var parent = path.getParent();
        var threadLock = THREAD_LOCKS.computeIfAbsent(path, key -> new ReentrantLock());
        threadLock.lock();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(path)) {
                createRestrictedFile();
            }
            try (var channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 var fileLock = acquire(channel)) {
                // Read through the locked channel. Windows refuses a read of a locked byte range
                // from another handle, even inside this JVM, so Files.readString here would fail
                // with a lock violation.
                var outcome = edit.apply(readAll(channel));
                if (outcome.next() != null) {
                    write(channel, outcome.next());
                }
                return outcome.result();
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        } finally {
            threadLock.unlock();
        }
    }

    /** pi creates the file with {@code "{}"} and {@code mode: 0o600}. */
    private void createRestrictedFile() throws IOException {
        Files.writeString(path, EMPTY, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        try {
            if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
                Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(path, ownerOnly);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Best effort: Windows has no POSIX mode, and pi's mode is only a default.
        }
    }

    /** Retry the file lock the way pi's synchronous path does ({@code auth-storage.ts:69-94}). */
    private static FileLock acquire(FileChannel channel) throws IOException {
        for (var attempt = 0; ; attempt++) {
            try {
                var lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException expected) {
                // Contention inside this JVM; the per-path lock above should have prevented it.
            }
            if (attempt + 1 >= MAX_ATTEMPTS) {
                throw new IOException("Failed to acquire auth storage lock on " + channel);
            }
            try {
                Thread.sleep(RETRY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while acquiring the auth storage lock", interrupted);
            }
        }
    }

    private static String readAll(FileChannel channel) throws IOException {
        var size = channel.size();
        var buffer = ByteBuffer.allocate((int) Math.min(size, Integer.MAX_VALUE));
        channel.position(0);
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) {
                break;
            }
        }
        return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
    }

    private static void write(FileChannel channel, String content) throws IOException {
        var bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
        channel.truncate(0);
        channel.position(0);
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
        channel.force(true);
    }
}
