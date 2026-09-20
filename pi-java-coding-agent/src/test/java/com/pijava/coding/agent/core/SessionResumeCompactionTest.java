package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.coding.agent.cli.ArgsParser;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resume must seed the lane with the compaction-aware context (pi
 * {@code buildSessionContext()}), not all persisted entries — otherwise the
 * compacted prefix is re-inflated on every restart. Display paths still see
 * full history from storage.
 */
class SessionResumeCompactionTest {

    /**
     * The cwd the seeded session is created under — must match what
     * {@code resolvePersistentWeb} looks in ({@code user.dir}), because sessions
     * are stored per project directory (docs/39 §6.1).
     */
    private static final String CWD = System.getProperty("user.dir");

    private static ProvisionedEntry<Entry.Message> message(String id, String parentId, String text) {
        return new ProvisionedEntry<>(new Entry.Message(id, 0, parentId, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null));
    }

    private static ProvisionedEntry<Entry.Compaction> compaction(String id, String parentId,
                                                                 String firstKeptId) {
        return new ProvisionedEntry<>(new Entry.Compaction(id, 0, parentId, null,
            "summary-text", firstKeptId, List.of(), 1000, null, null));
    }

    @Test
    void attachSeedsCompactedContextNotAllEntries() throws Exception {
        Path root = Files.createTempDirectory("pi-resume-compaction");
        // Seed a compacted session: [m1, m2, c1, m3]
        var handle = PersistentSessionRepositories.jsonl(root);
        try {
            var session = handle.create(CWD, null);
            session.appendEntry(message("m1", null, "q1"), "main");
            session.appendEntry(message("m2", "m1", "r1"), "main");
            session.appendEntry(compaction("c1", "m2", "m2"), "main");
            session.appendEntry(message("m3", "c1", "q2"), "main");

            var resumed = AgentSession.createWeb(ArgsParser.parse(new String[] {
                "--session-dir", root.toString()}));
            try {
                var transcript = resumed.harness().snapshot(resumed.laneName()).transcript();
                // Lane context = compaction-aware subset: [c1, m2, m3] — firstKept
                // is m2, so m2 is retained; only m1 (summarized away) is dropped
                assertThat(transcript).extracting(Entry::id).containsExactly("c1", "m2", "m3");
                // Persistence bookkeeping still covers ALL ids (append-only dedup)
                assertThat(resumed.persistedEntryIds()).containsExactlyInAnyOrder(
                    "m1", "m2", "c1", "m3");
                // Display path keeps full history from storage
                assertThat(resumed.accumulatedEntries()).extracting(Entry::id)
                    .containsExactly("m1", "m2", "c1", "m3");
            } finally {
                resumed.close();
            }
        } finally {
            handle.close();
        }
    }

    @Test
    void repersistAfterAttachDoesNotDuplicateCompaction() throws Exception {
        Path root = Files.createTempDirectory("pi-resume-idem");
        // Seed a compacted session: [m1, c1, m2]
        var handle = PersistentSessionRepositories.jsonl(root);
        try {
            var session = handle.create(CWD, null);
            session.appendEntry(message("m1", null, "q1"), "main");
            session.appendEntry(compaction("c1", "m1", "m1"), "main");
            session.appendEntry(message("m2", "c1", "q2"), "main");
            var before = handle.open(session.getMetadata())
                .findEntries(new com.pijava.agent.session.EntryQuery(
                    null, null, com.pijava.agent.session.EntryOrder.OLDEST_FIRST, null, null))
                .size();

            // Resume (seeds lane via attach) then close (flushes persistPending)
            var resumed = AgentSession.createWeb(ArgsParser.parse(new String[] {
                "--session-dir", root.toString()}));
            resumed.close();

            var after = handle.open(session.getMetadata())
                .findEntries(new com.pijava.agent.session.EntryQuery(
                    null, null, com.pijava.agent.session.EntryOrder.OLDEST_FIRST, null, null))
                .size();
            assertThat(after).isEqualTo(before);
        } finally {
            handle.close();
        }
    }
}
