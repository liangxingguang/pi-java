package com.pijava.coding.agent.core;

import java.time.Instant;
import java.util.Map;

import com.pijava.agent.session.jsonl.JsonlSessionMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCwdTest {

    @TempDir
    Path tmp;

    private JsonlSessionMetadata metadata(String cwd) {
        return new JsonlSessionMetadata("s1", Instant.EPOCH, null, cwd,
            tmp.resolve("session.jsonl"), 0, false, null, Map.of());
    }

    @Test
    void existingCwdHasNoIssue() {
        assertThat(SessionCwd.issueFor(metadata(tmp.toString()), tmp.toString())).isNull();
    }

    @Test
    void missingCwdReportsTheStoredValueAndTheFallback() {
        var missing = tmp.resolve("gone");
        var issue = SessionCwd.issueFor(metadata(missing.toString()), tmp.toString());

        assertThat(issue).isNotNull();
        assertThat(issue.sessionCwd()).isEqualTo(missing.toString());
        assertThat(issue.fallbackCwd()).isEqualTo(tmp.toString());
        assertThat(SessionCwd.formatPrompt(issue)).isEqualTo(
            "Session cwd does not exist: " + missing);
    }

    @Test
    void nonJsonlOrBlankCwdIsNotAnIssue() {
        assertThat(SessionCwd.issueFor(metadata(null), tmp.toString())).isNull();
        assertThat(SessionCwd.issueFor(metadata("  "), tmp.toString())).isNull();
    }

    @Test
    void optionsAreTheThreePiLabels() {
        var issue = new SessionCwd.Issue("old", "new");

        assertThat(SessionCwd.options(issue)).extracting(SessionCwd.Option::label)
            .containsExactly(
                "Continue in new (asks again next time)",
                "Fork into new (new session ID, original kept)",
                "Cancel");
        assertThat(SessionCwd.options(issue)).extracting(SessionCwd.Option::value)
            .containsExactly(SessionCwd.Choice.CONTINUE, SessionCwd.Choice.FORK, null);
    }
}
