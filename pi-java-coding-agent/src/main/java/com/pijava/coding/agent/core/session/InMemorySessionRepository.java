package com.pijava.coding.agent.core.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.pijava.agent.harness.AgentHarness;
import com.pijava.agent.harness.LaneConfig;
import com.pijava.coding.agent.core.AgentSession;

/**
 * In-memory session registry (Phase 3 design §11.5).
 *
 * <p>Same-process {@code -c/-r/--fork} and {@code /new /fork /clone /resume}
 * work against this registry; process exit loses everything. Phase 4 replaces
 * this with a persistent SessionRepository (SQLite/JSONL) behind the same API.</p>
 */
public final class InMemorySessionRepository {

    private static final InMemorySessionRepository SHARED =
        new InMemorySessionRepository();

    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private String latestId;

    private InMemorySessionRepository() {}

    /** Create a fresh registry (tests and isolated scopes). */
    public static InMemorySessionRepository create() {
        return new InMemorySessionRepository();
    }

    /**
     * The process-wide registry: every {@link AgentSession#create} registers
     * here, so {@code -c/-r/--fork} and {@code /session} work within the
     * process (Phase 4 replaces this with persistent storage).
     */
    public static InMemorySessionRepository shared() {
        return SHARED;
    }

    /** Create a new session entry (called by {@link AgentSession#create}). */
    public AgentSession create(AgentSession session) {
        var id = UUID.randomUUID().toString();
        sessions.put(id, new SessionEntry(id, session, Instant.now()));
        latestId = id;
        return session;
    }

    /**
     * Register a session under an exact caller-supplied ID ({@code --session-id},
     * "create if missing" per Phase 3 design §9.1).
     */
    public AgentSession createWithId(AgentSession session, String id) {
        sessions.put(id, new SessionEntry(id, session, Instant.now()));
        latestId = id;
        return session;
    }

    /** The most recent session, if any ({@code -c}). */
    public Optional<AgentSession> latest() {
        return latestId == null ? Optional.empty()
            : Optional.ofNullable(sessions.get(latestId)).map(e -> e.session);
    }

    /** Find a session by exact ID or unique prefix ({@code -r/--session}). */
    public Optional<AgentSession> find(String idOrPrefix) {
        if (idOrPrefix == null || idOrPrefix.isBlank()) {
            return Optional.empty();
        }
        return sessions.entrySet().stream()
            .filter(e -> e.getKey().equals(idOrPrefix)
                || e.getKey().startsWith(idOrPrefix))
            .map(e -> e.getValue().session)
            .findFirst();
    }

    /** Fork an existing session under a new ID ({@code --fork /fork /clone}). */
    public AgentSession fork(AgentSession source, String branchName) {
        var forked = source.forkCopy(branchName);
        return create(forked);
    }

    /** List all process-local sessions for selectors and {@code /session}. */
    public List<SessionInfo> list() {
        var result = new ArrayList<SessionInfo>();
        for (var entry : sessions.entrySet()) {
            result.add(new SessionInfo(
                entry.getKey(),
                entry.getValue().session.sessionName(),
                entry.getValue().createdAt,
                entry.getValue().session.entryCount()));
        }
        return List.copyOf(result);
    }

    /** Register a lane on an existing session (fork target support). */
    public void ensureLane(AgentSession session, String laneName) {
        session.harness().createLane(LaneConfig.of(laneName));
    }

    private record SessionEntry(
        String id,
        AgentSession session,
        Instant createdAt
    ) {}
}
