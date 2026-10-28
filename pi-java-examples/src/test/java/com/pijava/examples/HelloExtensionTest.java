package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.core.PromptConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * docs/40 P1：hello——注册 {@code hello} 工具（name/label/description/参数 schema）；
 * 执行返回 content 文本 {@code Hello, <name>!}＋details {@code {greeted}}。
 */
class HelloExtensionTest {

    @TempDir Path tmp;

    @Test
    void toolMetadataAndSchemaMatchTypeBoxOutput() {
        var tool = new HelloExtension.HelloTool();
        assertThat(tool.name()).isEqualTo("hello");
        assertThat(tool.label()).isEqualTo("Hello");
        assertThat(tool.description()).isEqualTo("A simple greeting tool");
        // F3：TypeBox 1.3.27 产出键序 type/required/properties（无 additionalProperties/$id）
        var schema = tool.inputSchema();
        assertThat(new ArrayList<>(schema.keySet()))
            .containsExactly("type", "required", "properties");
        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema.get("required")).isEqualTo(List.of("name"));
        @SuppressWarnings("unchecked")
        var nameSchema = (Map<String, Object>)
            ((Map<String, Object>) schema.get("properties")).get("name");
        assertThat(new ArrayList<>(nameSchema.keySet())).containsExactly("type", "description");
        assertThat(nameSchema.get("description")).isEqualTo("Name to greet");
    }

    @Test
    void executeReturnsGreetingAndDetails() throws Exception {
        var tool = new HelloExtension.HelloTool();
        var result = tool.execute("t1", Map.of("name", "Pi"), null, null, null);
        assertThat(ExampleFixtures.textOf(result.content())).isEqualTo("Hello, Pi!");
        assertThat(result.details()).isEqualTo(Map.of("greeted", "Pi"));
        assertThat(result.terminate()).isFalse();
    }

    @Test
    void endToEndToolCallThroughSession() throws Exception {
        try (var scope = ExampleFixtures.scopeExtensions(tmp, HelloExtension.class)) {
            var faux = ExampleFixtures.toolThenDone("faux-hello", "c1", "hello",
                Map.of("name", "Pi"));
            try (var session = ExampleFixtures.newSession(tmp, faux)) {
                assertThat(session.getActiveToolNames()).contains("hello");

                var result = session.processPrompt("greet", PromptConfig.defaults());
                assertThat(result.status().exitCode()).isZero();

                var toolResult = session.accumulatedEntries().stream()
                    .filter(e -> e instanceof Entry.Message m
                        && m.message() instanceof Message.ToolResultMessage)
                    .map(e -> (Message.ToolResultMessage) ((Entry.Message) e).message())
                    .findFirst().orElseThrow();
                assertThat(toolResult.toolName()).isEqualTo("hello");
                assertThat(ExampleFixtures.textOf(toolResult.content()))
                    .isEqualTo("Hello, Pi!");
            }
        }
    }
}
