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
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
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

    /**
     * Execute statements without parameters. The input may contain multiple
     * statements separated by {@code ';'} — JDBC {@code Statement.execute}
     * only runs the first, so each is executed individually. The splitter
     * ignores semicolons inside single-quoted strings and inside
     * {@code BEGIN...END} trigger bodies.
     *
     * <p>The SQL is always internal (PRAGMAs, resources, SAVEPOINTs), never
     * user input; SpotBugs cannot see that through the String parameter.</p>
     */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
        value = "SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE",
        justification = "exec receives only internal DDL/PRAGMA/SAVEPOINT statements")
    public void exec(String sql) {
        try (Statement statement = connection.createStatement()) {
            for (var part : splitStatements(sql)) {
                statement.execute(part);
            }
        } catch (SQLException e) {
            throw new SqliteException("Failed to execute SQL: " + sql, e);
        }
    }

    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        int beginDepth = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\'' && i > 0 && sql.charAt(i - 1) != '\\') {
                    inString = false;
                }
                continue;
            }
            if (c == '\'') {
                inString = true;
                current.append(c);
                continue;
            }
            if (c == ';' && beginDepth == 0) {
                if (!current.toString().isBlank()) {
                    statements.add(current.toString());
                }
                current.setLength(0);
                continue;
            }
            if (Character.isLetter(c)) {
                int end = i;
                while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                    end++;
                }
                String word = sql.substring(i, end).toLowerCase();
                if ("begin".equals(word)) {
                    beginDepth++;
                } else if ("end".equals(word) && beginDepth > 0) {
                    beginDepth--;
                }
                current.append(sql, i, end);
                i = end - 1;
                continue;
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }
        return statements;
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
                return rs.next() ? Optional.ofNullable(mapper.map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new SqliteException("Failed to query SQL: " + sql, e);
        }
    }

    /** Current transaction nesting depth (single-threaded connection). */
    private int txDepth;

    /**
     * Run {@code operation} inside a transaction; commit on success, rollback
     * on failure. Nested calls use SAVEPOINTs so inner work can roll back
     * without aborting the outer transaction.
     */
    public <T> T transaction(Supplier<T> operation) {
        boolean top = txDepth == 0;
        try {
            if (top) {
                connection.setAutoCommit(false);
            } else {
                connection.createStatement().execute("SAVEPOINT pi_tx_" + txDepth);
            }
            txDepth++;
        } catch (SQLException e) {
            throw new SqliteException("Failed to start transaction", e);
        }
        try {
            T result = operation.get();
            if (top) {
                connection.commit();
            } else {
                connection.createStatement().execute("RELEASE SAVEPOINT pi_tx_" + (txDepth - 1));
            }
            return result;
        } catch (SQLException e) {
            try {
                if (top) {
                    connection.rollback();
                } else {
                    connection.createStatement().execute("ROLLBACK TO SAVEPOINT pi_tx_" + (txDepth - 1));
                }
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw new SqliteException("Failed to commit transaction", e);
        } catch (RuntimeException | Error e) {
            try {
                if (top) {
                    connection.rollback();
                } else {
                    connection.createStatement().execute("ROLLBACK TO SAVEPOINT pi_tx_" + (txDepth - 1));
                }
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            txDepth--;
            if (top) {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException e) {
                    throw new SqliteException("Failed to restore auto-commit", e);
                }
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
