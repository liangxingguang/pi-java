package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionBackendFactory;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionMetadata;
import com.pijava.agent.session.SessionRepository;
import com.pijava.agent.session.jsonl.JsonlCodec;
import com.pijava.coding.agent.core.PersistentSessionRepositories.RepositoryHandle;

/**
 * SQLite repository handle, discovered at runtime via {@link SessionBackendFactory}.
 * Options are built reflectively so coding-agent keeps no compile-time dependency on
 * the sqlite module (Phase 4 §14). Split out of {@link PersistentSessionRepositories}
 * (≤500-line rule, batch 14 docs/25 T4); behaviour unchanged.
 */
final class SqliteRepositoryHandle {

    private SqliteRepositoryHandle() {}

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
            public Session<?> createWithId(String cwd, String id) {
                // pi SessionManager.create(cwd, dir, {id})（main.ts:448）对位：带 id 新建。
                @SuppressWarnings("rawtypes")
                SessionRepository raw = repo;
                return (Session<?>) raw.create(sqliteCreateOptions(id, cwd, null));
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
            public Optional<ScopedMatch> findWithScope(String idOrPrefix) {
                // sqlite 无项目目录概念：按 metadata cwd 与 user.dir 等否判定 local/global。
                return find(idOrPrefix).map(m -> new ScopedMatch(m,
                    !PersistentSessionRepositories.metadataCwd(m)
                        .equals(System.getProperty("user.dir"))));
            }

            @Override
            public Optional<? extends SessionMetadata> latest(String cwd) {
                return list(cwd).stream().findFirst();
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
            public Session<?> fork(SessionMetadata source, ForkOptions options, String cwd,
                                   String sessionId) {
                @SuppressWarnings("rawtypes")
                SessionRepository raw = repo;
                return (Session<?>) raw.fork(source, options,
                    sqliteCreateOptions(sessionId, cwd, null));
            }

            @Override
            public SessionRepository<?, ?, ?> repository() {
                return repo;
            }

            @Override
            public void exportJsonl(Session<?> session, Path target) {
                PersistentSessionRepositories.exportViaLog(session, target);
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
                PersistentSessionRepositories.replayInto(created, source);
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
}
