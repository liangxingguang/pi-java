package com.pijava.agent.session;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.pijava.agent.session.memory.MemorySessionCreateOptions;
import com.pijava.agent.session.memory.MemorySessionListOptions;
import com.pijava.agent.session.memory.MemorySessionMetadata;
import com.pijava.agent.session.memory.MemorySessionRepository;

/** Memory-backed conformance fixture. */
public final class MemoryConformanceBackend implements ConformanceBackend {

    private final MemorySessionRepository repository = new MemorySessionRepository();
    private final Map<String, MemorySessionMetadata> metadataById = new ConcurrentHashMap<>();

    @Override
    public Session<?> create(String cwd) {
        return create(cwd, null);
    }

    @Override
    public Session<?> create(String cwd, String id) {
        var session = repository.create(new MemorySessionCreateOptions(id, cwd, null));
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
        return repository.list(MemorySessionListOptions.all());
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
            new MemorySessionCreateOptions(null, "cwd", null));
        metadataById.put(forked.getMetadata().id(), forked.getMetadata());
        return forked;
    }

    @Override
    public void close() {
    }
}
