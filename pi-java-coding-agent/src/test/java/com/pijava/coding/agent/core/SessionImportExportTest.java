package com.pijava.coding.agent.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.Session;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.jsonl.JsonlCodec;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 §4.7 import/export across the JSONL and SQLite backends.
 */
class SessionImportExportTest {

    private static ProvisionedEntry<Entry.Message> message(String id, String text) {
        return new ProvisionedEntry<>(new Entry.Message(id, 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null));
    }

    @Test
    void jsonlExportThenImportRoundTrips() throws Exception {
        Path root = Files.createTempDirectory("pi-io-jsonl");
        var handle = PersistentSessionRepositories.jsonl(root);
        try {
            var session = handle.create("cwd", null);
            session.appendEntry(message("m1", "first"), "main");
            session.appendEntry(message("m2", "second"), "main");
            session.setName("io-test");

            Path exported = Files.createTempFile("pi-io", ".jsonl");
            handle.exportJsonl(session, exported);
            var lines = Files.readAllLines(exported);
            assertThat(lines).isNotEmpty();
            assertThat(JsonlCodec.parseHeader(lines.getFirst()).ok()).isTrue();

            // Import into a fresh root and reopen.
            Path root2 = Files.createTempDirectory("pi-io-jsonl2");
            var handle2 = PersistentSessionRepositories.jsonl(root2);
            var imported = handle2.importJsonl(exported, "cwd");
            assertThat(imported.getMetadata().id()).isEqualTo(session.getMetadata().id());
            assertThat(imported.findEntries(EntryQuery.all()))
                .extracting(Entry::id).containsExactly("m2", "m1");
            assertThat(imported.getName()).isEqualTo("io-test");

            // Duplicate id import is rejected.
            assertThatThrownBy(() -> handle2.importJsonl(exported, "cwd"))
                .extracting(e -> ((SessionError) e).code())
                .isEqualTo(SessionErrorCode.ALREADY_EXISTS);
        } finally {
            handle.close();
        }
    }

    @Test
    void sqliteExportThenImportReplays() throws Exception {
        Path db = Files.createTempDirectory("pi-io-sqlite").resolve("s.db");
        var handle = PersistentSessionRepositories.sqlite(db);
        try {
            var session = handle.create("cwd", null);
            session.appendEntry(message("m1", "first"), "main");
            session.appendEntry(message("m2", "second"), "main");
            session.setName("sqlite-io");

            Path exported = Files.createTempFile("pi-io-sqlite", ".jsonl");
            handle.exportJsonl(session, exported);
            var lines = Files.readAllLines(exported);
            assertThat(JsonlCodec.parseHeader(lines.getFirst()).ok()).isTrue();
            for (int i = 1; i < lines.size(); i++) {
                assertThat(JsonlCodec.parseMutation(lines.get(i)).ok())
                    .as("line %d parses", i + 1).isTrue();
            }

            // Import into a second database replays mutations.
            Path db2 = Files.createTempDirectory("pi-io-sqlite2").resolve("s.db");
            var handle2 = PersistentSessionRepositories.sqlite(db2);
            var imported = handle2.importJsonl(exported, "cwd");
            assertThat(imported.findEntries(EntryQuery.all()))
                .extracting(Entry::id).containsExactly("m2", "m1");
            assertThat(imported.getName()).isEqualTo("sqlite-io");
            handle2.close();
        } finally {
            handle.close();
        }
    }

    @Test
    void sqliteImportPreservesSeqAndTimestamp() throws Exception {
        Path db = Files.createTempDirectory("pi-io-sqlite-seq").resolve("s.db");
        var handle = PersistentSessionRepositories.sqlite(db);
        try {
            var session = handle.create("cwd", null);
            session.appendEntry(message("m1", "first"), "main");
            session.appendEntry(message("m2", "second"), "main");

            var m1Before = byId(session.findEntries(EntryQuery.all()), "m1");
            var m2Before = byId(session.findEntries(EntryQuery.all()), "m2");

            Path exported = Files.createTempFile("pi-io-seq", ".jsonl");
            handle.exportJsonl(session, exported);

            Path db2 = Files.createTempDirectory("pi-io-sqlite-seq2").resolve("s.db");
            var handle2 = PersistentSessionRepositories.sqlite(db2);
            var imported = handle2.importJsonl(exported, "cwd");

            var m1After = byId(imported.findEntries(EntryQuery.all()), "m1");
            var m2After = byId(imported.findEntries(EntryQuery.all()), "m2");
            assertThat(m1After.seq()).isEqualTo(m1Before.seq());
            assertThat(m2After.seq()).isEqualTo(m2Before.seq());
            assertThat(m1After.timestamp()).isEqualTo(m1Before.timestamp());
            assertThat(m2After.timestamp()).isEqualTo(m2Before.timestamp());
            handle2.close();
        } finally {
            handle.close();
        }
    }

    private static Entry byId(List<Entry> entries, String id) {
        return entries.stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }
}
