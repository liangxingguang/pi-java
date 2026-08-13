package com.pijava.tui.theme;

import java.io.IOException;

import com.pijava.tui.util.TamboUIAdapter;

import dev.tamboui.css.engine.StyleEngine;
import dev.tamboui.toolkit.app.ToolkitRunner;

/**
 * Theme loading/switching (Phase 3 design §3.2).
 *
 * <p>Phase 3 ships two built-in TCSS themes (dark default, light optional);
 * loading custom theme files arrives Phase 6.</p>
 */
public final class PiTheme {

    public static final String DARK = "themes/pi-dark.tcss";
    public static final String LIGHT = "themes/pi-light.tcss";

    private PiTheme() {}

    /**
     * Build a style engine with both themes and apply the requested one.
     *
     * @param theme "dark" (default) or "light"; unknown values fall back to dark
     */
    public static StyleEngine engineFor(String theme) {
        var engine = StyleEngine.create();
        try {
            engine.loadStylesheet(DARK);
            engine.loadStylesheet(LIGHT);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load built-in themes", e);
        }
        engine.setActiveStylesheet("light".equalsIgnoreCase(theme) ? LIGHT : DARK);
        return engine;
    }

    /** Apply the theme to an existing runner (used when toggling themes). */
    public static void apply(ToolkitRunner runner, String theme) {
        runner.styleEngine(engineFor(theme));
    }
}
