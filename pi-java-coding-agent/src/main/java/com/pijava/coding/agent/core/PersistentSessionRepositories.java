package com.pijava.coding.agent.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionBackendFactory;
import com.pijava.agent.session.SessionMetadata;
import com.pijava.agent.session.SessionRepository;
import com.pijava.agent.session.jsonl.JsonlSessionCreateOptions;
import com.pijava.agent.session.jsonl.JsonlSessionListOptions;
import com.pijava.agent.session.jsonl.JsonlSessionMetadata;
import com.pijava.agent.session.jsonl.JsonlSessionRepository;

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
        try {
            var type = Class.forName("com.pijava.session.sqlite.SqliteSessionCreateOptions");
            var ctor = type.getConstructor(String.class, String.class, String.class, java.util.Map.class);
            return ctor.newInstance(null, cwd, parentSessionId, null);
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

        @Override
        default void close() {
        }
    }
}
