package com.pijava.tui.screen;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.coding.agent.core.EnabledModelSet;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.ModelCatalogRefresh;
import com.pijava.coding.agent.core.ScopedModelController;
import com.pijava.coding.agent.core.ScopedModelSelection;
import com.pijava.tui.component.FuzzyMatch;
import com.pijava.tui.util.TamboUIAdapter;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/** pi ScopedModelsSelectorComponent: session-only edits with explicit Ctrl+S persistence. */
public final class ScopedModelsScreen implements ScreenOverlay {

    /** One display row; model is null for a configured but unavailable id. */
    public record ModelItem(String fullId, ModelInfo model, boolean enabled) {}

    private static final int MAX_VISIBLE = 8;

    private final ScopedModelController controller;
    private final KeybindingsManager keys;
    private final Consumer<String> statusSink;
    private final Map<String, ModelInfo> modelsById = new LinkedHashMap<>();
    private List<ModelItem> filteredItems = List.of();
    private int selectedIndex;
    private String query = "";
    private boolean done;
    private long generation;
    private ScopedModelRefresh refresh;

    /** Bind the selector to a session controller and status sink. */
    public ScopedModelsScreen(ScopedModelController controller, KeybindingsManager keys,
                              Consumer<String> statusSink) {
        this.controller = controller;
        this.keys = keys;
        this.statusSink = statusSink;
        rebuild();
    }

    /** Start bounded refresh and apply its result only while this screen generation is alive. */
    public void startRefresh(Supplier<ModelCatalogRefresh.Result> action,
                             Supplier<List<ModelInfo>> availableAfter,
                             Duration timeout, Executor uiExecutor) {
        long ticket = ++generation;
        refresh = new ScopedModelRefresh(timeout);
        refresh.start(action, availableAfter, outcome -> uiExecutor.execute(() -> {
            if (done || ticket != generation) {
                return;
            }
            controller.refreshFinished(outcome.models(), outcome.status());
            rebuild();
        }));
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        var stroke = TamboUIAdapter.toStroke(event);
        if (keys.matches(KeybindingsManager.SELECT_UP, stroke)) {
            moveSelection(-1);
            return true;
        }
        if (keys.matches(KeybindingsManager.SELECT_DOWN, stroke)) {
            moveSelection(1);
            return true;
        }
        boolean reorderUp = keys.matches(KeybindingsManager.MODELS_REORDER_UP, stroke);
        boolean reorderDown = keys.matches(KeybindingsManager.MODELS_REORDER_DOWN, stroke);
        if (reorderUp || reorderDown) {
            reorder(reorderUp ? -1 : 1);
            return true;
        }
        if (keys.matches(KeybindingsManager.SELECT_CONFIRM, stroke)) {
            var item = selected();
            if (item != null) {
                controller.update(ScopedModelSelection.toggle(
                    controller.enabled(), allIds(), item.fullId()));
                rebuild();
            }
            return true;
        }
        if (keys.matches(KeybindingsManager.MODELS_ENABLE_ALL, stroke)) {
            controller.update(ScopedModelSelection.enableAll(
                controller.enabled(), allIds(), query.isEmpty() ? null : filteredIds()));
            rebuild();
            return true;
        }
        if (keys.matches(KeybindingsManager.MODELS_CLEAR_ALL, stroke)) {
            controller.update(ScopedModelSelection.clearAll(
                controller.enabled(), allIds(), query.isEmpty() ? null : filteredIds()));
            rebuild();
            return true;
        }
        if (keys.matches(KeybindingsManager.MODELS_TOGGLE_PROVIDER, stroke)) {
            toggleProvider();
            return true;
        }
        if (keys.matches(KeybindingsManager.MODELS_SAVE, stroke)) {
            controller.persist();
            statusSink.accept("Model selection saved to settings");
            rebuild();
            return true;
        }
        if (event.isCtrlC()) {
            if (!query.isEmpty()) {
                search("");
            } else {
                finish();
            }
            return true;
        }
        if (event.isCancel()) {
            finish();
            return true;
        }
        if (event.isKey(KeyCode.BACKSPACE)) {
            if (!query.isEmpty()) {
                search(query.substring(0, query.length() - 1));
            }
            return true;
        }
        if (event.code() == KeyCode.CHAR && !event.hasCtrl() && !event.hasAlt()) {
            search(query + event.string());
            return true;
        }
        return false;
    }

    private void moveSelection(int delta) {
        if (!filteredItems.isEmpty()) {
            selectedIndex = Math.floorMod(selectedIndex + delta, filteredItems.size());
        }
    }

    private void reorder(int delta) {
        if (!(controller.enabled() instanceof EnabledModelSet.Explicit)) {
            return;
        }
        var item = selected();
        if (item != null && item.enabled()) {
            controller.update(ScopedModelSelection.move(controller.enabled(), item.fullId(), delta));
            selectedIndex = Math.max(0, Math.min(selectedIndex + delta, filteredItems.size() - 1));
            rebuild();
        }
    }

