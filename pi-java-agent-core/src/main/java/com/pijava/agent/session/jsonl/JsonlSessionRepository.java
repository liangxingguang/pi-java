package com.pijava.agent.session.jsonl;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionRepository;
import com.pijava.agent.session.UuidV7;

/**
 * JSONL session repository (aligned with pi {@code JsonlSessionRepo}). Guards
 * same-process create/fork races via {@code activeCreateDestinations}.
 */
public final class JsonlSessionRepository implements
    SessionRepository<JsonlSessionMetadata, JsonlSessionCreateOptions, JsonlSessionListOptions> {

    private static final Pattern SESSION_ID_PATTERN =
        Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$");

    private final JsonlSessionRepoFileSystem fs;
    private final Path sessionsRoot;
    private final Set<String> activeCreateDestinations = new HashSet<>();

    public JsonlSessionRepository(Path sessionsRoot, JsonlSessionRepoFileSystem fs) {
        this.sessionsRoot = sessionsRoot;
        this.fs = fs;
    }

    /** Default file system over {@code sessionsRoot}. */
    public static JsonlSessionRepository over(Path sessionsRoot) {
        return new JsonlSessionRepository(sessionsRoot, new DefaultJsonlFileSystem());
    }

    @Override
    public Session<JsonlSessionMetadata> create(JsonlSessionCreateOptions options) {
        var destination = resolveCreateDestination(options);
        synchronized (activeCreateDestinations) {
            if (!activeCreateDestinations.add(destination.key())) {
                throw new SessionError(SessionErrorCode.ALREADY_EXISTS,
                    "Session already exists: " + destination.id());
            }
        }
        try {
            var prepared = prepareCreate(destination, options);
            var storage = JsonlSessionStorage.create(fs, prepared.path(), prepared.header());
            return new Session<>(storage);
        } finally {
            synchronized (activeCreateDestinations) {
                activeCreateDestinations.remove(destination.key());
            }
        }
    }

    @Override
    public Session<JsonlSessionMetadata> open(JsonlSessionMetadata metadata) {
        return new Session<>(loadStorage(metadata));
    }

    @Override
    public List<JsonlSessionMetadata> list(JsonlSessionListOptions options) {
        List<JsonlSessionMetadata> metadata = new ArrayList<>();
        List<Path> directories = sessionDirectories(options.cwd());
        for (var directory : directories) {
            for (var entry : fs.listDir(directory)) {
                if ("directory".equals(entry.kind()) || !entry.name().endsWith(".jsonl")) {
                    continue;
                }
                List<String> firstLine = fs.readTextLines(entry.path(), 1);
                if (firstLine.isEmpty()) {
                    continue;
                }
                var headerResult = JsonlCodec.parseHeader(firstLine.getFirst());
                if (!headerResult.ok()) {
                    continue;
                }
                metadata.add(JsonlSessionStorage.metadataFromHeader(
                    headerResult.value(), entry.path(), entry.mtimeMs()));
            }
        }
        metadata.sort((left, right) -> Long.compare(right.modifiedAtMs(), left.modifiedAtMs()));
        return metadata;
    }

    @Override
    public void delete(JsonlSessionMetadata metadata) {
        fs.remove(metadata.path(), true);
    }

    @Override
    public Session<JsonlSessionMetadata> fork(JsonlSessionMetadata source, ForkOptions options,
                                              JsonlSessionCreateOptions createOptions) {
        var sourceStorage = loadStorage(source);
        String parentSessionId = createOptions.parentSessionId() != null
            ? createOptions.parentSessionId() : source.id();
        var optionsWithParent = new JsonlSessionCreateOptions(
            createOptions.id(), createOptions.cwd(), parentSessionId, createOptions.metadata());
        var destination = resolveCreateDestination(optionsWithParent);
        synchronized (activeCreateDestinations) {
            if (!activeCreateDestinations.add(destination.key())) {
                throw new SessionError(SessionErrorCode.ALREADY_EXISTS,
                    "Session already exists: " + destination.id());
            }
        }
        try {
            var prepared = prepareCreate(destination, optionsWithParent);
            var storage = sourceStorage.fork(prepared.path(), prepared.header(), options);
            return new Session<>(storage);
        } finally {
            synchronized (activeCreateDestinations) {
                activeCreateDestinations.remove(destination.key());
            }
        }
    }

    /** Delete all sessions under the root (test cleanup). */
    public void deleteAllQuietly() {
        if (!fs.exists(sessionsRoot)) {
            return;
        }
        fs.remove(sessionsRoot, true);
    }

    /**
     * Import a JSONL session file (Phase 4 §4.7). Validates the header,
     * keeps the header's session id, copies the file into the sessions
     * directory for {@code cwd}, and opens it. A same-id session throws
     * {@code already_exists}.
     */
    public Session<JsonlSessionMetadata> importJsonl(Path source, String cwd) {
        List<String> firstLine = fs.readTextLines(source, 1);
        if (firstLine.isEmpty()) {
            throw new SessionError(SessionErrorCode.INVALID_PAYLOAD,
                "Import file is empty: " + source);
        }
        var headerResult = JsonlCodec.parseHeader(firstLine.getFirst());
        if (!headerResult.ok()) {
            throw new SessionError(SessionErrorCode.INVALID_PAYLOAD,
                "Import file has an invalid header: " + headerResult.error().getMessage());
        }
        var header = headerResult.value();
        String id = header.id();
        if (!SESSION_ID_PATTERN.matcher(id).matches()) {
            throw new SessionError(SessionErrorCode.INVALID_PAYLOAD,
                "Session id must contain only alphanumeric characters, '-', '_', and '.'");
        }
        String resolvedCwd = fs.absolutePath(cwd);
        if (sessionIdExists(id, resolvedCwd)) {
            throw new SessionError(SessionErrorCode.ALREADY_EXISTS,
                "Session already exists: " + id);
        }
        String sessionDirectory = sessionDirectory(resolvedCwd);
        fs.createDir(Path.of(sessionDirectory), true);
        Path destination = Path.of(fs.joinPath(List.of(
            sessionDirectory, sessionFileName(header.createdAtMs(), id))));
        String content = fs.readTextFile(source);
        fs.writeFile(destination, content);
        return new Session<>(JsonlSessionStorage.load(fs, destination));
    }

    /** Check whether a session file exists and its id matches. */
    private JsonlSessionStorage loadStorage(JsonlSessionMetadata metadata) {
        if (!fs.exists(metadata.path())) {
            throw new SessionError(SessionErrorCode.NOT_FOUND,
                "Session not found: " + metadata.id());
        }
        JsonlSessionStorage storage;
        try {
            storage = JsonlSessionStorage.load(fs, metadata.path());
        } catch (JsonlSessionStorage.JsonlSessionError e) {
            throw new SessionError(SessionErrorCode.INVALID_ENTRY, e.getMessage(), e);
        }
        if (!storage.getMetadata().id().equals(metadata.id())) {
            throw new SessionError(SessionErrorCode.INVALID_ENTRY,
                "Session id does not match header: " + metadata.id());
        }
        return storage;
    }

    private CreateDestination resolveCreateDestination(JsonlSessionCreateOptions options) {
        String id = options.id() != null ? options.id() : UuidV7.INSTANCE.next();
        if (!SESSION_ID_PATTERN.matcher(id).matches()) {
            throw new SessionError(SessionErrorCode.INVALID_PAYLOAD,
                "Session id must be non-empty, contain only alphanumeric characters, "
                    + "'-', '_', and '.', and start and end with an alphanumeric character");
        }
        String cwd = fs.absolutePath(options.cwd() != null ? options.cwd() : System.getProperty("user.dir"));
        return new CreateDestination(id, cwd);
    }

    private PreparedCreate prepareCreate(CreateDestination destination,
                                         JsonlSessionCreateOptions options) {
        String id = destination.id();
        String cwd = destination.cwd();
        if (sessionIdExists(id, cwd)) {
            throw new SessionError(SessionErrorCode.ALREADY_EXISTS,
                "Session already exists: " + id);
        }
        long createdAt = System.currentTimeMillis();
        String sessionDirectory = sessionDirectory(cwd);
        String fileName = sessionFileName(createdAt, id);
        Path path = Path.of(fs.joinPath(List.of(sessionDirectory, fileName)));
        var header = JsonlV4Header.v4(id, createdAt, cwd, options.parentSessionId(), options.metadata());
        fs.createDir(Path.of(sessionDirectory), true);
        return new PreparedCreate(header, path);
    }

    private boolean sessionIdExists(String id, String cwd) {
        String suffix = "_" + id + ".jsonl";
        String directory = sessionDirectory(cwd);
        if (!fs.exists(Path.of(directory))) {
            return false;
        }
        return fs.listDir(Path.of(directory)).stream()
            .anyMatch(e -> !"directory".equals(e.kind()) && e.name().endsWith(suffix));
    }

    private List<Path> sessionDirectories(String cwd) {
        if (cwd != null) {
            String resolved = fs.absolutePath(cwd);
            String directory = sessionDirectory(resolved);
            return fs.exists(Path.of(directory)) ? List.of(Path.of(directory)) : List.of();
        }
        if (!fs.exists(sessionsRoot)) {
            return List.of();
        }
        return fs.listDir(sessionsRoot).stream()
            .filter(e -> "directory".equals(e.kind()))
            .map(JsonlSessionRepoFileSystem.DirEntry::path)
            .toList();
    }

    private String sessionDirectory(String cwd) {
        return fs.joinPath(List.of(sessionsRoot.toString(), sessionDirectoryName(cwd)));
    }

    static String sessionDirectoryName(String cwd) {
        String normalized = cwd.replaceFirst("^[/\\\\]", "");
        return "--" + normalized.replaceAll("[/\\\\:]", "-") + "--";
    }

    static String sessionFileName(long createdAtMs, String id) {
        String timestamp = Instant.ofEpochMilli(createdAtMs).toString().replace(":", "-").replace(".", "-");
        return timestamp + "_" + id + ".jsonl";
    }

    private record CreateDestination(String id, String cwd) {
        String key() {
            return cwd + "\u0000" + id;
        }
    }

    private record PreparedCreate(JsonlV4Header header, Path path) {}
}
