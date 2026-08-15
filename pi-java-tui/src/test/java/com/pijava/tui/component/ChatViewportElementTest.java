package com.pijava.tui.component;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.message.ContentBlock;

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

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 alignment design §7.1: row-level viewport rendering assertions —
 * mid-message slicing (root cause A), reflow-on-resize (root cause B),
 * sticky follow semantics, clamping, and the scrollbar.
 */
class ChatViewportElementTest {

    private static final ChatMessage USER_HELLO = new ChatMessage.User("hello");

    private static Buffer render(ChatViewportElement element, int width, int height)
            throws Exception {
        return mouseHarness(element, width, height).buffer();
    }

    /** Renders once and keeps the router so tests can drive mouse events. */
    private static MouseHarness mouseHarness(ChatViewportElement element,
                                             int width, int height) throws Exception {
        var buffer = Buffer.empty(new Rect(0, 0, width, height));
        var frame = Frame.forTesting(buffer);
        var focus = new FocusManager();
        var router = new EventRouter(focus, new ElementRegistry());
        var context = new DefaultRenderContext(focus, router);
        var mark = dev.tamboui.tui.RenderThread.class
            .getDeclaredMethod("markAsRenderThread");
        mark.setAccessible(true);
        mark.invoke(null);
        try {
            element.render(frame, new Rect(0, 0, width, height), context);
        } finally {
            var clear = dev.tamboui.tui.RenderThread.class
                .getDeclaredMethod("clearRenderThread");
            clear.setAccessible(true);
            clear.invoke(null);
        }
        return new MouseHarness(buffer, element, router, focus, width, height);
    }

    private static MouseEvent mouse(MouseEventKind kind, MouseButton button, int x, int y) {
        return new MouseEvent(kind, button, x, y, KeyModifiers.of(false, false, false));
    }

    private record MouseHarness(
        Buffer buffer,
        ChatViewportElement element,
        EventRouter router,
        FocusManager focus,
        int width,
        int height
    ) {
        void route(MouseEvent event) {
            router.route(event);
        }
    }

    private static String row(Buffer buffer, int y, int width) {
        var out = new StringBuilder();
        for (int x = 0; x < width; x++) {
            var cell = buffer.get(x, y);
            if (cell.isContinuation()) {
                continue;
            }
            out.append(cell.symbol());
        }
        return out.toString().stripTrailing();
    }

    private static List<String> rows(Buffer buffer, int height, int width) {
        var out = new ArrayList<String>();
        for (int y = 0; y < height; y++) {
            out.add(row(buffer, y, width));
        }
        return out;
    }

    private static ChatViewportElement viewport(List<ChatMessage> messages,
                                                ChatMessage draft) {
        return new ChatViewportElement().scrollbar(false).messages(messages, draft);
    }

    @Test
    void rendersMessageRowsInOrder() throws Exception {
        var element = viewport(List.of(
            USER_HELLO,
            new ChatMessage.Assistant(List.of(
                new ContentBlock.TextContent("world")))), null);
        var buffer = render(element, 20, 3);
        assertThat(row(buffer, 0, 20)).isEqualTo("› hello");
        assertThat(row(buffer, 1, 20)).isEmpty(); // blank row between blocks
        assertThat(row(buffer, 2, 20)).isEqualTo("• world");
    }

    @Test
    void sliceShowsContinuationRowsWhenOffsetLandsMidMessage() throws Exception {
        var element = viewport(List.of(
            new ChatMessage.User("aaaa\nbbbb\ncccc\ndddd\neeee\nffff")), null);
        var buffer = render(element, 20, 1);
        assertThat(row(buffer, 0, 20)).isEqualTo("  ffff"); // sticky bottom

        var state = element.scrollState();
        state.scrollByRows(-3, 6, 1); // up 3 → offset 2 (away)
        buffer = render(element, 20, 1);
        assertThat(row(buffer, 0, 20)).isEqualTo("  cccc");

        state.scrollByRows(1, 6, 1);
        buffer = render(element, 20, 1);
        assertThat(row(buffer, 0, 20)).isEqualTo("  dddd");

        state.scrollByRows(1, 6, 1);
        buffer = render(element, 20, 1);
        assertThat(row(buffer, 0, 20)).isEqualTo("  eeee");
    }

