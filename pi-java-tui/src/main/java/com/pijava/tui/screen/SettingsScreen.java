package com.pijava.tui.screen;

import java.util.function.Consumer;

import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.theme.ThemeCatalog;
import com.pijava.coding.agent.theme.ThemeController;
import com.pijava.tui.screen.settings.SettingsActionHandler;
import com.pijava.tui.screen.settings.SettingsItem;
import com.pijava.tui.screen.settings.SettingsItems;
import com.pijava.tui.screen.settings.SettingsListModel;
import com.pijava.tui.screen.settings.SettingsListView;
import com.pijava.tui.screen.settings.WarningSettingsScreen;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/** Complete searchable settings selector with nested submenus. */
public final class SettingsScreen implements ScreenOverlay {

    private final AgentSession session;
    private final ThemeController themeController;
    private final SettingsActionHandler actions;
    private final SettingsListModel model;
    private ScreenOverlay submenu;
    private boolean done;
    private boolean modelThinkingRequested;
    private boolean cacheMissNoticesToggled;
    private boolean themeRequested;

    /** Create the selector without a live theme controller. */
    public SettingsScreen(AgentSession session) {
        this(session, null, new SettingsActionHandler.Host() { });
    }

    /** Create the selector with theme preview support. */
    public SettingsScreen(AgentSession session, ThemeController themeController) {
        this(session, themeController, new SettingsActionHandler.Host() { });
    }

    /** Create the selector with real settings writes and host effects. */
    public SettingsScreen(AgentSession session, ThemeController themeController,
                          SettingsActionHandler.Host host) {
        this.session = session;
        this.themeController = themeController;
        this.actions = new SettingsActionHandler(session, host);
        var currentTheme = themeController != null
            ? themeController.getThemeSelection()
            : session.services().settings().accessors().getThemeSetting();
        this.model = new SettingsListModel(SettingsItems.build(
            session.services().settings(), session.modelCatalog(), session.harness().getModel(),
            currentTheme == null ? "system" : currentTheme, ThemeCatalog.getAvailableThemes(),
            false, "Alt+Enter", "Shift+Tab"), 10);
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (submenu != null) {
            var consumed = submenu.onKeyEvent(event);
            if (submenu.isDone()) {
                closeSubmenu();
            }
            return consumed;
        }
        if (event.isCancel() || event.isCtrlC()) {
            done = true;
            return true;
        }
        if (event.isUp()) {
            model.move(-1);
            return true;
        }
        if (event.isDown()) {
            model.move(1);
            return true;
        }
        if (event.isConfirm() || isActivationSpace(event) && model.query().isEmpty()) {
            activateSelected();
            return true;
        }
        if (event.isKey(KeyCode.BACKSPACE)) {
            var query = model.query();
            if (!query.isEmpty()) {
                model.search(query.substring(0, query.length() - 1));
            }
            return true;
        }
        if (event.code() == KeyCode.CHAR && !event.hasCtrl() && !event.hasAlt()) {
            model.search(model.query() + event.string());
            return true;
        }
        return false;
    }

    private static boolean isActivationSpace(KeyEvent event) {
        return event.code() == KeyCode.CHAR && " ".equals(event.string());
    }

    private void activateSelected() {
        var activation = model.activate();
        if (activation instanceof SettingsListModel.Activation.Cycle cycle) {
            actions.apply(cycle.id(), cycle.newValue());
            if ("cache-miss-notices".equals(cycle.id())) {
                cacheMissNoticesToggled = true;
            }
        } else if (activation instanceof SettingsListModel.Activation.OpenSubmenu open) {
            openSubmenu(open.submenu());
        }
    }

    private void openSubmenu(SettingsItem.Submenu type) {
        switch (type) {
            case WARNINGS -> submenu = new WarningSettingsScreen(session);
            case THEME -> {
                themeRequested = true;
                submenu = new ThemeSubmenuScreen(session, themeController);
            }
            case MODEL_THINKING -> {
                modelThinkingRequested = true;
                submenu = new ModelThinkingLevelScreen(session, true);
            }
            case NONE -> { }
        }
    }

    private void closeSubmenu() {
        submenu.apply(session, ignored -> { });
        submenu = null;
        themeRequested = false;
        modelThinkingRequested = false;
        model.updateValue("model-thinking", thinkingSummary());
        model.updateValue("theme", currentTheme());
    }

    private String thinkingSummary() {
        int count = session.services().settings().accessors().getAllModelThinkingLevels().size();
        return count == 0 ? "(none)" : count + " override" + (count > 1 ? "s" : "");
    }

    private String currentTheme() {
        var value = themeController != null
            ? themeController.getThemeSelection()
            : session.services().settings().accessors().getThemeSetting();
        return value == null ? "system" : value;
    }

    /** Test seam in the screen package; production keys use the same activation path. */
    void activate(String id) {
        model.select(id);
        activateSelected();
    }

    /** Test seam for Esc, including submenu-back behavior. */
    void escape() {
        onKeyEvent(KeyEvent.ofKey(KeyCode.ESCAPE));
    }

    /** Current row id for hosts and interaction tests. */
    public String selectedId() {
        return model.selectedId();
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        // Edits are applied on Enter, not deferred until the main screen closes.
    }

    /** Legacy one-shot request view; internal submenus clear it before the main screen closes. */
    public boolean pollModelThinkingRequest() {
        var requested = modelThinkingRequested;
        modelThinkingRequested = false;
        return requested;
    }

    /** One-shot cache-notice change flag consumed by OverlayManager. */
    public boolean pollCacheMissNoticesToggled() {
        var toggled = cacheMissNoticesToggled;
        cacheMissNoticesToggled = false;
        return toggled;
    }

    /** Legacy one-shot theme request view. */
    public boolean pollThemeRequest() {
        var requested = themeRequested;
        themeRequested = false;
        return requested;
    }

    @Override
    public Element render() {
        return submenu == null ? SettingsListView.render(model) : submenu.render();
    }
}
