package com.pijava.ai.stream;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ToolCallBuilder} — delta aggregation for OpenAI/Mistral tool calls.
 */
class ToolCallBuilderTest {

    @Test
    void shouldStartWithCorrectIdAndName() {
        var builder = new ToolCallBuilder();
        builder.start("toolu_01", "read");

        assertThat(builder.isStarted()).isTrue();
        assertThat(builder.id()).isEqualTo("toolu_01");
        assertThat(builder.name()).isEqualTo("read");
    }

    @Test
    void shouldNotBeCompleteUntilArgumentsPresent() {
        var builder = new ToolCallBuilder();
        assertThat(builder.isComplete()).isFalse();

        builder.start("toolu_01", "read");
        assertThat(builder.isComplete()).isFalse();

        builder.append("{\"path\": \"/src\"}");
        assertThat(builder.isComplete()).isTrue();
    }

    @Test
    void shouldAccumulateJsonFragments() {
        var builder = new ToolCallBuilder();
        builder.start("id", "tool");
        builder.append("{\"path\":");
        builder.append("\"/src/main.java\"}");

        assertThat(builder.argumentsJson()).isEqualTo("{\"path\":\"/src/main.java\"}");
    }

    @Test
    void shouldParseValidJsonToEnd() {
        var builder = new ToolCallBuilder();
        builder.start("toolu_01", "read");
        builder.append("{\"path\":\"/src/main.java\",\"offset\":10}");

        var end = builder.toEnd();
        assertThat(end.id()).isEqualTo("toolu_01");
        assertThat(end.name()).isEqualTo("read");
        assertThat(end.arguments())
                .containsEntry("path", "/src/main.java")
                .containsEntry("offset", 10);
    }

    @Test
    void shouldHandleInvalidJsonGracefully() {
        var builder = new ToolCallBuilder();
        builder.start("toolu_01", "read");
        builder.append("not valid json");

        var end = builder.toEnd();
        assertThat(end.arguments()).containsKey("_raw");
        assertThat(end.arguments().get("_raw")).isEqualTo("not valid json");
    }

    @Test
    void freshBuilderIsNotStarted() {
        var builder = new ToolCallBuilder();
        assertThat(builder.isStarted()).isFalse();
        assertThat(builder.id()).isEmpty();
        assertThat(builder.name()).isEmpty();
    }
}