    private void toggleProvider() {
        var item = selected();
        if (item == null || item.model() == null) {
            return;
        }
        var provider = item.model().id().provider();
        var providerIds = modelsById.values().stream()
            .filter(model -> model.id().provider().equals(provider))
            .map(ScopedModelsScreen::fullId).toList();
        boolean allEnabled = providerIds.stream()
            .allMatch(id -> ScopedModelSelection.isEnabled(controller.enabled(), id));
        controller.update(allEnabled
            ? ScopedModelSelection.clearAll(controller.enabled(), allIds(), providerIds)
            : ScopedModelSelection.enableAll(controller.enabled(), allIds(), providerIds));
        rebuild();
    }

    private void search(String value) {
        query = value;
        rebuild();
    }

    private void rebuild() {
        var selectedId = selected() == null ? null : selected().fullId();
        modelsById.clear();
        controller.availableModels().forEach(model -> modelsById.put(fullId(model), model));
        var items = ScopedModelSelection.sortedIds(controller.enabled(), allIds()).stream()
            .map(id -> new ModelItem(id, modelsById.get(id),
                ScopedModelSelection.isEnabled(controller.enabled(), id)))
            .toList();
        filteredItems = query.isEmpty() ? items : items.stream()
            .filter(item -> FuzzyMatch.fuzzyMatch(query, searchText(item)).matches()).toList();
        selectedIndex = selectedId == null
            ? Math.min(selectedIndex, Math.max(0, filteredItems.size() - 1))
            : Math.max(0, filteredItems.indexOf(filteredItems.stream()
                .filter(item -> item.fullId().equals(selectedId)).findFirst().orElse(null)));
    }

    private static String searchText(ModelItem item) {
        return item.model() == null ? item.fullId()
            : item.model().id().modelName() + " " + item.model().id().provider()
                + " " + item.model().displayName();
    }

    private List<String> allIds() {
        return List.copyOf(modelsById.keySet());
    }

    private List<String> filteredIds() {
        return filteredItems.stream().map(ModelItem::fullId).toList();
    }

    private ModelItem selected() {
        return selectedIndex >= 0 && selectedIndex < filteredItems.size()
            ? filteredItems.get(selectedIndex) : null;
    }

    private void finish() {
        done = true;
        generation++;
        if (refresh != null) {
            refresh.close();
        }
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public void apply(com.pijava.coding.agent.core.AgentSession session,
                      Consumer<com.pijava.coding.agent.core.AgentSession> switcher) {
        finish();
    }

    /** Current filtered rows for interaction tests and the renderer. */
    public List<ModelItem> filteredItems() {
        return filteredItems;
    }

    /** Footer counts and unsaved marker. */
    public String footerText() {
        int available = modelsById.size();
        String count;
        if (controller.enabled() instanceof EnabledModelSet.All) {
            count = "all enabled";
        } else {
            var ids = ((EnabledModelSet.Explicit) controller.enabled()).orderedIds();
            long enabledCount = ids.stream().filter(modelsById::containsKey).count();
            long unavailable = ids.stream().filter(id -> !modelsById.containsKey(id)).count();
            count = enabledCount + "/" + available + " enabled"
                + (unavailable > 0 ? " · " + unavailable + " unavailable" : "");
        }
        return "enter toggle · ctrl+a all · ctrl+x clear · ctrl+p provider · alt+↑/↓ reorder · ctrl+s save · "
            + count + (controller.dirty() ? " (unsaved)" : "");
    }

    /** Current refresh message. */
    public String refreshStatus() {
        return controller.refreshStatus();
    }

    @Override
    public Element render() {
        var rows = new ArrayList<Element>();
        rows.add(TamboUIAdapter.text("Model Configuration").cyan());
        rows.add(TamboUIAdapter.text("Session-only. Ctrl+S to save to settings.").dim());
        rows.add(TamboUIAdapter.text("> " + query));
        if (filteredItems.isEmpty()) {
            rows.add(TamboUIAdapter.text("  No matching models").dim());
        } else {
            int start = Math.max(0, Math.min(selectedIndex - MAX_VISIBLE / 2,
                filteredItems.size() - MAX_VISIBLE));
            int end = Math.min(start + MAX_VISIBLE, filteredItems.size());
            for (int i = start; i < end; i++) {
                var item = filteredItems.get(i);
                boolean selected = i == selectedIndex;
                var id = item.model() == null ? item.fullId() : item.model().id().modelName();
                var badge = item.model() == null ? " [unavailable]"
                    : " [" + item.model().id().provider() + "]";
                var text = TamboUIAdapter.text((selected ? "→ " : "  ")
                    + (item.model() != null && item.enabled() ? "✓ " : "  ") + id + badge);
                rows.add(selected ? text.cyan() : text);
            }
            if (start > 0 || end < filteredItems.size()) {
                rows.add(TamboUIAdapter.text("  (" + (selectedIndex + 1) + "/" + filteredItems.size() + ")").dim());
            }
            var item = selected();
            if (item != null) {
                rows.add(TamboUIAdapter.text("  " + (item.model() == null ? "Model unavailable"
                    : "Model Name: " + item.model().displayName())).dim());
            }
        }
        rows.add(TamboUIAdapter.text("  " + refreshStatus()).dim());
        rows.add(TamboUIAdapter.text("  " + footerText()).dim());
        return TamboUIAdapter.panel("Model Configuration", TamboUIAdapter.column(rows)).fill();
    }

    private static String fullId(ModelInfo model) {
        return model.id().provider() + "/" + model.id().modelName();
    }
}
