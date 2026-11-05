package com.pijava.agent.session.jsonl;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionPaths;
import com.pijava.agent.session.SessionRepository;
import com.pijava.agent.session.UuidV7;
import com.pijava.ai.AbortSignal;

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
    /** 默认根（{@code ~/.pi-java/agent/sessions}）；显式目录模式下用于 cwd 过滤判据。 */
    private final Path defaultSessionsRoot;
    /** {@code true}＝显式会话目录（pi {@code --session-dir}）：文件平铺，不拼 cwd 子目录。 */
    private final boolean flat;
    private final Set<String> activeCreateDestinations = new HashSet<>();
    private final RichSessionLister richLister;

    /** Create a JSONL repository rooted at {@code sessionsRoot}（默认根布局）。 */
    public JsonlSessionRepository(Path sessionsRoot, JsonlSessionRepoFileSystem fs) {
        this(sessionsRoot, sessionsRoot, false, fs);
    }

    /**
     * B278（docs/24 W4c）：pi {@code SessionManager.create(cwd, sessionDir)} 的两种
     * 目录形态。
     *
     * @param sessionDirectory  会话文件所在目录（flat）或默认根（非 flat）
     * @param defaultSessionsRoot 默认根；flat 模式下与 `默认根/--cwd--/` 比较决定是否按 cwd 过滤
     * @param flat              {@code true}＝显式目录（文件平铺）
     * @param fs                文件系统边界
     */
    public JsonlSessionRepository(Path sessionDirectory, Path defaultSessionsRoot,
                                  boolean flat, JsonlSessionRepoFileSystem fs) {
        this.sessionsRoot = sessionDirectory;
        this.defaultSessionsRoot = defaultSessionsRoot;
        this.flat = flat;
        this.fs = fs;
        this.richLister = new RichSessionLister(sessionDirectory, fs, this::sessionDirectories,
            this::cwdMatches);
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
                var parsed = JsonlSessionStorage.metadataFromHeader(
                    headerResult.value(), entry.path(), entry.mtimeMs());
                // B278（docs/24 W4c）：pi SessionManager.list 的 includeSession 过滤。
                if (!cwdMatches(options.cwd(), parsed.cwd())) {
                    continue;
                }
                metadata.add(parsed);
            }
        }
        metadata.sort((left, right) -> Long.compare(right.modifiedAtMs(), left.modifiedAtMs()));
        return metadata;
    }

    @Override
    public void delete(JsonlSessionMetadata metadata) {
        fs.remove(metadata.path(), true);
    }

    // #79/B55：渐进富列表的实现在 RichSessionLister（本类只留转发 —— ≤500 行规约）。

    /** pi {@code listSessionsFromDir} —— 转发 {@link RichSessionLister}。 */
    public List<RichSessionInfo> listRich(String cwd, SessionListProgress progress,
                                          AbortSignal signal) {
        return richLister.listRich(cwd, progress, signal);
    }

    /** pi {@code listAll} —— 转发 {@link RichSessionLister}。 */
    public List<RichSessionInfo> listAllRich(SessionListProgress progress, AbortSignal signal) {
        return richLister.listAllRich(progress, signal);
    }


    /**
     * pi {@code SessionManager.findById}（session-manager.ts:1874-1911）对位：
     * **只读各文件 header** 的精确道（不加载 transcript）——会话 find 阶梯的 ① 级
     * （pi {@code resolveSessionPath}，main.ts:260-262；B53，docs/09 §10）。
     *
     * @param cwd 项目工作目录（{@code null} ⇒ 跨项目全扫）
     * @param id  精确会话 id
     */
    public Optional<JsonlSessionMetadata> findById(String cwd, String id) {
        for (var directory : sessionDirectories(cwd)) {
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
                var metadata = JsonlSessionStorage.metadataFromHeader(
                    headerResult.value(), entry.path(), entry.mtimeMs());
                if (metadata.id().equals(id) && cwdMatches(cwd, metadata.cwd())) {
                    return Optional.of(metadata);
                }
            }
        }
        return Optional.empty();
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

    List<Path> sessionDirectories(String cwd) {
        if (flat) {
            // B278（docs/24 W4c）：显式会话目录平铺——项目内列表与全量列表都只扫该目录。
            return fs.exists(sessionsRoot) ? List.of(sessionsRoot) : List.of();
        }
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
        if (flat) {
            return sessionsRoot.toString();
        }
        return fs.joinPath(List.of(sessionsRoot.toString(), sessionDirectoryName(cwd)));
    }

    /**
     * pi {@code filterCwd}（session-manager.ts:744-745,1805,1891,1922）：显式目录
     * 与「默认根/--编码 cwd--」不是同一位置时，列表与精确查找按 cwd 过滤。
     */
    private boolean filterCwd(String cwd) {
        if (!flat || cwd == null) {
            return false;
        }
        String resolved = fs.absolutePath(cwd);
        String defaultDir = fs.joinPath(List.of(
            defaultSessionsRoot.toString(), sessionDirectoryName(resolved)));
        return !SessionPaths.same(sessionsRoot.toString(), defaultDir);
    }

    /** pi {@code includeSession}：不启用过滤或 header cwd 与当前 cwd 相同 ⇒ 收入。 */
    private boolean cwdMatches(String cwd, String sessionCwd) {
        if (!filterCwd(cwd)) {
            return true;
        }
        return sessionCwd != null && !sessionCwd.isEmpty()
            && SessionPaths.same(sessionCwd, fs.absolutePath(cwd));
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
