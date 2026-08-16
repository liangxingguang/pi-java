package com.pijava.session.sqlite;

import java.nio.file.Path;

import com.pijava.agent.session.SessionBackendFactory;
import com.pijava.agent.session.SessionRepository;

/** ServiceLoader entry for the {@code "sqlite"} session backend. */
public final class SqliteSessionBackendFactory implements SessionBackendFactory {

    @Override
    public String name() {
        return "sqlite";
    }

    @Override
    public SessionRepository<?, ?, ?> create(Path dbPath) {
        return new SqliteSessionRepository(dbPath);
    }
}
