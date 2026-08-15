package com.pijava.session.sqlite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * SQLite schema migrations (aligned with pi {@code migrations.ts}). The
 * {@code migrations} table records applied ids; {@code 001_initial.sql} is
 * applied first and idempotently.
 */
public final class Migrations {

    private static final List<String> MIGRATIONS = List.of("001_initial.sql");

    private Migrations() {}

    /** Apply all pending migrations inside transactions. */
    public static void applyMigrations(SqliteDatabase db) {
        ensureMigrationsTable(db);
        var applied = db.all("SELECT id FROM migrations ORDER BY applied_at, id",
            rs -> rs.getString("id"));
        for (var id : MIGRATIONS) {
            if (applied.contains(id)) {
                continue;
            }
            db.transaction(() -> {
                db.exec(loadMigration(id));
                db.run("INSERT INTO migrations (id, applied_at) VALUES (?, ?)",
                    id, Instant.now().toString());
            });
            applied.add(id);
        }
    }

    private static void ensureMigrationsTable(SqliteDatabase db) {
        db.exec("""
            CREATE TABLE IF NOT EXISTS migrations (
                id TEXT PRIMARY KEY,
                applied_at TEXT NOT NULL
            )
            """);
    }

    private static String loadMigration(String id) {
        try (InputStream in = Migrations.class.getResourceAsStream("/sql/" + id)) {
            if (in == null) {
                throw new IllegalStateException("Missing migration resource /sql/" + id);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load migration " + id, e);
        }
    }
}