    @Test
    void reflowOnResizeKeepsContentByteIdentical() throws Exception {
        var text = "aaaa bbbb cccc dddd";
        var element = viewport(List.of(new ChatMessage.User(text)), null);
        element.scrollState().scrollToTop();

        var wide = render(element, 9, 2);
        assertThat(rows(wide, 2, 9)).containsExactly("› aaaa", "  bbbb");

        element.scrollState().scrollToTop();
        var narrow = render(element, 4, 2);
        assertThat(rows(narrow, 2, 4)).containsExactly("›", "  aa");

        element.scrollState().scrollToBottom();
        narrow = render(element, 4, 2);
        assertThat(rows(narrow, 2, 4)).containsExactly("  dd", "dd");
    }

    @Test
    void stickyFollowPinsBottomOnNewContent() throws Exception {
        var element = viewport(List.of(
            new ChatMessage.User("one"),
            new ChatMessage.User("two")), null);
        render(element, 20, 2);
        assertThat(element.scrollState().offset()).isEqualTo(1);

        element.messages(List.of(
            new ChatMessage.User("one"),
            new ChatMessage.User("two"),
            new ChatMessage.User("three\nfour")), null);
        var buffer = render(element, 20, 2);
        assertThat(element.scrollState().offset()).isEqualTo(4);
        assertThat(rows(buffer, 2, 20)).containsExactly("› three", "  four");
    }

    @Test
    void userScrollAwayPausesFollow() throws Exception {
        var element = viewport(List.of(
            new ChatMessage.User("one"),
            new ChatMessage.User("two"),
            new ChatMessage.User("three\nfour")), null);
        render(element, 20, 2);
        element.scrollState().scrollByRows(-1, 6, 2);
        assertThat(element.scrollState().userScrolledAway()).isTrue();

        element.messages(List.of(
            new ChatMessage.User("one"),
            new ChatMessage.User("two"),
            new ChatMessage.User("three\nfour"),
            new ChatMessage.User("five")), null);
        var buffer = render(element, 20, 2);
        // Offset stays put; new content does not yank the view down.
        assertThat(element.scrollState().offset()).isEqualTo(3);
        assertThat(rows(buffer, 2, 20)).containsExactly("", "› three");
    }

    @Test
    void scrollToBottomResumesFollow() throws Exception {
        var element = viewport(List.of(
            new ChatMessage.User("one"),
            new ChatMessage.User("two"),
            new ChatMessage.User("three\nfour"),
            new ChatMessage.User("five")), null);
        render(element, 20, 2);
        element.scrollState().scrollToBottom();
        var buffer = render(element, 20, 2);
        assertThat(element.scrollState().offset()).isEqualTo(6);
        assertThat(rows(buffer, 2, 20)).containsExactly("", "› five");
        assertThat(element.scrollState().userScrolledAway()).isFalse();

        element.messages(List.of(
            new ChatMessage.User("one"),
            new ChatMessage.User("two"),
            new ChatMessage.User("three\nfour"),
            new ChatMessage.User("five"),
            new ChatMessage.User("six")), null);
        buffer = render(element, 20, 2);
        assertThat(element.scrollState().offset()).isEqualTo(8);
        assertThat(rows(buffer, 2, 20)).containsExactly("", "› six");
    }

    @Test
    void clampBoundsOffsetAfterContentShrinks() throws Exception {
        var element = viewport(List.of(
            new ChatMessage.User("0\n1\n2\n3\n4\n5")), null);
        render(element, 20, 2);
        element.scrollState().scrollByRows(-2, 6, 2);
        assertThat(element.scrollState().offset()).isEqualTo(2);

        // Content shrinks to 2 rows: the offset must clamp to 0, never wrap.
        element.messages(List.of(new ChatMessage.User("a\nb")), null);
        var buffer = render(element, 20, 2);
        assertThat(element.scrollState().offset()).isZero();
        assertThat(rows(buffer, 2, 20)).containsExactly("› a", "  b");
    }

