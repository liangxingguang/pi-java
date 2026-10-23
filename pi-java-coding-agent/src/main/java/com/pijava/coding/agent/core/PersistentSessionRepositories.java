package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.MutationReplayer;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionMetadata;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.NewRecord;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionMutation;
import com.pijava.agent.session.SessionRepository;
import com.pijava.agent.session.jsonl.DefaultJsonlFileSystem;
import com.pijava.agent.session.jsonl.JsonlCodec;
import com.pijava.agent.session.jsonl.JsonlSessionCreateOptions;
import com.pijava.agent.session.jsonl.JsonlSessionListOptions;
import com.pijava.agent.session.jsonl.JsonlSessionMetadata;
import com.pijava.agent.session.jsonl.JsonlSessionRepoFileSystem;
import com.pijava.agent.session.jsonl.JsonlSessionRepository;
import com.pijava.agent.session.jsonl.JsonlSessionStorage;
import com.pijava.agent.session.jsonl.RichSessionInfo;
import com.pijava.agent.session.jsonl.SessionListProgress;
import com.pijava.agent.session.memory.MemorySessionMetadata;
import com.pijava.ai.AbortSignal;

/**
 * Type-erased persistent-session facade used by {@link AgentSession}
 * (Phase 4 §13). Keeps the wildcarded {@link SessionRepository} behind
 * concrete create/list/open/fork operations per backend.
 */
final class PersistentSessionRepositories {

    private PersistentSessionRepositories() {}

    /** JSONL repository handle (default backend). */
    static RepositoryHandle jsonl(Path sessionsRoot) {
        return jsonl(sessionsRoot, new DefaultJsonlFileSystem());
    }

