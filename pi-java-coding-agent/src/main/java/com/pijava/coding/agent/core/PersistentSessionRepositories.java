package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.MutationReplayer;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionBackendFactory;
import com.pijava.agent.session.SessionMetadata;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.record.NewRecord;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionMutation;
import com.pijava.agent.session.SessionRepository;
import com.pijava.agent.session.jsonl.JsonlCodec;
import com.pijava.agent.session.jsonl.JsonlSessionCreateOptions;
import com.pijava.agent.session.jsonl.JsonlSessionListOptions;
import com.pijava.agent.session.jsonl.JsonlSessionMetadata;
import com.pijava.agent.session.jsonl.JsonlSessionRepository;
import com.pijava.agent.session.jsonl.JsonlSessionStorage;
import com.pijava.agent.session.memory.MemorySessionMetadata;

/**
 * Type-erased persistent-session facade used by {@link AgentSession}
 * (Phase 4 §13). Keeps the wildcarded {@link SessionRepository} behind
 * concrete create/list/open/fork operations per backend.
 */
final class PersistentSessionRepositories {

    private PersistentSessionRepositories() {}

    /** JSONL repository handle (default backend). */
    static RepositoryHandle jsonl(Path sessionsRoot) {
        var repo = JsonlSessionRepository.over(sessionsRoot);
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
                return repo.list(JsonlSessionListOptions.all()).stream()
                    .filter(m -> m.id().equals(idOrPrefix) || m.id().startsWith(idOrPrefix))
                    .findFirst();
            }

            @Override
            public Optional<? extends SessionMetadata> latest() {
                return repo.list(JsonlSessionListOptions.all()).stream().findFirst();
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
     * SQLite repository handle, discovered at runtime via
     * {@link SessionBackendFactory}. Options are built reflectively so
     * coding-agent keeps no compile-time dependency on the sqlite module
     * (Phase 4 §14).
     */
    static RepositoryHandle sqlite(Path dbPath) {
        var factory = ServiceLoader.load(SessionBackendFactory.class).stream()
            .map(ServiceLoader.Provider::get)
            .filter(f -> "sqlite".equals(f.name()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "SQLite session backend requested but no SessionBackendFactory is registered"));
        SessionRepository<?, ?, ?> repo = factory.create(dbPath);
        return new RepositoryHandle() {
            @Override
            public Session<?> create(String cwd, String parentSessionId) {
                // Raw call: the runtime repository is the sqlite repo.
                @SuppressWarnings("rawtypes")
                SessionRepository raw = repo;
                return (Session<?>) raw.create(sqliteCreateOptions(cwd, parentSessionId));
            }

            @Override
            public Optional<? extends SessionMetadata> find(String idOrPrefix) {
                if (idOrPrefix == null || idOrPrefix.isBlank()) {
                    return Optional.empty();
                }
                return list(null).stream()
                    .filter(m -> m.id().equals(idOrPrefix) || m.id().startsWith(idOrPrefix))
                    .findFirst();
            }

            @Override
            public Optional<? extends SessionMetadata> latest() {
                return list(null).stream().findFirst();
            }

            @Override
            public List<? extends SessionMetadata> list(String cwd) {
                @SuppressWarnings("rawtypes")
                SessionRepository raw = repo;
                return ((List<?>) raw.list(sqliteListOptions(cwd))).stream()
                    .map(SessionMetadata.class::cast)
                    .toList();
            }

            @Override
            public Session<?> open(SessionMetadata metadata) {
                @SuppressWarnings("rawtypes")
                SessionRepository raw = repo;
                return (Session<?>) raw.open(metadata);
            }

            @Override
            public Session<?> fork(SessionMetadata source, ForkOptions options, String cwd) {
                @SuppressWarnings("rawtypes")
                SessionRepository raw = repo;
                return (Session<?>) raw.fork(source, options, sqliteCreateOptions(cwd, null));
            }

            @Override
            public SessionRepository<?, ?, ?> repository() {
                return repo;
            }

            @Override
            public void exportJsonl(Session<?> session, Path target) {
                exportViaLog(session, target);
            }

            @Override
            public Session<?> importJsonl(Path source, String cwd) {
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
                // Raw call: the runtime repository is the sqlite repo.
                @SuppressWarnings("rawtypes")
                SessionRepository raw = repo;
                Session<?> created = (Session<?>) raw.create(
                    sqliteCreateOptions(header.value().id(), cwd, header.value().parentSessionId()));
                replayInto(created, source);
                return created;
            }

            @Override
            public void close() {
                if (repo instanceof AutoCloseable closable) {
                    try {
                        closable.close();
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed to close SQLite repository", e);
                    }
                }
            }
        };
    }

    private static Object sqliteCreateOptions(String cwd, String parentSessionId) {
        return sqliteCreateOptions(null, cwd, parentSessionId);
    }

    private static Object sqliteCreateOptions(String id, String cwd, String parentSessionId) {
        try {
            var type = Class.forName("com.pijava.session.sqlite.SqliteSessionCreateOptions");
            var ctor = type.getConstructor(String.class, String.class, String.class, java.util.Map.class);
            return ctor.newInstance(id, cwd, parentSessionId, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SQLite backend unavailable", e);
        }
    }

    private static Object sqliteListOptions(String cwd) {
        try {
            var type = Class.forName("com.pijava.session.sqlite.SqliteSessionListOptions");
            var ctor = type.getConstructor(String.class);
            return ctor.newInstance(cwd);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("SQLite backend unavailable", e);
        }
    }
    /** Type-erased repository facade used by the persistent session flow. */
    interface RepositoryHandle extends AutoCloseable {
        Session<?> create(String cwd, String parentSessionId);

        Optional<? extends SessionMetadata> find(String idOrPrefix);

        Optional<? extends SessionMetadata> latest();

        List<? extends SessionMetadata> list(String cwd);

        Session<?> open(SessionMetadata metadata);

        Session<?> fork(SessionMetadata source, ForkOptions options, String cwd);

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
            var parsed = JsonlCodec.parseMutation(lines.get(i));
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
                new SessionMutation.Record(r.record());
            case com.pijava.agent.session.LogItem.LaneItem l ->
                new SessionMutation.Lane(l.seq(), l.lane(), l.leafId());
            case com.pijava.agent.session.LogItem.NameItem n ->
                new SessionMutation.FactName(n.seq(), n.name());
            case com.pijava.agent.session.LogItem.LabelItem l ->
                new SessionMutation.FactLabel(l.seq(), l.targetId(), l.label());
        };
    }

    /** Resolve the session cwd from metadata; the SQLite type is read reflectively. */
    private static String metadataCwd(SessionMetadata metadata) {
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
