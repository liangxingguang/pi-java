package com.pijava.tui.util;

import java.io.IOException;

import dev.tamboui.backend.panama.PanamaBackend;
import dev.tamboui.backend.panama.PlatformTerminal;

/**
 * Panama backend that enters raw mode without the Mode 2027 (grapheme
 * cluster) handshake.
 *
 * <p>Stock {@link PanamaBackend#enableRawMode()} sends a DECRQM query
 * ({@code CSI ? 2027 $ p}) and synchronously waits up to 500&nbsp;ms for the
 * DECRPM response. On Windows ConPTY the response is often split across
 * writes and arrives after the timeout; the leftover bytes
 * ({@code 2027;3$y}) then leak into the input stream and get inserted into
 * the editor as ordinary characters. Mode 2027 only improves complex
 * grapheme rendering, so skipping the handshake is safe for this app.</p>
 */
public final class NoMode2027Backend extends PanamaBackend {

    private final PlatformTerminal platform;

    public NoMode2027Backend() throws IOException {
        super();
        this.platform = platformTerminal();
    }

    @Override
    public void enableRawMode() throws IOException {
        // Same native raw-mode setup, but no Mode 2027 query/enable.
        platform.enableRawMode();
    }

    @Override
    public void disableRawMode() throws IOException {
        // Mode 2027 was never enabled, so no disable sequence is needed.
        platform.disableRawMode();
    }
}