    /**
     * JSONL handle over an injected file system — the seam a fixture needs to
     * <em>count</em> how many session files a scan opens (原 docs/39 §6.2), the
     * deterministic stand-in for the timing question A12 was about.
     */
    static RepositoryHandle jsonl(Path sessionsRoot, JsonlSessionRepoFileSystem fs) {
        var repo = new JsonlSessionRepository(sessionsRoot, fs);
        return new RepositoryHandle() {
            @Override
            public Session<?> create(String cwd, String parentSessionId) {
                return repo.create(new JsonlSessionCreateOptions(null, cwd, parentSessionId, null));
            }

            @Override
            public Optional<? extends SessionMetadata> find(String idOrPrefix) {
                if (idOrPrefix == null || idOrPrefix.isBlank()) {
                    return Optional.empty();
                }
                // pi resolveSessionPath（main.ts:260-276）阶梯（B53，docs/09 §10）：
                // ① 项目内 header-only 精确（findById 不加载 transcript）→
                // ② 项目内前缀 → ③ 跨项目**精确优先于前缀**（pi 是一次扫里
                // `exact ?? prefix`，java 单扫两谓词，行为等价留痕）。
                String cwd = System.getProperty("user.dir");
                return repo.findById(cwd, idOrPrefix)
                    .or(() -> repo.list(new JsonlSessionListOptions(cwd)).stream()
                        .filter(m -> m.id().startsWith(idOrPrefix))
                        .findFirst())
                    .or(() -> {
                        Optional<JsonlSessionMetadata> prefix = Optional.empty();
                        for (var m : repo.list(JsonlSessionListOptions.all())) {
                            if (m.id().equals(idOrPrefix)) {
                                return Optional.of(m);
                            }
                            if (prefix.isEmpty() && m.id().startsWith(idOrPrefix)) {
                                prefix = Optional.of(m);
                            }
                        }
                        return prefix;
                    });
            }

            @Override
            public Optional<ScopedMatch> findWithScope(String idOrPrefix) {
                if (idOrPrefix == null || idOrPrefix.isBlank()) {
                    return Optional.empty();
                }
                // pi resolveSessionPath（main.ts:260-276）阶梯带 scope 标：
                // ① 项目内精确 / ② 项目内前缀 ⇒ local；③ 跨项目精确优先于前缀 ⇒ global。
                String cwd = System.getProperty("user.dir");
                var local = repo.findById(cwd, idOrPrefix)
                    .or(() -> repo.list(new JsonlSessionListOptions(cwd)).stream()
                        .filter(m -> m.id().startsWith(idOrPrefix))
                        .findFirst());
                if (local.isPresent()) {
                    return local.map(m -> new ScopedMatch(m, false));
                }
                Optional<JsonlSessionMetadata> prefix = Optional.empty();
                for (var m : repo.list(JsonlSessionListOptions.all())) {
                    if (m.id().equals(idOrPrefix)) {
                        return Optional.of(new ScopedMatch(m, true));
                    }
                    if (prefix.isEmpty() && m.id().startsWith(idOrPrefix)) {
                        prefix = Optional.of(m);
                    }
                }
                return prefix.map(m -> new ScopedMatch(m, true));
            }

            @Override
            public Optional<? extends SessionMetadata> findLocalById(String cwd, String id) {
                // pi findLocalSessionByExactId（main.ts:243-250）：项目内 header-only 精确。
                if (id == null || id.isBlank()) {
                    return Optional.empty();
                }
                return repo.findById(cwd, id);
            }

            @Override
            public Session<?> createWithId(String cwd, String id) {
                // pi SessionManager.create(cwd, dir, {id})（main.ts:448）对位：带 id 新建。
                return repo.create(new JsonlSessionCreateOptions(id, cwd, null, null));
            }

            @Override
            public List<RichSessionInfo> listRich(String cwd, SessionListProgress progress,
                                                  AbortSignal signal) {
                // pi SessionManager.list(cwd, dir, onProgress, signal)（:943）对位。
                return repo.listRich(cwd, progress, signal);
            }

            @Override
            public List<RichSessionInfo> listAllRich(SessionListProgress progress,
                                                     AbortSignal signal) {
                // pi SessionManager.listAll(dir, onProgress, signal)（:1921）对位。
                return repo.listAllRich(progress, signal);
            }

            @Override
            public Optional<Session<?>> openPath(Path path) {
                // ⓪ 路径快道（pi main.ts:253-256）：直接按路径开 JSONL 文件。
                if (!Files.exists(path)) {
                    return Optional.empty();
                }
                return Optional.of(new Session<>(JsonlSessionStorage.load(fs, path)));
            }

            @Override
            public Optional<? extends SessionMetadata> latest(String cwd) {
                return list(cwd).stream().findFirst();
            }

            @Override
            public List<? extends SessionMetadata> list(String cwd) {
                return repo.list(new JsonlSessionListOptions(cwd));
            }

            @Override
            public Session<?> open(SessionMetadata metadata) {
                return repo.open((JsonlSessionMetadata) metadata);
            }

            @Override
            public Session<?> fork(SessionMetadata source, ForkOptions options, String cwd) {
                return repo.fork((JsonlSessionMetadata) source, options,
                    new JsonlSessionCreateOptions(null, cwd, null, null));
            }

            @Override
            public Session<?> fork(SessionMetadata source, ForkOptions options, String cwd,
                                   String sessionId) {
                return repo.fork((JsonlSessionMetadata) source, options,
                    new JsonlSessionCreateOptions(sessionId, cwd, null, null));
            }

            @Override
            public SessionRepository<?, ?, ?> repository() {
                return repo;
            }

            @Override
            public void exportJsonl(Session<?> session, Path target) {
                if (session.storage() instanceof JsonlSessionStorage storage) {
                    try {
                        Files.copy(storage.path(), target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(
                            "Failed to export session to " + target, e);
                    }
                } else {
                    exportViaLog(session, target);
                }
            }

            @Override
            public Session<?> importJsonl(Path source, String cwd) {
                return repo.importJsonl(source, cwd);
            }
        };
    }

    /**
     * SQLite repository handle — implementation lives in {@link SqliteRepositoryHandle}
     * (split for the ≤500-line rule, batch 14 docs/25 T4); this delegate keeps the
     * call sites unchanged.
     */
    static RepositoryHandle sqlite(Path dbPath) {
        return SqliteRepositoryHandle.sqlite(dbPath);
    }

    /** Type-erased repository facade used by the persistent session flow. */
    interface RepositoryHandle extends AutoCloseable {
        Session<?> create(String cwd, String parentSessionId);

        Optional<? extends SessionMetadata> find(String idOrPrefix);

        /**
         * The most recent session <b>in {@code cwd}'s own project directory</b>,
         * or empty.
         *
         * <p>Scoped to one project, matching pi's {@code continueRecent(cwd)} →
         * {@code getDefaultSessionDir(cwd)} → {@code findMostRecentSession(dir)}
         * ({@code session-manager.ts:1589}, {@code :483}, {@code :636}). Scanning
         * <em>every</em> project instead made {@code -c} and the web {@code ready}
         * frame cost O(all session files) — measured 1082 ms for 2125 files vs
         * 5 ms for an empty root (原 docs/39 §3), and it could resume a session
         * belonging to a different project.</p>
         *
         * <p>A {@code null} {@code cwd} means "no project scope": every directory
         * (JSONL) / every row (SQLite). Production callers always pass one.</p>
         */
        Optional<? extends SessionMetadata> latest(String cwd);

        List<? extends SessionMetadata> list(String cwd);

        Session<?> open(SessionMetadata metadata);

        /**
         * ⓪ 路径快道（pi {@code resolveSessionPath}，main.ts:253-256）：入参是
         * 路径形（含 {@code /}、{@code \} 或 {@code .jsonl} 结尾）时直接按路径开
         * 会话文件，不走 id/前缀阶梯。后端不支持 ⇒ {@code empty}。
         */
        default Optional<Session<?>> openPath(Path path) {
            return Optional.empty();
        }

        /**
         * pi {@code findLocalSessionByExactId}（main.ts:243-250）：项目内 header-only
         * 精确查找（不走前缀阶梯）。默认臂＝同步 {@link #list} 过滤；jsonl 臂走
         * header-only 快道。
         */
        default Optional<? extends SessionMetadata> findLocalById(String cwd, String id) {
            if (id == null || id.isBlank()) {
                return Optional.empty();
            }
            return list(cwd).stream().filter(m -> m.id().equals(id)).findFirst();
        }

        /**
         * pi {@code SessionManager.create(cwd, dir, { id })}（main.ts:448）＋
         * {@code forkFrom(..., { id: sessionId })} 对位：带指定 id 新建会话。
         */
        Session<?> createWithId(String cwd, String id);

        /**
         * pi {@code SessionManager.list(cwd, dir, onProgress, signal)}
         * （session-manager.ts:941）对位——E3-9。退化臂（sqlite/未知后端，窄差 D74）：
         * 同步 {@link #list} 映射最小 {@link RichSessionInfo}（无名字/首条消息/全文/
         * 计数），单次终末进度回调。
         */
        default List<RichSessionInfo> listRich(String cwd, SessionListProgress progress,
                                               AbortSignal signal) {
            var infos = list(cwd).stream()
                .map(PersistentSessionRepositories::minimalRichInfo)
                .toList();
            if (progress != null) {
                progress.onProgress(infos.size(), infos.size(), infos);
            }
            return infos;
        }

        /**
         * pi {@code SessionManager.listAll(dir, onProgress, signal)}
         * （session-manager.ts:1921）对位。退化臂＝{@code listRich(null, ...)}。
         */
        default List<RichSessionInfo> listAllRich(SessionListProgress progress,
                                                  AbortSignal signal) {
            return listRich(null, progress, signal);
        }

        /**
         * pi {@code resolveSessionPath} 的 scope 标（main.ts:252-283）：
         * {@code global=false} ≙ pi {@code local}（项目内 ①② 命中），
         * {@code global=true} ≙ pi {@code global}（跨项目 ③ 命中，带对端 cwd——
         * 由 metadata 携带）。
         */
        record ScopedMatch(SessionMetadata metadata, boolean global) {}

        /**
         * {@link #find} 的 scope 带标变体——{@code --session} 的全局臂（pi main.ts:391-413）
         * 需要区分项目内/跨项目命中。默认臂＝{@code find} 命中即 local（单项目假设）。
         */
        default Optional<ScopedMatch> findWithScope(String idOrPrefix) {
            return find(idOrPrefix).map(m -> new ScopedMatch(m, false));
        }

        Session<?> fork(SessionMetadata source, ForkOptions options, String cwd);

        /**
         * pi {@code forkSessionOrExit(..., parsed.sessionId)}（main.ts:380-386）：
         * {@code --fork} 与 {@code --session-id} 组合时，新会话用给定 id
         * （{@code forkFrom(..., { id: sessionId })}；#75/docs/10 §5）。
         */
        default Session<?> fork(SessionMetadata source, ForkOptions options, String cwd,
                                String sessionId) {
            return fork(source, options, cwd);
        }

        SessionRepository<?, ?, ?> repository();

        /** Export the session to a JSONL file (Phase 4 §4.7). */
        void exportJsonl(Session<?> session, Path target);

        /** Import a JSONL file into a new session (Phase 4 §4.7). */
        Session<?> importJsonl(Path source, String cwd);

        @Override
        default void close() {
        }
    }

    /** Export via {@code getLog} re-encode (backend-independent). */
    static void exportViaLog(Session<?> session, Path target) {
        var metadata = session.getMetadata();
        var header = new com.pijava.agent.session.jsonl.JsonlV4Header("header", 4,
            metadata.id(), metadata.createdAt().toEpochMilli(),
            metadataCwd(metadata), metadata.parentSessionId(), null, null);
        var sb = new StringBuilder(JsonlCodec.encodeHeader(header));
        for (var item : session.getLog(com.pijava.agent.session.LogOptions.none())) {
            sb.append(JsonlCodec.encodeMutation(mutationFromLogItem(item)));
        }
        try {
            Files.writeString(target, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to export session to " + target, e);
        }
    }

    /** Replay a JSONL file into a session facade (SQLite/Memory import). */
    static void replayInto(Session<?> session, Path source) {
        List<String> lines;
        try {
            lines = Files.readAllLines(source, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new SessionError(SessionErrorCode.INVALID_PAYLOAD,
                "Failed to read import file " + source, e);
        }
        if (lines.isEmpty()) {
            throw new SessionError(SessionErrorCode.INVALID_PAYLOAD, "Import file is empty");
        }
        var header = JsonlCodec.parseHeader(lines.getFirst());
        if (!header.ok()) {
            throw new SessionError(SessionErrorCode.INVALID_PAYLOAD,
                "Import file has an invalid header");
        }
        for (int i = 1; i < lines.size(); i++) {
            // pi 的行不带 seq（没有 kind 判别）⇒ 行号就是它的 seq（docs/12）。
            var parsed = JsonlCodec.parseMutation(lines.get(i), i);
            if (!parsed.ok()) {
                throw new SessionError(SessionErrorCode.INVALID_PAYLOAD,
                    "Import file line " + (i + 1) + ": " + parsed.error().getMessage());
            }
            applyMutation(session, parsed.value());
        }
    }

    private static void applyMutation(Session<?> session, SessionMutation mutation) {
        if (session.storage() instanceof MutationReplayer replayer) {
            replayer.replayMutation(mutation);
            return;
        }
        switch (mutation) {
            case SessionMutation.Entry entry -> {
                String lane = entry.lane() == null ? "main" : entry.lane();
                ensureLane(session, lane);
                session.appendEntry(new ProvisionedEntry<>(entry.entry()), lane);
            }
            case SessionMutation.Record record ->
                session.appendRecord(new NewRecord<>(record.record()));
            case SessionMutation.Lane lane -> {
                boolean exists = session.getLanes().stream()
                    .anyMatch(p -> lane.lane().equals(p.lane()));
                if (exists) {
                    session.moveLane(lane.lane(), lane.leafId());
                } else {
                    session.createLane(lane.lane(), lane.leafId());
                }
            }
            case SessionMutation.FactName name -> session.setName(name.name());
            case SessionMutation.FactLabel label -> session.setLabel(label.targetId(), label.label());
        }
    }

    private static void ensureLane(Session<?> session, String lane) {
        boolean exists = session.getLanes().stream().anyMatch(p -> lane.equals(p.lane()));
        if (!exists) {
            session.createLane(lane, null);
        }
    }

    private static SessionMutation mutationFromLogItem(com.pijava.agent.session.LogItem item) {
        return switch (item) {
            case com.pijava.agent.session.LogItem.EntryItem e ->
                new SessionMutation.Entry(null, e.entry());
            case com.pijava.agent.session.LogItem.RecordItem r ->
                new SessionMutation.Record(r.parentId(), r.record());
            case com.pijava.agent.session.LogItem.LaneItem l ->
                new SessionMutation.Lane(l.seq(), l.parentId(), l.timestamp(), l.lane(), l.leafId());
            case com.pijava.agent.session.LogItem.NameItem n ->
                new SessionMutation.FactName(n.seq(), n.parentId(), n.timestamp(), n.name());
            case com.pijava.agent.session.LogItem.LabelItem l ->
                new SessionMutation.FactLabel(l.seq(), l.parentId(), l.timestamp(),
                    l.targetId(), l.label());
        };
    }

    /**
     * 退化臂的最小 {@link RichSessionInfo}（窄差 D74：sqlite/未知后端无富扫描——
     * 名字/首条消息/全文/计数缺省，选择器列表退化但可用）。
     */
    private static RichSessionInfo minimalRichInfo(SessionMetadata metadata) {
        long ms = metadata.createdAt() == null ? 0L : metadata.createdAt().toEpochMilli();
        return new RichSessionInfo(metadata.id(), "", null, metadataCwd(metadata), ms, ms, 0,
            "(no messages)", "", metadata.parentSessionId());
    }

    /** Resolve the session cwd from metadata; the SQLite type is read reflectively. */
    static String metadataCwd(SessionMetadata metadata) {
        if (metadata instanceof JsonlSessionMetadata j) {
            return j.cwd();
        }
        if (metadata instanceof MemorySessionMetadata m) {
            return m.cwd();
        }
        try {
            return (String) metadata.getClass().getMethod("cwd").invoke(metadata);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return System.getProperty("user.dir");
        }
    }
}
