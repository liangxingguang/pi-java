package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** docs/27 W5：entry-renderer 验收——卡片文案、缺省臂、展开臂时间串、命令载荷。 */
class EntryRendererExtensionTest {

    private static final long FIXED = 1_700_000_000_000L;

    @Test
    void collapsedCardLine() {
        assertThat(EntryRendererExtension.renderCard(card("hi", FIXED), false))
            .containsExactly(" [status] hi");
    }

    @Test
    void missingDataFallsBackToNoData() {
        assertThat(EntryRendererExtension.renderCard(null, false))
            .containsExactly(" [status] " + EntryRendererExtension.NO_DATA);
        // pi 的 {@code data.message} ⇒ undefined 在模板里逐字打印
        assertThat(EntryRendererExtension.renderCard(Map.of(), false))
            .containsExactly(" [status] undefined");
    }

    @Test
    void expandedAddsLocaleTimestamp() {
        var lines = EntryRendererExtension.renderCard(card("hi", FIXED), true);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).matches(" \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat(EntryRendererExtension.renderCard(Map.of("message", "x"), true))
            .hasSize(1);
    }

    @Test
    void commandAppendsDurableEntry() {
        var w = new W3Context();
        var ext = new EntryRendererExtension();
        ext.clock(() -> FIXED);
        ext.register(w.ctx);

        w.command("status-card", "   ");
        assertThat(w.appended).containsExactly("status-card");
        assertThat(w.appendedData.get("status-card"))
            .containsEntry("message", EntryRendererExtension.DEFAULT_MESSAGE)
            .containsEntry("timestamp", FIXED);

        w.command("status-card", " build green ");
        assertThat(w.appendedData.get("status-card")).containsEntry("message", "build green");
    }

    @Test
    void rendererRegisteredUnderPiCustomType() {
        var w = new W3Context();
        new EntryRendererExtension().register(w.ctx);

        assertThat(w.entryRenderers).containsKey("status-card");
        assertThat(w.entryRenderers.get("status-card")
            .render("status-card", card("ok", FIXED), false)).containsExactly(" [status] ok");
    }

    private static Map<String, Object> card(String message, long timestamp) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("message", message);
        map.put("timestamp", timestamp);
        return map;
    }
}
