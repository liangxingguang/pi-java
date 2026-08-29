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
            "summary", "kept-1", List.of(), 100,
            Map.of("readFiles", List.of("a.txt")), null);
        assertThat(entry.summary()).isEqualTo("summary");
        assertThat(entry.firstKeptEntryId()).isEqualTo("kept-1");
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
    void customMessageEntryTextFormSerializesAsBareString() {
        var entry = new Entry.CustomMessage("id-1", 7L, "parent", Instant.ofEpochMilli(1720000002000L),
            "my-extension", CustomMessageContent.of("Injected context..."), true, null);
        var node = SessionJson.mapper().valueToTree(entry);

        assertThat(entry.type()).isEqualTo("custom_message");
        assertThat(node.get("type").asText()).isEqualTo("custom_message");
        assertThat(node.get("customType").asText()).isEqualTo("my-extension");
        assertThat(node.get("content").isTextual()).isTrue();
        assertThat(node.get("content").asText()).isEqualTo("Injected context...");
        assertThat(node.get("display").asBoolean()).isTrue();
        assertThat(node.has("details")).isFalse();
    }

    @Test
    void customMessageEntryBlocksFormSerializesAsBareArray() {
        var blocks = List.<ContentBlock>of(
            new ContentBlock.TextContent("see "),
            new ContentBlock.ImageContent("image/png", "aGVsbG8="));
        var entry = new Entry.CustomMessage("id-2", 8L, "parent", Instant.now(),
            "ext", CustomMessageContent.of(blocks), false, Map.of("internal", 42));
        var node = SessionJson.mapper().valueToTree(entry);

        assertThat(node.get("content").isArray()).isTrue();
        assertThat(node.get("content").get(0).get("type").asText()).isEqualTo("text");
        assertThat(node.get("content").get(1).get("type").asText()).isEqualTo("image");
        assertThat(node.get("display").asBoolean()).isFalse();
        assertThat(node.get("details").get("internal").asInt()).isEqualTo(42);
    }

    @Test
    void customMessageEntryRoundTripsThroughCodec() {
        var blocks = List.<ContentBlock>of(new ContentBlock.TextContent("hello"),
            new ContentBlock.ImageContent("image/jpeg", "eA=="));
        var original = new Entry.CustomMessage("id-3", 9L, "parent", Instant.now(),
            "ext", CustomMessageContent.of(blocks), true, Map.of("k", "v"));
        var decoded = com.pijava.agent.session.jsonl.JsonlCodec.decodeEntry(
            SessionJson.mapper().valueToTree(original));

        assertThat(decoded).isInstanceOf(Entry.CustomMessage.class);
        var cm = (Entry.CustomMessage) decoded;
        assertThat(cm.id()).isEqualTo("id-3");
        assertThat(cm.customType()).isEqualTo("ext");
        assertThat(cm.display()).isTrue();
        assertThat(cm.details()).containsEntry("k", "v");
        assertThat(cm.content()).isInstanceOf(CustomMessageContent.Blocks.class);
        assertThat(((CustomMessageContent.Blocks) cm.content()).blocks()).isEqualTo(blocks);
    }

    @Test
    void customMessageTextRoundTripAndConversions() {
        var original = new Entry.CustomMessage("id-4", 10L, "parent", Instant.now(),
            "ext", CustomMessageContent.of("plain text"), false, null);
        var decoded = com.pijava.agent.session.jsonl.JsonlCodec.decodeEntry(
            SessionJson.mapper().valueToTree(original));

        var cm = (Entry.CustomMessage) decoded;
        assertThat(cm.content()).isEqualTo(new CustomMessageContent.Text("plain text"));
        assertThat(cm.content().toBlocks()).containsExactly(new ContentBlock.TextContent("plain text"));
        assertThat(cm.content().plainText()).isEqualTo("plain text");
        assertThat(cm.committed(11L, "p2", Instant.EPOCH).type()).isEqualTo("custom_message");
    }

    @Test
    void customMessageBlocksPlainTextJoinsTextIgnoresImages() {
        var content = CustomMessageContent.of(List.<ContentBlock>of(
            new ContentBlock.TextContent("a"),
            new ContentBlock.ImageContent("image/png", "x"),
            new ContentBlock.TextContent("b")));
        assertThat(content.plainText()).isEqualTo("ab");
        assertThat(content.toBlocks()).hasSize(3);
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
