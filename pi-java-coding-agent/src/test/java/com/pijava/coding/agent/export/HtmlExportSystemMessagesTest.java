package com.pijava.coding.agent.export;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 W8（docs/25 §9；pi {@code test/export-html-system-messages.test.ts}，issue #11261）：
 * 系统消息不再只渲染成 {@code meta("system")}——分支上<b>首条</b> system 条目渲染
 * 完整提示（content＋sections），后续渲染 update 文案，工具增删附摘要。
 */
class HtmlExportSystemMessagesTest {

    @TempDir
    Path dir;

    private Path write(String... lines) throws Exception {
        var file = dir.resolve("session.jsonl");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return file;
    }

    private static String header() {
        return "{\"type\":\"session\",\"version\":4,\"id\":\"sys-test\","
            + "\"timestamp\":\"1970-01-01T00:00:00Z\",\"cwd\":\"/tmp/sys\"}";
    }

    @Test
    void leadingSystemMessageRendersFullPromptAndTools() throws Exception {
        var session = write(header(),
            "{\"type\":\"message\",\"id\":\"s1\",\"parentId\":null,\"seq\":1,"
                + "\"timestamp\":\"1970-01-01T00:00:01Z\",\"message\":{\"role\":\"system\","
                + "\"content\":\"You are a helpful assistant.\","
                + "\"sections\":{\"rules\":\"<rules>\\nbe concise\\n</rules>\","
                + "\"project_context\":\"<project_context>\\nv1\\n</project_context>\"},"
                + "\"toolsAdded\":[{\"name\":\"read\",\"description\":\"Read a file\"}]}}",
            "{\"type\":\"message\",\"id\":\"u1\",\"parentId\":\"s1\",\"seq\":2,"
                + "\"timestamp\":\"1970-01-01T00:00:02Z\",\"message\":{\"role\":\"user\","
                + "\"content\":\"hi\"}}");

        var html = new HtmlExporter().export(session, dir.resolve("out.html"));
        var text = Files.readString(html);

        // 首条 ⇒ 完整提示块（pi getSystemMessageText 序：content 先，sections 随后）。
        assertThat(text).contains("System Prompt");
        assertThat(text).contains("You are a helpful assistant.");
        assertThat(text).contains("&lt;rules&gt;");
        assertThat(text).contains("be concise");
        assertThat(text).contains("&lt;project_context&gt;");
        // 工具清单随首条渲染。
        assertThat(text).contains("Available Tools");
        assertThat(text).contains("read");
        // 系统条目带时间戳（pi system-entry > .message-timestamp）。
        assertThat(text).contains("system-entry");
        assertThat(text).contains("1970-01-01T00:00:01Z");
    }

    @Test
    void laterSystemMessageRendersUpdateNotPrompt() throws Exception {
        var session = write(header(),
            "{\"type\":\"message\",\"id\":\"s1\",\"parentId\":null,\"seq\":1,"
                + "\"timestamp\":\"1970-01-01T00:00:01Z\",\"message\":{\"role\":\"system\","
                + "\"content\":\"You are a helpful assistant.\"}}",
            "{\"type\":\"message\",\"id\":\"u1\",\"parentId\":\"s1\",\"seq\":2,"
                + "\"timestamp\":\"1970-01-01T00:00:02Z\",\"message\":{\"role\":\"user\","
                + "\"content\":\"hi\"}}",
            "{\"type\":\"message\",\"id\":\"s2\",\"parentId\":\"u1\",\"seq\":3,"
                + "\"timestamp\":\"1970-01-01T00:00:03Z\",\"message\":{\"role\":\"system\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"Extra instructions\"}],"
                + "\"sections\":{\"project_context\":\"<project_context>\\nv2\\n</project_context>\","
                + "\"rules\":null},"
                + "\"toolsAdded\":[{\"name\":\"bash\",\"description\":\"Run a command\"}],"
                + "\"toolsRemoved\":[{\"name\":\"read\"}]}}");

        var text = Files.readString(new HtmlExporter().export(session, dir.resolve("out.html")));

        // 后续 ⇒ update 文案（pi renderSystemMessageUpdate）。
        assertThat(text).contains("System Prompt Update");
        assertThat(text).contains("Extra instructions");
        assertThat(text).contains("Updated system prompt section &quot;project_context&quot;");
        assertThat(text).contains("Removed system prompt section &quot;rules&quot;");
        // 工具摘要：+bash -read（pi describeSystemUpdate 序）。
        assertThat(text).contains("instructions");
        assertThat(text).contains("project_context");
        assertThat(text).contains("-rules");
        assertThat(text).contains("tools +bash -read");
        assertThat(text).contains("Tools Added");
        assertThat(text).contains("Tools Removed");
    }

    @Test
    void compactionSystemMessageCountsAsEarlierState() throws Exception {
        // pi isLeadingSystemEntry：compaction 携带 systemMessage ⇒ 其后的 system 不是首条。
        var session = write(header(),
            "{\"type\":\"compaction\",\"id\":\"c1\",\"parentId\":null,\"seq\":1,"
                + "\"timestamp\":\"1970-01-01T00:00:01Z\",\"summary\":\"sum\","
                + "\"tokensBefore\":10,\"systemMessage\":{\"role\":\"system\","
                + "\"content\":\"Compacted away prompt\"}}",
            "{\"type\":\"message\",\"id\":\"s2\",\"parentId\":\"c1\",\"seq\":2,"
                + "\"timestamp\":\"1970-01-01T00:00:02Z\",\"message\":{\"role\":\"system\","
                + "\"content\":\"Second prompt\"}}");

        var text = Files.readString(new HtmlExporter().export(session, dir.resolve("out.html")));

        assertThat(text).contains("System Prompt Update");
        assertThat(text).doesNotContain("System Prompt</div>");
    }
}
