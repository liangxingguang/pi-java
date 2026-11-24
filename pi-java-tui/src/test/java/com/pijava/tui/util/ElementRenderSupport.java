package com.pijava.tui.util;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.toolkit.element.DefaultRenderContext;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.ElementRegistry;
import dev.tamboui.toolkit.event.EventRouter;
import dev.tamboui.toolkit.focus.FocusManager;
import dev.tamboui.tui.RenderThread;

/** L7 W6：元素渲染测试脚手架（标记渲染线程后渲染到固定缓冲）。 */
public final class ElementRenderSupport {

    private ElementRenderSupport() {}

    /** Renders the element and returns the buffer contents. */
    public static Buffer render(Element element, int width, int height) throws Exception {
        var buffer = Buffer.empty(new Rect(0, 0, width, height));
        var frame = Frame.forTesting(buffer);
        var context = new DefaultRenderContext(
            new FocusManager(), new EventRouter(new FocusManager(), new ElementRegistry()));
        var mark = RenderThread.class.getDeclaredMethod("markAsRenderThread");
        mark.setAccessible(true);
        mark.invoke(null);
        try {
            element.render(frame, new Rect(0, 0, width, height), context);
        } finally {
            var clear = RenderThread.class.getDeclaredMethod("clearRenderThread");
            clear.setAccessible(true);
            clear.invoke(null);
        }
        return buffer;
    }
}