    @Test
    void scrollbarRendersThumbColumn() throws Exception {
        var element = new ChatViewportElement().scrollbar(true).messages(
            List.of(new ChatMessage.User("0\n1\n2\n3\n4\n5")), null);
        render(element, 20, 3);
        element.scrollState().scrollByRows(-1, 6, 3);
        var buffer = render(element, 20, 3);
        var thumb = false;
        for (int y = 0; y < 3; y++) {
            if ("█".equals(buffer.get(19, y).symbol())) {
                thumb = true;
            }
        }
        assertThat(thumb).isTrue();
        assertThat(row(buffer, 0, 19)).isEqualTo("  2");
    }

    @Test
    void draftRendersAsLastMessageInsideViewport() throws Exception {
        var draft = new ChatMessage.Assistant(List.of(
            new ContentBlock.TextContent("draft")));
        var element = viewport(List.of(USER_HELLO), draft);
        var buffer = render(element, 20, 2);
        assertThat(rows(buffer, 2, 20)).containsExactly("› hello", "• draft");

        element.messages(List.of(USER_HELLO), null);
        buffer = render(element, 20, 2);
        assertThat(row(buffer, 1, 20)).isEmpty();
    }

    @Test
    void hoverThickensScrollbarAndMoveAwayShrinksIt() throws Exception {
        var element = new ChatViewportElement().scrollbar(true).messages(
            List.of(new ChatMessage.User("0\n1\n2\n3\n4\n5\n6\n7\n8\n9")), null);
        var harness = mouseHarness(element, 20, 3);
        assertThat(element.scrollbarHovered()).isFalse();

        harness.route(mouse(MouseEventKind.MOVE, MouseButton.NONE, 19, 1));
        assertThat(element.scrollbarHovered()).isTrue();

        harness.route(mouse(MouseEventKind.MOVE, MouseButton.NONE, 5, 1));
        assertThat(element.scrollbarHovered()).isFalse();
    }

    @Test
    void dragOnScrollbarMapsMouseYToOffsetAndEndsOnButtonlessMove() throws Exception {
        var element = new ChatViewportElement().scrollbar(true).messages(
            List.of(new ChatMessage.User("0\n1\n2\n3\n4\n5\n6\n7\n8\n9")), null);
        var harness = mouseHarness(element, 20, 3);
        assertThat(element.scrollState().offset()).isEqualTo(7); // sticky bottom

        // Hover first (2-column scrollbar), then press at the top of it.
        harness.route(mouse(MouseEventKind.MOVE, MouseButton.NONE, 19, 1));
        harness.route(mouse(MouseEventKind.PRESS, MouseButton.LEFT, 19, 0));
        assertThat(element.scrollbarDragging()).isTrue();
        assertThat(element.scrollState().offset()).isZero();

        // X10 repeats PRESS at every dragged position → jump to row 2/3.
        harness.route(mouse(MouseEventKind.PRESS, MouseButton.LEFT, 19, 2));
        assertThat(element.scrollState().offset()).isEqualTo(5);

        // Buttonless move (X10 release encoding) ends the drag.
        harness.route(mouse(MouseEventKind.MOVE, MouseButton.NONE, 19, 2));
        assertThat(element.scrollbarDragging()).isFalse();
    }

    @Test
    void pressOutsideScrollbarDoesNotStartDrag() throws Exception {
        var element = new ChatViewportElement().scrollbar(true).messages(
            List.of(new ChatMessage.User("0\n1\n2\n3\n4\n5\n6\n7\n8\n9")), null);
        var harness = mouseHarness(element, 20, 3);
        harness.route(mouse(MouseEventKind.PRESS, MouseButton.LEFT, 5, 1));
        assertThat(element.scrollbarDragging()).isFalse();
    }
}