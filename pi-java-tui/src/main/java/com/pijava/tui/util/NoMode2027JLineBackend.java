package com.pijava.tui.util;

import java.io.IOException;

import dev.tamboui.backend.jline3.JLineBackend;

import org.jline.terminal.Attributes;

/**
 * JLine backend that enters raw mode without the Mode 2027 (grapheme
 * cluster) handshake.
 *
 * <p>On Windows the stock Panama backend reads console input records and
 * drops every key whose {@code uChar} is 0 — that includes all arrow/function
 * keys — so navigation never reaches the app. JLine reads the console
 * correctly and delivers arrows as ANSI sequences, so pi-java switches to it
 * on Windows. The Mode 2027 DECRQM/DECRPM handshake (whose late ConPTY
 * response leaked {@code 2027;3$y} into the editor) is skipped here exactly
 * as in {@link NoMode2027Backend}.</p>
 */
public final class NoMode2027JLineBackend extends JLineBackend {

    private Attributes savedAttributes;

    public NoMode2027JLineBackend() throws IOException {
        super();
    }

    @Override
    public void enableRawMode() throws IOException {
        var terminal = jlineTerminal();
        savedAttributes = terminal.enterRawMode();
        var attrs = terminal.getAttributes();
        attrs.setLocalFlag(Attributes.LocalFlag.ISIG, false);
        terminal.setAttributes(attrs);
    }

    @Override
    public void disableRawMode() throws IOException {
        if (savedAttributes != null) {
            jlineTerminal().setAttributes(savedAttributes);
            savedAttributes = null;
        }
    }
}
