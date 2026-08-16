package com.pijava.session.sqlite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.agent.session.EntryOrder;
import com.pijava.agent.session.EntryQuery;
import com.pijava.agent.session.SessionError;
import com.pijava.agent.session.SessionErrorCode;
import com.pijava.agent.session.SessionSearchHit;
import com.pijava.agent.session.SessionSearchOptions;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SQLite-specific: migrations idempotency, lease fencing, FTS5 search. */
class SqliteLeaseAndSearchTest {

    private static ProvisionedEntry<Entry.Message> message(String id, String text) {
        return new ProvisionedEntry<>(new Entry.Message(id, 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent(text))), null));
    }

    @Test
    void migrationsAreIdempotent() throws Exception {
        Path db = Files.createTempDirectory("pi-sqlite-mig").resolve("s.db");
        var repo1 = SqliteSessionRepository.open(db);
        repo1.create(new SqliteSessionCreateOptions(null, "cwd", null, null));
        repo1.close();
        var repo2 = SqliteSessionRepository.open(db);
        assertThat(repo2.list(SqliteSessionListOptions.all())).hasSize(1);
        repo2.close();
    }

    @Test
    void expiredLeaseCanBePreemptedAndFenceInvalidatesOldWriter() throws Exception {
        Path db = Files.createTempDirectory("pi-sqlite-lease").resolve("s.db");
        var db1 = SqliteDatabase.open(db);
        var db2 = SqliteDatabase.open(db);
        Migrations.applyMigrations(db1);
        try {
            long t0 = System.currentTimeMillis();
            var lease1 = WriterLease.acquire(db1, "s", "owner1", t0, 50).orElseThrow();

            // An unexpired lease blocks a second holder.
            assertThat(WriterLease.acquire(db2, "s", "owner2", t0 + 10, 60)).isEmpty();

            // After expiry, the second holder preempts and the fence bumps.
            var lease2 = WriterLease.acquire(db2, "s", "owner2", t0 + 100, 50).orElseThrow();
            assertThat(lease2.fence()).isEqualTo(2);

            // The old holder's renew/write fails (fence mismatch).
            assertThat(WriterLease.renew(db1, "s", lease1, t0 + 110, 50)).isFalse();
            assertThat(WriterLease.renew(db2, "s", lease2, t0 + 110, 50)).isTrue();
        } finally {
            db1.close();
            db2.close();
        }
    }

    @Test
    void fts5SearchFindsPhrasesAndFiltersByCwd() throws Exception {
        Path db = Files.createTempDirectory("pi-sqlite-search").resolve("s.db");
        var repo = SqliteSessionRepository.open(db);
        var session = repo.create(new SqliteSessionCreateOptions(null, "cwd-a", null, null));
        session.appendEntry(message("m1", "fix the login bug"), "main");
        repo.create(new SqliteSessionCreateOptions(null, "cwd-b", null, null));

        var search = SqliteSessionSearch.create(db);
        var hits = search.search(SessionSearchOptions.all("login bug"));
        assertThat(hits).extracting(SessionSearchHit::entryId).contains("m1");
        var filtered = search.search(new SessionSearchOptions("login bug", "cwd-b"));
        assertThat(filtered).isEmpty();
        search.close();
        repo.close();
    }

    @Test
    void branchCacheRebuildAfterFork() throws Exception {
        Path db = Files.createTempDirectory("pi-sqlite-branch").resolve("s.db");
        var repo = SqliteSessionRepository.open(db);
        var session = repo.create(new SqliteSessionCreateOptions(null, "cwd", null, null));
        session.appendEntry(message("m1", "root"), "main");
        session.appendEntry(message("m2", "child"), "main");
        var metadata = session.getMetadata();
        var forked = repo.fork(metadata, new com.pijava.agent.session.ForkOptions.Tree(),
            new SqliteSessionCreateOptions(null, "cwd", null, null));
        assertThat(forked.findEntries(new EntryQuery(null, null, EntryOrder.OLDEST_FIRST, null, null)))
            .extracting(Entry::id).containsExactly("m1", "m2");
        repo.close();
    }
}
