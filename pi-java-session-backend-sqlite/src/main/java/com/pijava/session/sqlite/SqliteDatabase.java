package com.pijava.session.sqlite;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Thin sqlite-jdbc wrapper: parameter binding, row mapping and transactions.
 * Equivalent to pi's {@code SqliteDatabase} abstraction.
 */
public final class SqliteDatabase implements AutoCloseable {

    private final Connection connection;

    private SqliteDatabase(Connection connection) {
        this.connection = connection;
    }

    /** Open (creating if needed) the database at {@code path}. */
    public static SqliteDatabase open(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Class.forName("org.sqlite.JDBC");
            var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            return new SqliteDatabase(connection);
        } catch (Exception e) {
            throw new SqliteException("Failed to open SQLite database " + path, e);
        }
    }

    /** Open an in-memory database (tests). */
    public static SqliteDatabase inMemory() {
        try {
            Class.forName("org.sqlite.JDBC");
            return new SqliteDatabase(DriverManager.getConnection("jdbc:sqlite::memory:"));
        } catch (Exception e) {
            throw new SqliteException("Failed to open in-memory SQLite database", e);
        }
    }

    /** Execute a statement without parameters. */
    public void exec(String sql) {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new SqliteException("Failed to execute SQL: " + sql, e);
        }
    }

    /** Execute an update with parameters; returns the change count. */
    public int run(String sql, Object... params) {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new SqliteException("Failed to run SQL: " + sql, e);
        }
    }

    /** Execute a query and map all rows. */
    public <T> List<T> all(String sql, RowMapper<T> mapper, Object... params) {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new SqliteException("Failed to query SQL: " + sql, e);
        }
    }

    /** Execute a query and map the first row, if any. */
    public <T> Optional<T> get(String sql, RowMapper<T> mapper, Object... params) {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(mapper.map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new SqliteException("Failed to query SQL: " + sql, e);
        }
    }

    /** Run {@code operation} inside a transaction; commit on success, rollback on failure. */
    public <T> T transaction(Supplier<T> operation) {
        boolean previousAutoCommit;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
        } catch (SQLException e) {
            throw new SqliteException("Failed to start transaction", e);
        }
        try {
            T result = operation.get();
            connection.commit();
            return result;
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw new SqliteException("Failed to commit transaction", e);
        } catch (RuntimeException | Error e) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException e) {
                throw new SqliteException("Failed to restore auto-commit", e);
            }
        }
    }

    /** Run {@code operation} inside a transaction; commit on success, rollback on failure. */
    public void transaction(Runnable operation) {
        transaction(() -> {
            operation.run();
            return null;
        });
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new SqliteException("Failed to close SQLite database", e);
        }
    }

    private static void bind(java.sql.PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    /** Maps a result-set row to a value. */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    /** SQLite access failure. */
    public static final class SqliteException extends RuntimeException {
        public SqliteException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}