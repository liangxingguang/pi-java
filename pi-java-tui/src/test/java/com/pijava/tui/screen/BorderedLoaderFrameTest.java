package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.ElementRegistry;
import dev.tamboui.toolkit.event.EventRouter;
import dev.tamboui.toolkit.focus.FocusManager;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L7 W1（docs/22 §6，C7）：pi {@code BorderedLoader} 帧形——上下 {@code ─}
 * 框线（border 色）＋ spinner 行 ＋ cancellable ⇒ {@code (esc to cancel)} hint。
 */
class BorderedLoaderFrameTest {

    @Test
    void rendersBorderLoaderAndHintRows() throws Exception {
        var element = BorderedLoaderFrame.render("⠋", "Uploading to Radius...", true, 30);
        var rows = renderText(element, 40, 6);

        assertThat(rows).anyMatch(r -> r.startsWith("─") && r.length() == 30);
        assertThat(rows).anyMatch(r -> r.contains("⠋ Uploading to Radius..."));
        assertThat(rows).anyMatch(r -> r.contains("(esc to cancel)"));
        // 两条框线（上/下）。
        assertThat(rows.stream().filter(r -> r.startsWith("─")).count()).isEqualTo(2);
    }

    @Test
    void cancellableFalseOmitsTheHintRow() throws Exception {
        // pi BorderedLoader：options.cancellable=false ⇒ 无 hint 行。
        var element = BorderedLoaderFrame.render("", "Working...", false, 30);
        var rows = renderText(element, 40, 6);

        assertThat(rows).noneMatch(r -> r.contains("esc to cancel"));
        // 空 spinner 帧 ⇒ 无 spinner 符号，只有 message。
        assertThat(rows).anyMatch(r -> r.contains("Working..."));
    }

    @Test
    void widthIsClampedToOneWhenTerminalIsDegenerate() throws Exception {
        var element = BorderedLoaderFrame.render("⠋", "x", true, 0);
        var rows = renderText(element, 10, 6);
        assertThat(rows).anyMatch(r -> r.equals("─"));
    }

    /** 与 ChatScreenSessionEventTest 同法的整帧文本读面。 */
    private static List<String> renderText(Element element, int width, int height)
            throws Exception {
        var buffer = Buffer.empty(new Rect(0, 0, width, height));
        var frame = Frame.forTesting(buffer);
        var focus = new FocusManager();
        markRenderThread(true);
        try {
            element.render(frame, new Rect(0, 0, width, height),
                new DefaultRenderContext(focus, new EventRouter(focus, new ElementRegistry())));
        } finally {
            markRenderThread(false);
        }
        var rows = new ArrayList<String>();
        for (int y = 0; y < height; y++) {
            var row = new StringBuilder();
            for (int x = 0; x < width; x++) {
                var cell = buffer.get(x, y);
                if (!cell.isContinuation()) {
                    row.append(cell.symbol());
                }
            }
            rows.add(row.toString().stripTrailing());
        }
        return rows;
    }

    private static void markRenderThread(boolean on) throws Exception {
        var method = dev.tamboui.tui.RenderThread.class.getDeclaredMethod(
            on ? "markAsRenderThread" : "clearRenderThread");
        method.setAccessible(true);
        method.invoke(null);
    }
}
