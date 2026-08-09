/**
 * SQLite session storage backend for pi-java.
 *
 * <p>Implements {@link com.pijava.agent.session.SessionStorage} and
 * {@link com.pijava.agent.session.SessionRepository} on top of SQLite.
 * Full implementation in Phase 4.</p>
 */
module com.pijava.session.backend.sqlite {
    requires com.pijava.agent;
    requires java.sql;

    // exports com.pijava.session.backend.sqlite; — Phase 4
}
