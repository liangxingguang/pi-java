package com.pijava.ai.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pijava.ai.message.ContentBlock;
import com.pijava.ai.message.Message;

class ContextNormalizerTest {

    @Test
    void leavesMessagesUntouchedWhenLegacyFieldsAreEmpty() {
        var user = user("hi");

        var normalized = ContextNormalizer.normalize(null, List.of(user), List.of());

        assertThat(normalized.messages()).containsExactly(user);
    }

    @Test
    void prependsLegacySystemPromptAndCopiesTools() {
        var user = user("hi");
        var tool = tool("bash");
        var tools = new ArrayList<ToolDefinition>();
        tools.add(tool);

        var normalized = ContextNormalizer.normalize("sys", List.of(user), tools);

        assertThat(normalized.messages()).hasSize(2);
        assertThat(normalized.messages().get(0)).isInstanceOf(Message.SystemMessage.class);
        var system = (Message.SystemMessage) normalized.messages().get(0);
        assertThat(system.content()).containsExactly(new ContentBlock.TextContent("sys"));
        assertThat(system.timestamp()).isEqualTo(Instant.EPOCH);
        assertThat(system.toolsAdded()).containsExactly(tool);
        assertThat(normalized.messages().get(1)).isEqualTo(user);

        tools.clear();
        assertThat(system.toolsAdded()).containsExactly(tool);
    }

    @Test
    void preservesExistingLeadingSystemMessageWithoutInjectingLegacyFields() {
        var existing = new Message.SystemMessage(
            "existing", Instant.EPOCH, Map.of(), List.of(), List.of());
        var user = user("hi");

        var normalized = ContextNormalizer.normalize(
            "legacy", List.of(existing, user), List.of(tool("bash")));

        assertThat(normalized.messages()).containsExactly(existing, user);
        assertThat(normalized.messages()).isNotSameAs(List.of(existing, user));
    }

    @Test
    void treatsNullMessagesAndToolsAsEmptyAndPreservesOrder() {
        var first = user("first");
        var second = user("second");
        var supplied = new ArrayList<Message>();
        supplied.add(first);
        supplied.add(second);

        var normalized = ContextNormalizer.normalize("", supplied, null);

        assertThat(normalized.messages()).containsExactly(first, second);
        assertThat(normalized.messages()).isNotSameAs(supplied);

        supplied.clear();
        assertThat(normalized.messages()).containsExactly(first, second);
        assertThat(ContextNormalizer.normalize(null, null, null).messages()).isEmpty();
    }

    private static Message.UserMessage user(String text) {
        return new Message.UserMessage(List.of(new ContentBlock.TextContent(text)));
    }

    private static ToolDefinition tool(String name) {
        return new ToolDefinition(name, name + " tool", Map.of("type", "object"));
    }
}
