package com.pijava.agent.entry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pijava.ai.message.Message;
import com.pijava.ai.message.ContentBlock;
import com.pijava.agent.session.SessionJson;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EntryTest {

    @Test
    void messageEntryFlatFields() {
        var now = Instant.now();
        var blocks = List.<ContentBlock>of(new ContentBlock.TextContent("Hello"));
        var msg = new Entry.Message("id-1", 1L, "parent", now,
            new Message.UserMessage(blocks), null);

        assertThat(msg.id()).isEqualTo("id-1");
        assertThat(msg.seq()).isEqualTo(1L);
        assertThat(msg.parentId()).isEqualTo("parent");
        assertThat(msg.timestamp()).isEqualTo(now);
        assertThat(msg.message().role()).isEqualTo("user");
        assertThat(msg.message().content()).hasSize(1);
        assertThat(msg.terminate()).isNull();
    }

    @Test
    void messageEntryPreservesPayloadOnCommit() {
        var now = Instant.now();
        var msg = new Entry.Message("id-1", 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))), null);
        Entry committed = msg.committed(5L, "leaf", now);

        assertThat(committed.id()).isEqualTo("id-1");
        assertThat(committed.seq()).isEqualTo(5L);
        assertThat(committed.parentId()).isEqualTo("leaf");
        assertThat(committed.timestamp()).isEqualTo(now);
        assertThat(((Entry.Message) committed).message().content()).hasSize(1);
    }

    @Test
    void thinkingLevelChangeEntry() {
        var entry = new Entry.ThinkingLevelChange("id-1", 1L, "parent",
            Instant.now(), "medium");
        assertThat(entry.thinkingLevel()).isEqualTo("medium");
        assertThat(entry.type()).isEqualTo("thinking_level_change");
    }

    @Test
    void modelChangeEntry() {
        var entry = new Entry.ModelChange("id-1", 2L, "parent",
            Instant.now(), "anthropic", "claude-sonnet-4-6");
        assertThat(entry.provider()).isEqualTo("anthropic");
        assertThat(entry.modelId()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void activeToolsChangeEntryDefensiveCopy() {
        var tools = new java.util.ArrayList<>(List.of("bash", "read"));
        var entry = new Entry.ActiveToolsChange("id-1", 3L, "parent",
            Instant.now(), tools);
        tools.clear();
        assertThat(entry.activeToolNames()).containsExactly("bash", "read");
    }

    @Test
    void compactionEntry() {
        var entry = new Entry.Compaction("id-1", 4L, "parent", Instant.now(),
            "summary", List.of(), 100,
            Map.of("readFiles", List.of("a.txt")), null);
        assertThat(entry.summary()).isEqualTo("summary");
        assertThat(entry.tokensBefore()).isEqualTo(100);
        assertThat(entry.retainedTail()).isEmpty();
    }

    @Test
    void branchSummaryEntry() {
        var entry = new Entry.BranchSummary("id-1", 5L, "parent", Instant.now(),
            "from-1", "Summary text", null, null);
        assertThat(entry.summary()).isEqualTo("Summary text");
        assertThat(entry.fromId()).isEqualTo("from-1");
    }

    @Test
    void customEntryDefensiveCopy() {
        var data = new java.util.HashMap<String, Object>(Map.of("key", "value"));
        var entry = new Entry.Custom("id-1", 6L, "parent", Instant.now(), "my-event", data);
        data.put("key", "modified");
        assertThat(entry.data()).containsEntry("key", "value");
        assertThat(entry.customType()).isEqualTo("my-event");
    }

    @Test
    void provisionedEntryWrapsWithoutWrittenFlag() {
        var entry = new Entry.Message("id-1", 0, null, null,
            new Message.UserMessage(List.of(new ContentBlock.TextContent("hi"))), null);
        var pw = new ProvisionedEntry<Entry.Message>(entry);

        assertThat(pw.entry()).isSameAs(entry);
    }

    @Test
    void jsonSerializationUsesPiKeyNames() throws Exception {
        var msg = new Entry.Message("id-1", 1L, "parent", Instant.ofEpochMilli(1720000001000L),
            new Message.UserMessage(List.of(new ContentBlock.TextContent("fix"))), null);
        var node = SessionJson.mapper().valueToTree(msg);

        assertThat(node.get("type").asText()).isEqualTo("message");
        assertThat(node.get("id").asText()).isEqualTo("id-1");
        assertThat(node.get("seq").asLong()).isEqualTo(1L);
        assertThat(node.get("parentId").asText()).isEqualTo("parent");
        assertThat(node.get("timestamp").asLong()).isEqualTo(1720000001000L);
        assertThat(node.get("message").get("role").asText()).isEqualTo("user");
        assertThat(node.get("message").get("content").get(0).get("type").asText()).isEqualTo("text");
        assertThat(node.has("terminate")).isFalse();
    }
}