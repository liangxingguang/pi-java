package com.pijava.coding.agent.export;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-12: HtmlExporter — JSONL v4 会话 → 自包含 HTML。
 */
class HtmlExporterTest {

    @TempDir
    Path tmp;

    @Test
    void exportsSessionToHtml() throws Exception {
        var jsonl = tmp.resolve("session.jsonl");
        Files.write(jsonl, List.of(
            "{\"kind\":\"header\",\"version\":4,\"id\":\"sess-1\",\"createdAtMs\":1700000000000,"
                + "\"cwd\":\"/tmp/proj\"}",
            "{\"type\":\"message\",\"id\":\"e1\",\"seq\":1,\"parentId\":null,"
                + "\"timestamp\":\"2026-08-20T10:00:00Z\",\"message\":{\"role\":\"user\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"**Hello** world\"}]}}",
            "{\"type\":\"message\",\"id\":\"e2\",\"seq\":2,\"parentId\":\"e1\","
                + "\"timestamp\":\"2026-08-20T10:00:01Z\",\"message\":{\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"Code:\\n\\n```java\\n"
                + "int x = 1 < 2;\\n```\"},{\"type\":\"tool_use\",\"id\":\"t1\","
                + "\"name\":\"bash\",\"arguments\":{\"command\":\"ls\"}}]}}",
            "{\"type\":\"message\",\"id\":\"e3\",\"seq\":3,\"parentId\":\"e2\","
                + "\"timestamp\":\"2026-08-20T10:00:02Z\",\"message\":{\"role\":\"tool\","
                + "\"toolUseId\":\"t1\",\"toolName\":\"bash\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"main.java\"}],\"isError\":false}}",
            "{\"type\":\"model_change\",\"id\":\"e4\",\"seq\":4,\"parentId\":\"e2\","
                + "\"timestamp\":\"2026-08-20T10:00:03Z\",\"provider\":\"anthropic\","
                + "\"modelId\":\"claude-sonnet-4-5\"}"));

        var output = tmp.resolve("out.html");
        var written = new HtmlExporter().export(jsonl, output);

        assertThat(written).isEqualTo(output);
        var html = Files.readString(output);
        assertThat(html).startsWith("<!DOCTYPE html>")
            .contains("sess-1")
            .contains("class=\"msg user\"")
            .contains("<strong>Hello</strong>")
            .contains("class=\"language-java\"")
            .contains("int x = 1 &lt; 2;")
            .contains("class=\"tool\"")
            .contains("bash")
            .contains("main.java")
            .contains("model → anthropic/claude-sonnet-4-5");
    }

    @Test
    void exportDerivesDefaultOutputName() throws Exception {
        var jsonl = tmp.resolve("my-session.jsonl");
        Files.writeString(jsonl, "{\"kind\":\"header\",\"version\":4,\"id\":\"s1\"}\n");

        var output = new HtmlExporter().export(jsonl);

        assertThat(output.getFileName().toString()).isEqualTo("pi-java-session-my-session.html");
        assertThat(Files.readString(output)).contains("<html");
    }

    @Test
    void emptyFileThrows() throws Exception {
        var jsonl = tmp.resolve("empty.jsonl");
        Files.writeString(jsonl, "");

        var exporter = new HtmlExporter();
        var thrown = org.assertj.core.api.Assertions.catchThrowable(
            () -> exporter.export(jsonl, tmp.resolve("out.html")));
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Empty session file");
    }
}
