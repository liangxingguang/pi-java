package com.pijava.tui.screen.settings;

import java.util.ArrayList;

import com.pijava.tui.util.TamboUIAdapter;
import dev.tamboui.toolkit.element.Element;

/** Text renderer for {@link SettingsListModel}. */
public final class SettingsListView {

    private SettingsListView() {}

    /** Render search, rows, selected description and pi's keyboard hints. */
    public static Element render(SettingsListModel model) {
        var rows = new ArrayList<Element>();
        rows.add(TamboUIAdapter.text("> " + model.query()));
        rows.add(TamboUIAdapter.text(""));
        if (model.filteredItems().isEmpty()) {
            rows.add(TamboUIAdapter.text("  No matching settings").dim());
        } else {
            var selected = model.selected();
            for (var item : model.visibleItems()) {
                var text = TamboUIAdapter.text((item == selected ? "→ " : "  ")
                    + item.label() + "  " + item.currentValue());
                rows.add(item == selected ? text.cyan() : text);
            }
            if (!model.scrollPosition().isEmpty()) {
                rows.add(TamboUIAdapter.text(model.scrollPosition()).dim());
            }
            if (selected != null && !selected.description().isEmpty()) {
                rows.add(TamboUIAdapter.text(""));
                rows.add(TamboUIAdapter.text("  " + selected.description()).dim());
            }
        }
        rows.add(TamboUIAdapter.text(""));
        rows.add(TamboUIAdapter.text("  Type to search · Enter/Space to change · Esc to cancel").dim());
        return TamboUIAdapter.panel("Settings", TamboUIAdapter.column(rows)).fill();
    }
}
