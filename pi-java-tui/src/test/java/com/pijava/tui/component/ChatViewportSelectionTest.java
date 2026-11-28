package com.pijava.tui.component;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.ElementRegistry;
import dev.tamboui.toolkit.event.EventRouter;
import dev.tamboui.toolkit.focus.FocusManager;
import dev.tamboui.tui.event.KeyModifiers;
import dev.tamboui.tui.event.MouseButton;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.MouseEventKind;

/**
 * S2：视口鼠标选择——双击选词、三击行选、边缘自动滚动与 X10 释放编码
 * （pi {@code tui-alt-screen.ts:1192-1336,1338-1416} 对位）。
 */
class ChatViewportSelectionTest {

    private static final ChatMessage USER = new ChatMessage.User("hello world");

    private static final class Harness {
        private final EventRouter router;

        Harness(EventRouter router) {
            this.router = router;
        }

        void route(MouseEventKind kind, MouseButton button, int x, int y) {
            router.route(new MouseEvent(kind, button, x, y,
                KeyModifiers.of(false, false, false)));
        }
    }

    private static Harness render(ChatViewportElement element, int width, int height)
            throws Exception {
        var buffer = Buffer.empty(new Rect(0, 0, width, height));
        var frame = Frame.forTesting(buffer);
        var focus = new FocusManager();
        var router = new EventRouter(focus, new ElementRegistry());
        var context = new DefaultRenderContext(focus, router);
        var mark = dev.tamboui.tui.RenderThread.class.getDeclaredMethod("markAsRenderThread");
        mark.setAccessible(true);
        mark.invoke(null);
        try {
            element.render(frame, new Rect(0, 0, width, height), context);
        } finally {
            var clear = dev.tamboui.tui.RenderThread.class.getDeclaredMethod("clearRenderThread");
            clear.setAccessible(true);
            clear.invoke(null);
        }
        return new Harness(router);
    }

    private static ChatViewportElement viewport(List<ChatMessage> messages) {
        return new ChatViewportElement().scrollbar(false).messages(messages, null);
    }

    @Test
    void doubleClickCopiesWholeWord() throws Exception {
        var element = viewport(List.of(USER));
        var harness = render(element, 30, 2);
        var copied = new ArrayList<String>();
        element.setSelectionSink(copied::add);

        long[] now = {1000};
        element.selectionClock(() -> now[0]);
        harness.route(MouseEventKind.PRESS, MouseButton.LEFT, 3, 0);
        harness.route(MouseEventKind.RELEASE, MouseButton.LEFT, 3, 0);
        now[0] = 1100;
        harness.route(MouseEventKind.PRESS, MouseButton.LEFT, 4, 0);
        harness.route(MouseEventKind.RELEASE, MouseButton.LEFT, 4, 0);

        assertThat(copied).containsExactly("hello");
    }

    @Test
    void tripleClickCopiesWholeLine() throws Exception {
        var element = viewport(List.of(USER));
        var harness = render(element, 30, 2);
        var copied = new ArrayList<String>();
        element.setSelectionSink(copied::add);

        long[] now = {1000};
        element.selectionClock(() -> now[0]);
        for (int x : new int[] {3, 4, 5}) {
            harness.route(MouseEventKind.PRESS, MouseButton.LEFT, x, 0);
            harness.route(MouseEventKind.RELEASE, MouseButton.LEFT, x, 0);
            now[0] += 100;
        }

        assertThat(copied).last().isEqualTo("› hello world");
    }

    @Test
    void edgeDragAutoScrollsUpAndStopsAtTop() throws Exception {
        var element = viewport(List.of(new ChatMessage.User("0\n1\n2\n3\n4\n5\n6\n7\n8\n9")));
        var harness = render(element, 20, 3);
        assertThat(element.scrollState().offset()).isEqualTo(7);

        harness.route(MouseEventKind.PRESS, MouseButton.LEFT, 5, 1);
        harness.route(MouseEventKind.PRESS, MouseButton.LEFT, 5, 0);

        assertThat(element.tickSelectionAutoScroll(1000)).isFalse();
        assertThat(element.tickSelectionAutoScroll(1030)).isFalse();
        assertThat(element.scrollState().offset()).isEqualTo(7);
        assertThat(element.tickSelectionAutoScroll(1050)).isTrue();
        assertThat(element.scrollState().offset()).isEqualTo(6);
        for (long now = 1100; now <= 1350; now += 50) {
            element.tickSelectionAutoScroll(now);
        }
        assertThat(element.scrollState().offset()).isZero();
        assertThat(element.tickSelectionAutoScroll(1400)).isFalse();
        assertThat(element.tickSelectionAutoScroll(1450)).isFalse();
    }

    @Test
    void buttonlessMoveEndsX10SelectionAndCopies() throws Exception {
        var element = viewport(List.of(USER));
        var harness = render(element, 30, 2);
        var copied = new ArrayList<String>();
        element.setSelectionSink(copied::add);

        long[] now = {1000};
        element.selectionClock(() -> now[0]);
        harness.route(MouseEventKind.PRESS, MouseButton.LEFT, 3, 0);
        harness.route(MouseEventKind.MOVE, MouseButton.NONE, 3, 0);
        now[0] = 1100;
        harness.route(MouseEventKind.PRESS, MouseButton.LEFT, 4, 0);
        harness.route(MouseEventKind.MOVE, MouseButton.NONE, 4, 0);

        assertThat(copied).containsExactly("hello");
    }
}
