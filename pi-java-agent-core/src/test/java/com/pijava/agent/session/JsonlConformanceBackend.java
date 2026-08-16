package com.pijava.agent.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.pijava.agent.session.jsonl.JsonlSessionCreateOptions;
import com.pijava.agent.session.jsonl.JsonlSessionListOptions;
import com.pijava.agent.session.jsonl.JsonlSessionMetadata;
import com.pijava.agent.session.jsonl.JsonlSessionRepository;

/** JSONL-backed conformance fixture over a fresh temp directory. */
public final class JsonlConformanceBackend implements ConformanceBackend {

    private final Path root;
    private final JsonlSessionRepository repository;
    private final Map<String, JsonlSessionMetadata> metadataById = new ConcurrentHashMap<>();

    public JsonlConformanceBackend() {
        try {
            root = Files.createTempDirectory("pi-jsonl-conformance");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create temp dir", e);
        }
        repository = JsonlSessionRepository.over(root);
    }

    @Override
    public Session<?> create(String cwd) {
        return create(cwd, null);
    }

    @Override
    public Session<?> create(String cwd, String id) {
        var session = repository.create(new JsonlSessionCreateOptions(id, cwd, null, null));
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
        return repository.list(JsonlSessionListOptions.all());
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
            new JsonlSessionCreateOptions(null, "cwd", null, null));
        metadataById.put(forked.getMetadata().id(), forked.getMetadata());
        return forked;
    }

    @Override
    public void close() {
        repository.deleteAllQuietly();
    }
}