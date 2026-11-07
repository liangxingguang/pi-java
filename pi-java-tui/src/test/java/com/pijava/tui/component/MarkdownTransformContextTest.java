package com.pijava.tui.component;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

import com.pijava.coding.agent.extension.MarkdownTransformContext;
import com.pijava.coding.agent.extension.MarkdownTransformContext.MarkdownMessageType;
import com.pijava.coding.agent.extension.MarkdownTransformer;

/**
 * L5 W8（docs/25 §9；pi {@code types.ts:1562-1568}）：变换上下文增加
 * {@code messageId}/{@code timestamp}——渲染<b>持久化条目</b>时传入条目标识与
 * ISO 时间，实时消息不传（{@code null}）。
 */
class MarkdownTransformContextTest {

    private static MarkdownTransformContext lastSeen;

    private static final MarkdownTransformer CAPTURE =
        (markdown, context) -> {
            lastSeen = context;
            return markdown;
        };

    @Test
    void liveRenderingHasNoEntryIdentity() {
        MarkdownTransformers.install(() -> List.of(CAPTURE));
        try {
            MarkdownTransformers.apply("x", MarkdownMessageType.ASSISTANT);

            assertThat(lastSeen.messageId()).isNull();
            assertThat(lastSeen.timestamp()).isNull();
        } finally {
            MarkdownTransformers.install(null);
        }
    }

    @Test
    void persistedEntryRenderingCarriesIdAndIsoTimestamp() {
        MarkdownTransformers.install(() -> List.of(CAPTURE));
        try {
            MarkdownTransformers.applyForEntry("x", MarkdownMessageType.ASSISTANT,
                "a1", "2026-11-05T10:00:00Z");

            assertThat(lastSeen.messageId()).isEqualTo("a1");
            assertThat(lastSeen.timestamp()).isEqualTo("2026-11-05T10:00:00Z");
        } finally {
            MarkdownTransformers.install(null);
        }
    }

    @Test
    void threeArgumentConstructorStaysForLiveRendering() {
        var context = new MarkdownTransformContext(
            MarkdownMessageType.ASSISTANT_THINKING, true, 80);

        assertThat(context.messageType()).isEqualTo(MarkdownMessageType.ASSISTANT_THINKING);
        assertThat(context.isStreaming()).isTrue();
        assertThat(context.availableWidth()).isEqualTo(80);
        assertThat(context.messageId()).isNull();
        assertThat(context.timestamp()).isNull();
    }
}
