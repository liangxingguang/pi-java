package com.pijava.agent.session.jsonl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.entry.ProvisionedEntry;
import com.pijava.ai.Usage;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 14 写侧：会话行必须以 pi 主流拼写落盘 —— {@code "toolResult"} 角色、
 * {@code "toolCallId"} 字段、{@code "toolCall"} 块（{@code docs/14 §4.2}，
 * 经 {@link SessionWireShape} 单点转换）。
 */
class PiNativeSpellingWriteTest {

    @Test
    void writesToolMessagesWithPiSpellings() throws Exception {
        Path dir = Files.createTempDirectory("pi-native-write");
        var repo = JsonlSessionRepository.over(dir);
        var session = repo.create(new JsonlSessionCreateOptions(null, "work", null, null));

        var assistant = new Message.AssistantMessage(
            List.of(new ContentBlock.ToolUseContent("call-1", "read",
                Map.of("path", "x"), null)),
            "toolUse", null, "openai-completions", "stub", "stub-1",
            new Usage(0, 0, 0, 0, null, null, 0, Usage.Cost.zero()),
            java.time.Instant.EPOCH, null, "tool_calls");
        session.appendEntry(new ProvisionedEntry<>(new Entry.Message(
            "a1", 0, null, null, assistant, null)), "main");

        var toolResult = new Message.ToolResultMessage("call-1", "read",
            List.of(new ContentBlock.TextContent("result")), null, null, List.of(),
            false, java.time.Instant.EPOCH);
        session.appendEntry(new ProvisionedEntry<>(new Entry.Message(
            "t1", 0, null, null, toolResult, null)), "main");
        session.storage().drain();

        Path file = repo.list(JsonlSessionListOptions.all()).getFirst().path();
        String text = Files.readString(file);

        assertThat(text).contains("\"role\":\"toolResult\"");
        assertThat(text).contains("\"toolCallId\":\"call-1\"");
        assertThat(text).contains("\"type\":\"toolCall\"");
        assertThat(text)
            .as("本仓方言不该出现在线上")
            .doesNotContain("\"role\":\"tool\"", "\"toolUseId\"", "\"type\":\"tool_use\"");
    }
}
