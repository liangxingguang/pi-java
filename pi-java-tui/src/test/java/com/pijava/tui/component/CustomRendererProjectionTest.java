package com.pijava.tui.component;

import java.util.List;
import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;
import com.pijava.agent.entry.Entry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批 26（docs/37 T10，E5-8/E5-10）：custom 消息/条目渲染器的投影缝——
 * message renderer 命中渲染、空表回落默认、entry renderer 缺席不渲
 * （pi interactive-mode.ts:3846-3902 对位）。
 */
class CustomRendererProjectionTest {

    @AfterEach
    void tearDown() {
        CustomRenderers.install(null, null);
    }

    private static Entry.CustomMessage customMessage(boolean display) {
        return new Entry.CustomMessage("cm", 0, null, null, "my-ext",
            CustomMessageContent.of("injected"), display, null);
    }

    @Test
    void customMessageRendererWins() {
        CustomRenderers.install(
            type -> "my-ext".equals(type)
                ? (t, content, details, expanded, pad) -> List.of("rendered line") : null,
            null);

        var bubble = ChatMessage.from(customMessage(true));

        assertThat(bubble).isEqualTo(
            new ChatMessage.System("rendered line", MetaKind.CUSTOM));
    }

    @Test
    void customMessageEmptyRendererFallsBackToDefault() {
        CustomRenderers.install(
            type -> (t, content, details, expanded, pad) -> List.of(), null);

        var bubble = ChatMessage.from(customMessage(true));

        assertThat(bubble).isEqualTo(
            new ChatMessage.System("[my-ext] injected", MetaKind.CUSTOM));
    }

    @Test
    void customMessageDisplayFalseStaysHidden() {
        CustomRenderers.install(
            type -> (t, content, details, expanded, pad) -> List.of("rendered line"), null);

        assertThat(ChatMessage.from(customMessage(false))).isNull();
    }

    @Test
    void customEntryWithoutRendererIsNotRendered() {
        assertThat(ChatMessage.from(new Entry.Custom("x", 0, null, null, "progress", null)))
            .isNull();
    }

    @Test
    void customEntryRendererRenders() {
        CustomRenderers.install(null,
            type -> "progress".equals(type)
                ? (t, data, expanded) -> List.of("entry line") : null);

        var bubble = ChatMessage.from(new Entry.Custom("x", 0, null, null, "progress",
            Map.of("pct", 1)));

        assertThat(bubble).isEqualTo(
            new ChatMessage.System("entry line", MetaKind.CUSTOM));
    }

    @Test
    void customEntryEmptyRendererIsNotRendered() {
        CustomRenderers.install(null,
            type -> (t, data, expanded) -> List.of());

        assertThat(ChatMessage.from(new Entry.Custom("x", 0, null, null, "progress", null)))
            .isNull();
    }
}
