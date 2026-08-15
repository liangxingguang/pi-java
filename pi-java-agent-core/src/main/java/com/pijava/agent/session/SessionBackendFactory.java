package com.pijava.agent.session;

import java.nio.file.Path;

/**
 * SPI for selecting a session backend at runtime (agent-core defines the
 * interface; the sqlite module registers an implementation via
 * {@code META-INF/services}). Keeps coding-agent free of a compile-time
 * dependency on the sqlite module.
 */
public interface SessionBackendFactory {

    /** Backend name, e.g. {@code "sqlite"}. */
    String name();

    /** Create a repository over the given database path. */
    SessionRepository<?, ?, ?> create(Path dbPath);
}