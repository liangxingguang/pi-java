package com.pijava.tui.theme;

import java.io.IOException;
import java.nio.file.Path;


import dev.tamboui.css.engine.StyleEngine;
import dev.tamboui.toolkit.app.ToolkitRunner;

/**
 * Theme loading/switching (Phase 3 design §3.2).
 *
 * <p>Two built-in TCSS themes ship with the app (dark default, light
 * optional). Since Phase 6 (P6-21) {@code engineFor} also accepts a custom
 * theme file: pass a {@code .tcss} file path (e.g. {@code --theme
 * ~/.pi-java/themes/nord.tcss}) and it is loaded as the active stylesheet.
 * Bare names other than "dark"/"light" still fall back to dark, so an unknown
 * name is never a silent custom-theme misconfiguration.</p>
 */
public final class PiTheme {

    public static final String DARK = "themes/pi-dark.tcss";
    public static final String LIGHT = "themes/pi-light.tcss";

    /** Stylesheet name a custom theme file is registered under. */
    private static final String CUSTOM = "custom";

    private PiTheme() {}

    /**
     * Build a style engine and apply the requested theme.
     *
     * <p>Resolution order: {@code "dark"} / {@code "light"} (case-insensitive)
     * activate the built-in theme; a value that is an existing file or ends
     * with {@code .tcss} is loaded as a custom theme file; anything else falls
     * back to dark.</p>
     *
     * @param theme "dark" (default), "light", or a custom theme file path
     * @throws IllegalArgumentException when a custom theme file cannot be found or read
     */
    public static StyleEngine engineFor(String theme) {
        var engine = StyleEngine.create();
        try {
            engine.loadStylesheet("dark", DARK);
            engine.loadStylesheet("light", LIGHT);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load built-in themes", e);
        }
        String active = resolveThemeName(engine, theme);
        engine.setActiveStylesheet(active);
        return engine;
    }

    /** Apply the theme to an existing runner (used when toggling themes). */
    public static void apply(ToolkitRunner runner, String theme) {
        runner.styleEngine(engineFor(theme));
    }

    private static String resolveThemeName(StyleEngine engine, String theme) {
        if ("light".equalsIgnoreCase(theme)) {
            return "light";
        }
        if ("dark".equalsIgnoreCase(theme)) {
            return "dark";
        }
        var path = Path.of(theme);
        boolean looksLikeFile = theme.endsWith(".tcss") || path.toFile().isFile();
        if (looksLikeFile) {
            if (!path.toFile().isFile()) {
                throw new IllegalArgumentException(
                    "Custom theme file not found: " + theme);
            }
            try {
                engine.loadStylesheet(CUSTOM, path);
                return CUSTOM;
            } catch (IOException e) {
                throw new IllegalArgumentException(
                    "Cannot read custom theme file: " + theme, e);
            }
        }
        return "dark"; // unknown bare name → dark (preserves Phase 3 behavior)
    }
}
