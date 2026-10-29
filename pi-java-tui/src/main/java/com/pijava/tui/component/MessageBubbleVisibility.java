package com.pijava.tui.component;

import java.util.function.BooleanSupplier;

/** Live-read visibility settings for assistant message projection. */
public final class MessageBubbleVisibility {

    private static volatile BooleanSupplier hideThinking = () -> false;

    private MessageBubbleVisibility() {}

    /** Install the current session's hideThinkingBlock supplier. */
    public static void install(BooleanSupplier supplier) {
        hideThinking = supplier == null ? () -> false : supplier;
    }

    /** Whether thinking blocks should be omitted at render time. */
    static boolean hiddenThinking() {
        return hideThinking.getAsBoolean();
    }
}
