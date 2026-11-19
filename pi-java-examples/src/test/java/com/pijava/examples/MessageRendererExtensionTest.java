package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import com.pijava.agent.entry.CustomMessageContent;

import org.junit.jupiter.api.Test;

/** docs/27 W5：message-renderer 验收——级别前缀、展开臂时间串、命令三臂载荷。 */
class MessageRendererExtensionTest {

    private static final long FIXED = 1_700_000_000_000L;

    @Test
    void levelDrivesPrefix() {
        var content = CustomMessageContent.of("hello");
        assertThat(MessageRendererExtension.renderMessage(content, Map.of("level", "error"), false))
            .containsExactly("[ERROR] hello");
        assertThat(MessageRendererExtension.renderMessage(content, Map.of("level", "warn"), false))
            .containsExactly("[WARN] hello");
        assertThat(MessageRendererExtension.renderMessage(content, Map.of(), false))
            .containsExactly("[INFO] hello");
        assertThat(MessageRendererExtension.renderMessage(content, null, false))
            .containsExactly("[INFO] hello");
    }

    @Test
    void expandedAddsTimestampLine() {
        var content = CustomMessageContent.of("boom");
        var details = details("error", FIXED);

        var lines = MessageRendererExtension.renderMessage(content, details, true);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).isEqualTo("[ERROR] boom");
        assertThat(lines.get(1)).matches("  at \\d{2}:\\d{2}:\\d{2}");

        // 折叠臂不显示时间
        assertThat(MessageRendererExtension.renderMessage(content, details, false)).hasSize(1);
        // timestamp 0 在 pi 的 truthy 判定里不显示
        assertThat(MessageRendererExtension.renderMessage(content,
            details("error", 0L), true)).hasSize(1);
    }

    @Test
    void multiLineContentStaysMultiLine() {
        var lines = MessageRendererExtension.renderMessage(
            CustomMessageContent.of("a\nb"), Map.of("level", "info"), false);
        assertThat(lines).containsExactly("[INFO] a", "b");
    }

    @Test
    void commandParsesLevelAndContent() {
        var w = new W3Context();
        var ext = new MessageRendererExtension();
        ext.clock(() -> FIXED);
        ext.register(w.ctx);

        w.command("status", "warn disk full");
        var sent = w.sentCustom.get(0);
        assertThat(sent.type()).isEqualTo("status-update");
        assertThat(sent.content()).isEqualTo("disk full");
        assertThat(sent.details()).containsEntry("level", "warn")
            .containsEntry("timestamp", FIXED);

        w.command("status", "error");
        assertThat(w.sentCustom.get(1).content())
            .isEqualTo(MessageRendererExtension.DEFAULT_CONTENT);
        assertThat(w.sentCustom.get(1).details()).containsEntry("level", "error");

        w.command("status", "plain note");
        assertThat(w.sentCustom.get(2).content()).isEqualTo("plain note");
        assertThat(w.sentCustom.get(2).details()).containsEntry("level", "info");
    }

    @Test
    void rendererRegisteredUnderPiCustomType() {
        var w = new W3Context();
        new MessageRendererExtension().register(w.ctx);

        assertThat(w.messageRenderers).containsKey("status-update");
        var renderer = w.messageRenderers.get("status-update");
        assertThat(renderer.render("status-update", CustomMessageContent.of("x"),
            Map.of("level", "warn"), false, 2)).containsExactly("[WARN] x");
    }

    private static Map<String, Object> details(String level, long timestamp) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("level", level);
        map.put("timestamp", timestamp);
        return map;
    }
}
