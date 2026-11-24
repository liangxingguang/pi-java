package com.pijava.tui.screen.settings;

import com.pijava.tui.util.TamboUIAdapter;
import dev.tamboui.toolkit.element.Element;

/** Width-aware renderer for {@link SettingsListModel}（L7 W6 C3）。 */
public final class SettingsListView {

    private SettingsListView() {}

    /** Render without mouse activation (legacy seam). */
    public static Element render(SettingsListModel model) {
        return render(model, null);
    }

    /** Render search, aligned rows, description wrap and pi's keyboard hints. */
    public static Element render(SettingsListModel model, Runnable activator) {
        return TamboUIAdapter.panel("Settings",
            new SettingsListElement(model, activator)).fill();
    }
}
