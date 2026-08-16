package com.pijava.tui.util;

import java.util.List;
import java.util.Optional;

import dev.tamboui.css.Styleable;
import dev.tamboui.css.cascade.CssStyleResolver;
import dev.tamboui.css.cascade.PseudoClassState;
import dev.tamboui.css.engine.StyleEngine;
import dev.tamboui.toolkit.element.RenderContext;

/**
 * Minimal render context for the raw-scrollback inline shell.
 *
 * <p>{@code StyledElement.render} switches to a plain {@code renderContent}
 * fallback whenever the context is not a {@code DefaultRenderContext}, which
 * avoids TamboUI's element registration — and therefore its render-thread
 * marker that only {@code TuiRunner} (package-private) can set. CSS theme
 * resolution is still wired through the same {@link StyleEngine}; only
 * focus-based pseudo classes and ancestor-chained selectors are skipped,
 * which the bottom region and overlays do not rely on.</p>
 */
public final class InlineRenderContext implements RenderContext {

    private StyleEngine styleEngine;

    /** Sets the CSS theme engine (null disables CSS styling). */
    public void setStyleEngine(StyleEngine engine) {
        this.styleEngine = engine;
    }

    @Override
    public boolean isFocused(String elementId) {
        return false;
    }

    @Override
    public boolean hasFocus() {
        return false;
    }

    @Override
    public Optional<CssStyleResolver> resolveStyle(Styleable element) {
        if (styleEngine == null) {
            return Optional.empty();
        }
        return Optional.of(styleEngine.resolve(element, PseudoClassState.NONE, List.of()));
    }
}
