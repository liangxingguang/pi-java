package com.pijava.session.sqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.pijava.agent.session.ConformanceBackend;
import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionMetadata;

/** SQLite-backed conformance fixture over a fresh temp database. */
public final class SqliteConformanceBackend implements ConformanceBackend {

    private final Path dbPath;
    private final SqliteSessionRepository repository;
    private final Map<String, SqliteSessionMetadata> metadataById = new ConcurrentHashMap<>();

    public SqliteConformanceBackend() {
        try {
            var dir = Files.createTempDirectory("pi-sqlite-conformance");
            dbPath = dir.resolve("sessions.db");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create temp dir", e);
        }
        repository = SqliteSessionRepository.open(dbPath);
    }

    @Override
    public Session<?> create(String cwd) {
        return create(cwd, null);
    }

    @Override
    public Session<?> create(String cwd, String id) {
        var session = repository.create(new SqliteSessionCreateOptions(id, cwd, null, null));
        metadataById.put(session.getMetadata().id(), session.getMetadata());
        return session;
    }

    @Override
    public Session<?> open(String id) {
        var metadata = metadataById.get(id);
        if (metadata == null) {
            throw new SessionError(SessionErrorCode.NOT_FOUND, "Session not found: " + id);
        }
        return repository.open(metadata);
    }

    @Override
    public List<? extends SessionMetadata> list() {
        return repository.list(SqliteSessionListOptions.all());
    }

    @Override
    public void delete(String id) {
        var metadata = metadataById.remove(id);
        if (metadata != null) {
            repository.delete(metadata);
        }
    }

    @Override
    public Session<?> fork(String sourceId, ForkOptions options) {
        var metadata = metadataById.get(sourceId);
        var forked = repository.fork(metadata, options,
            new SqliteSessionCreateOptions(null, "cwd", null, null));
        metadataById.put(forked.getMetadata().id(), forked.getMetadata());
        return forked;
    }

    @Override
    public void close() {
        repository.close();
    }
}