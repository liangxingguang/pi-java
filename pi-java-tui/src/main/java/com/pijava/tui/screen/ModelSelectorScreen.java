package com.pijava.tui.screen;

import java.util.List;
import java.util.function.Consumer;

import com.pijava.ai.provider.builtin.ProviderCatalog;
import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.component.SelectList;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Model selector opened by {@code /model} and Ctrl+L (Phase 3 design §8.3).
 */
public final class ModelSelectorScreen implements ScreenOverlay {

    private final SelectList<String> list;
    private final AgentSession session;

    /**
     * Creates the model selector listing all built-in models.
     *
     * @param session the session whose model is switched on confirm
     */
    public ModelSelectorScreen(AgentSession session) {
        this.session = session;
        List<String> models = ProviderCatalog.allModels().listModels().stream()
            .map(m -> m.id().provider() + "/" + m.id().modelName())
            .sorted()
            .toList();
        this.list = new SelectList<>(models, s -> s);
    }

    /** Handle selector keys; returns true when consumed. */
    public boolean onKeyEvent(KeyEvent event) {
        return list.onKeyEvent(event);
    }

    @Override
    public boolean isDone() {
        return list.confirmed() || list.cancelled();
    }

    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        list.selected().ifPresent(selection -> {
            var parts = selection.split("/", 2);
            session.harness().setModel(ModelId.of(parts[0], parts[1]));
        });
    }

    @Override
    public Element render() {
        return list.render();
    }
}
