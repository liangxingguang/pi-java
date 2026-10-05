package com.pijava.agent.session.jsonl;

import java.nio.file.Files;
import java.nio.file.Path;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.session.LogItem;
import com.pijava.agent.session.LogOptions;
import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包 14 读侧：pi 主流拼写必须可读 —— 裸串 content、{@code "toolCall"} 块、
 * {@code "toolResult"} 角色＋{@code "toolCallId"} 字段；本仓旧拼写
 * （块数组、{@code "tool_use"}、{@code "tool"}/{@code "toolUseId"}）继续可读。
 */
class PiNativeSpellingReadTest {

    private static final DefaultJsonlFileSystem FS = new DefaultJsonlFileSystem();

    private static final String HEADER =
        "{\"type\":\"session\",\"version\":3,\"id\":\"s-1\",\"timestamp\":\"2026-10-05T01:00:00.000Z\","
            + "\"cwd\":\"D:\\\\work\"}";

    private static Entry loadLine(String line) throws Exception {
        Path dir = Files.createTempDirectory("pi-native-read");
        Path file = dir.resolve("2026-10-05T01-00-00-000Z_s-1.jsonl");
        Files.writeString(file, HEADER + "\n" + line + "\n");
        return ((LogItem.EntryItem) JsonlSessionStorage.load(FS, file)
            .getLog(LogOptions.none()).getFirst()).entry();
    }

    @Test
    void readsABareStringSystemAndUserContent() throws Exception {
        var system = (Entry.Message) loadLine(
            "{\"type\":\"message\",\"id\":\"m\",\"parentId\":null,\"timestamp\":\"2026-10-05T01:00:00.000Z\","
                + "\"message\":{\"role\":\"system\",\"content\":\"system instructions\",\"timestamp\":1}}");
        assertThat(((Message.SystemMessage) system.message()).content())
            .as("裸串 content 包成单个 text 块")
            .containsExactly(new ContentBlock.TextContent("system instructions"));

        var user = (Entry.Message) loadLine(
            "{\"type\":\"message\",\"id\":\"m\",\"parentId\":null,\"timestamp\":\"2026-10-05T01:00:00.000Z\","
                + "\"message\":{\"role\":\"user\",\"content\":\"bare user\",\"timestamp\":2}}");
        assertThat(((Message.UserMessage) user.message()).content())
            .containsExactly(new ContentBlock.TextContent("bare user"));
    }

    @Test
    void readsAToolCallBlock() throws Exception {
        var assistant = (Entry.Message) loadLine(
            "{\"type\":\"message\",\"id\":\"m\",\"parentId\":null,\"timestamp\":\"2026-10-05T01:00:00.000Z\",\"message\":{"
                + "\"role\":\"assistant\",\"timestamp\":3,"
                + "\"content\":[{\"type\":\"toolCall\",\"id\":\"call-1\",\"name\":\"read\","
                + "\"arguments\":{\"path\":\"x\"}}]}}");
        assertThat(((Message.AssistantMessage) assistant.message()).content())
            .as("pi 的 \"toolCall\" 块读成本仓 ToolUseContent")
            .containsExactly(new ContentBlock.ToolUseContent("call-1", "read",
                java.util.Map.of("path", "x"), null));
    }

    @Test
    void readsAToolResultRoleAndToolCallIdField() throws Exception {
        var tool = (Entry.Message) loadLine(
            "{\"type\":\"message\",\"id\":\"m\",\"parentId\":null,\"timestamp\":\"2026-10-05T01:00:00.000Z\",\"message\":{"
                + "\"role\":\"toolResult\",\"toolCallId\":\"call-1\",\"toolName\":\"read\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"result\"}],\"isError\":false,"
                + "\"timestamp\":4}}");
        var message = (Message.ToolResultMessage) tool.message();
        assertThat(message.toolUseId())
            .as("pi 的 \"toolCallId\" 读入本仓 toolUseId 分量").isEqualTo("call-1");
        assertThat(message.toolName()).isEqualTo("read");
        assertThat(message.content())
            .containsExactly(new ContentBlock.TextContent("result"));
    }
}
