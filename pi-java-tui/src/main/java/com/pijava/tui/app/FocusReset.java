package com.pijava.tui.app;

import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.toolkit.focus.FocusManager;
import dev.tamboui.tui.PostRenderProcessor;
import dev.tamboui.terminal.Frame;

/**
 * Clears the TamboUI focus after every rendered frame.
 *
 * <p>TamboUI auto-generates element ids on first render and auto-focuses the
 * first focusable element — the input TextArea would then swallow every key
 * (including Enter as a newline) before the app's global handler runs. This
 * processor keeps the focus system out of the way so the app owns all keys
 * (Phase 3 input model).</p>
 */
final class FocusReset implements PostRenderProcessor {

    private FocusManager manager;

    /** Bind the focus manager once the runner exists. */
    void bind(FocusManager manager) {
        this.manager = manager;
    }

    @Override
    public void process(Frame frame) {
        if (manager != null) {
            manager.clearFocus();
        }
    }
}
