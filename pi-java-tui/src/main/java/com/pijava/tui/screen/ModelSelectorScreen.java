package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Consumer;

import com.pijava.ai.model.ModelId;
import com.pijava.ai.provider.ModelsJsonConfig;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.coding.agent.core.ModelLabels;
import com.pijava.tui.component.SelectList;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * Model selector opened by {@code /model} and Ctrl+L (Phase 3 design §8.3).
 *
 * <p>docs/62（L7 W2，pi {@code components/model-selector.ts}）：PgUp/PgDn 整页翻
 * （{@code MAX_VISIBLE=10}，pi {@code :38}）；行 = {@code provider/model} +
 * 去重别名 {@code (name)}（pi {@code :331-334}，纯文本见 D264）。</p>
 */
public final class ModelSelectorScreen implements ScreenOverlay {

    /** pi {@code model-selector.ts:38}。 */
    private static final int MAX_VISIBLE = 10;

    private final SelectList<ModelRow> list;
    private final AgentSession session;

    /**
     * Creates the model selector listing all built-in models.
     *
     * @param session the session whose model is switched on confirm
     */
    public ModelSelectorScreen(AgentSession session) {
        this.session = session;
        var models = new ArrayList<ModelRow>();
        for (var model : ModelsJsonConfig.allModels().listModels()) {
            models.add(new ModelRow(model.id(), model.displayName()));
        }
        models.sort(Comparator.comparing(ModelRow::fullId));
        this.list = new SelectList<>(models, ModelRow::label, MAX_VISIBLE);
    }

    /** 行文案（pi {@code :331-334}）：{@code provider/model} + 去重别名。 */
    static String label(String modelName, String provider, String displayName) {
        var id = provider + "/" + modelName;
        var name = ModelLabels.distinctName(modelName, displayName);
        return name == null ? id : id + " (" + name + ")";
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
        list.selected().ifPresent(row -> session.setModel(row.id()));
    }

    @Override
    public Element render() {
        return list.render();
    }

    private record ModelRow(ModelId id, String displayName) {
        String fullId() {
            return id.provider() + "/" + id.modelName();
        }

        String label() {
            return ModelSelectorScreen.label(id.modelName(), id.provider(), displayName);
        }
    }
}
