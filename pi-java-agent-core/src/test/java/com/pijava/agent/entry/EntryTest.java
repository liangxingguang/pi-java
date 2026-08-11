package com.pijava.agent.entry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.ai.message.ContentBlock;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EntryTest {

    @Test
    void entryHeaderFields() {
        var now = Instant.now();
        var header = new EntryHeader("id-1", 0L, "", now);

        assertThat(header.id()).isEqualTo("id-1");
        assertThat(header.seq()).isEqualTo(0L);
        assertThat(header.parentId()).isEmpty();
        assertThat(header.timestamp()).isEqualTo(now);
    }

    @Test
    void newHeaderGeneratesUuid() {
        var header = Entry.newHeader(5L, "parent-1");

        assertThat(header.id()).isNotEmpty();
        assertThat(header.seq()).isEqualTo(5L);
        assertThat(header.parentId()).isEqualTo("parent-1");
        assertThat(header.timestamp()).isNotNull();
    }

    @Test
    void messageEntry() {
        var header = Entry.newHeader(0L, "");
        var blocks = List.<ContentBlock>of(new ContentBlock.TextContent("Hello"));
        var msg = new Entry.Message(header, "user", blocks);

        assertThat(msg.header()).isSameAs(header);
        assertThat(msg.role()).isEqualTo("user");
        assertThat(msg.blocks()).hasSize(1);
    }

    @Test
    void messageEntryDefensiveCopy() {
        var header = Entry.newHeader(0L, "");
        var blocks = new java.util.ArrayList<>(List.<ContentBlock>of(
                new ContentBlock.TextContent("test")));
        var msg = new Entry.Message(header, "assistant", blocks);

        blocks.clear();
        assertThat(msg.blocks()).hasSize(1);
    }

    @Test
    void thinkingLevelChangeEntry() {
        var header = Entry.newHeader(1L, "parent");
        var entry = new Entry.ThinkingLevelChange(header, "medium");

        assertThat(entry.header()).isSameAs(header);
        assertThat(entry.level()).isEqualTo("medium");
    }

    @Test
    void modelChangeEntry() {
        var header = Entry.newHeader(2L, "parent");
        var entry = new Entry.ModelChange(header, "anthropic", "claude-sonnet-4-6");

        assertThat(entry.provider()).isEqualTo("anthropic");
        assertThat(entry.modelId()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void activeToolsChangeEntry() {
        var header = Entry.newHeader(3L, "parent");
        var tools = new java.util.ArrayList<>(List.of("bash", "read"));
        var entry = new Entry.ActiveToolsChange(header, tools);

        tools.clear();
        assertThat(entry.toolNames()).containsExactly("bash", "read");
    }

    @Test
    void compactionEntry() {
        var header = Entry.newHeader(4L, "parent");
        var entry = new Entry.Compaction(header, "overflow", 100, 50);

        assertThat(entry.reason()).isEqualTo("overflow");
        assertThat(entry.entriesBefore()).isEqualTo(100);
        assertThat(entry.entriesAfter()).isEqualTo(50);
    }

    @Test
    void branchSummaryEntry() {
        var header = Entry.newHeader(5L, "parent");
        var entry = new Entry.BranchSummary(header, "Summary text");

        assertThat(entry.summary()).isEqualTo("Summary text");
    }

    @Test
    void customEntry() {
        var header = Entry.newHeader(6L, "parent");
        var data = new java.util.HashMap<String, Object>(Map.of("key", "value"));
        var entry = new Entry.Custom(header, "my-event", data);

        data.put("key", "modified");
        assertThat(entry.data()).containsEntry("key", "value");
        assertThat(entry.kind()).isEqualTo("my-event");
    }

    @Test
    void provisionedEntry() {
        var header = Entry.newHeader(0L, "");
        var entry = new Entry.Message(header, "user",
                List.of(new ContentBlock.TextContent("hi")));
        var pw = new ProvisionedEntry(entry);

        assertThat(pw.entry()).isSameAs(entry);
        assertThat(pw.isWritten()).isFalse();

        pw.markWritten();
        assertThat(pw.isWritten()).isTrue();
    }
}
