package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.session.SessionMetadata;
import com.pijava.agent.session.jsonl.JsonlSessionMetadata;
import org.jspecify.annotations.Nullable;

/**
 * A resumed session whose recorded working directory no longer exists
 * (pi {@code core/session-cwd.ts:40-57}, anchor {@code a00a5048d}; L5 W3).
 *
 * <p>Only JSONL sessions carry a durable cwd; other backends keep the existing
 * behavior (a null/blank cwd is not an issue).</p>
 */
public final class SessionCwd {

    /** The missing directory and the fallback offered instead. */
    public record Issue(String sessionCwd, String fallbackCwd) {}

    /** What the user chose. */
    public enum Choice {
        /** Continue in the fallback directory, asking again next time. */
        CONTINUE,
        /** Fork into the fallback directory; a new session id, the original kept. */
        FORK,
        /** Exit without opening anything. */
        CANCEL
    }

    /** One selector entry; {@link Choice#CANCEL} carries a null value in pi. */
    public record Option(String label, @Nullable Choice value) {}

    private SessionCwd() {}

    /**
     * The issue when {@code metadata}'s recorded cwd no longer exists, else null.
     *
     * @param metadata    the session to inspect
     * @param fallbackCwd the current working directory offered instead
     */
    public static @Nullable Issue issueFor(SessionMetadata metadata, String fallbackCwd) {
        if (!(metadata instanceof JsonlSessionMetadata)) {
            return null;
        }
        var cwd = ((JsonlSessionMetadata) metadata).cwd();
        if (cwd == null || cwd.isBlank()) {
            return null;
        }
        if (Files.exists(Path.of(cwd))) {
            return null;
        }
        return new Issue(cwd, fallbackCwd);
    }

    /** pi {@code formatMissingSessionCwdPrompt}. */
    public static String formatPrompt(Issue issue) {
        return "Session cwd does not exist: " + issue.sessionCwd();
    }

    /** pi {@code getMissingSessionCwdOptions} 三臂，逐字。 */
    public static List<Option> options(Issue issue) {
        return List.of(
            new Option("Continue in " + issue.fallbackCwd() + " (asks again next time)",
                Choice.CONTINUE),
            new Option("Fork into " + issue.fallbackCwd()
                + " (new session ID, original kept)", Choice.FORK),
            new Option("Cancel", null));
    }
}
