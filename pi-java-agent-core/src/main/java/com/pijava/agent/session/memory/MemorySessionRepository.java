package com.pijava.agent.session.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.pijava.agent.session.ForkOptions;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionRepository;
import com.pijava.agent.session.UuidV7;

/**
 * In-memory session repository: conformance oracle and test double.
 */
public final class MemorySessionRepository implements
    SessionRepository<MemorySessionMetadata, MemorySessionCreateOptions, MemorySessionListOptions> {

    private final Map<String, MemorySessionStorage> storages = new ConcurrentHashMap<>();

    @Override
    public Session<MemorySessionMetadata> create(MemorySessionCreateOptions options) {
        String id = options.id() != null ? options.id() : UuidV7.INSTANCE.next();
        if (storages.containsKey(id)) {
            throw new SessionError(SessionErrorCode.ALREADY_EXISTS, "Session already exists: " + id);
        }
        var metadata = new MemorySessionMetadata(id, Instant.now(),
            options.parentSessionId(), options.cwd());
        var storage = new MemorySessionStorage(metadata);
        storages.put(id, storage);
        return new Session<>(storage);
    }

    @Override
    public Session<MemorySessionMetadata> open(MemorySessionMetadata metadata) {
        var storage = storages.get(metadata.id());
        if (storage == null) {
            throw new SessionError(SessionErrorCode.NOT_FOUND, "Session not found: " + metadata.id());
        }
        return new Session<>(storage);
    }

    @Override
    public List<MemorySessionMetadata> list(MemorySessionListOptions options) {
        List<MemorySessionMetadata> result = new ArrayList<>();
        for (var storage : storages.values()) {
            var metadata = storage.getMetadata();
            if (options.cwd() == null || options.cwd().equals(metadata.cwd())) {
                result.add(metadata);
            }
        }
        result.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        return result;
    }

    @Override
    public void delete(MemorySessionMetadata metadata) {
        storages.remove(metadata.id());
    }

    @Override
    public Session<MemorySessionMetadata> fork(MemorySessionMetadata source, ForkOptions options,
                                               MemorySessionCreateOptions createOptions) {
        var sourceStorage = storages.get(source.id());
        if (sourceStorage == null) {
            throw new SessionError(SessionErrorCode.NOT_FOUND, "Session not found: " + source.id());
        }
        String id = createOptions.id() != null ? createOptions.id() : UuidV7.INSTANCE.next();
        if (storages.containsKey(id)) {
            throw new SessionError(SessionErrorCode.ALREADY_EXISTS, "Session already exists: " + id);
        }
        var metadata = new MemorySessionMetadata(id, Instant.now(),
            createOptions.parentSessionId() != null ? createOptions.parentSessionId() : source.id(),
            createOptions.cwd());
        var storage = sourceStorage.fork(metadata, options);
        storages.put(id, storage);
        return new Session<>(storage);
    }
}