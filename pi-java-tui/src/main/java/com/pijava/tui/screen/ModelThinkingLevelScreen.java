package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.catalog.ModelThinkingLevels;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.provider.ModelsJsonConfig;
import com.pijava.ai.thinking.ModelThinkingLevel;
import com.pijava.coding.agent.cli.ThinkingLevels;
import com.pijava.coding.agent.core.AgentSession;
import com.pijava.tui.component.SelectList;

import dev.tamboui.toolkit.element.Element;
import dev.tamboui.tui.event.KeyEvent;

/**
 * 批 12 T14（docs/23 §3）：/settings 的 "Default thinking level per model"
 * 两级子菜单——pi settings-selector.ts:607-700 的 SteppedSubmenu 对位
 * （step "model" ⇒ step "level"），提交回调 pi interactive-mode.ts:5009-5019
 * 对位（当前模型即时生效）。
 *
 * <p>差异注记（不登记窄差）：pi 模型步可搜索（SteppedSubmenu 自带）；java
 * {@link SelectList} 自带 fuzzy filter（与 ModelSelectorScreen 同域的 UX 差，
 * S14 判定内）。</p>
 */
public final class ModelThinkingLevelScreen implements ScreenOverlay {

    private enum Step { MODEL, LEVEL }

    /** 级别项：{@code level} 为 null ⇒ "(clear override)"（pi :674-679）。 */
    private record LevelChoice(String label, String level) {}

    private final AgentSession session;
    private final SelectList<ModelInfo> modelList;
    private SelectList<LevelChoice> levelList;
    private ModelInfo chosenModel;
    private LevelChoice choice;          // null ⇒ 取消
    private Step step = Step.MODEL;
    private boolean done;

    /**
     * @param session 当前会话（模型目录、override 读写、当前模型即时生效）
     */
    public ModelThinkingLevelScreen(AgentSession session) {
        this.session = session;
        var models = new ArrayList<>(ModelsJsonConfig.allModels().listModels());
        models.sort(java.util.Comparator.comparing(
            m -> m.id().provider() + "/" + m.id().modelName()));
        // pi step "model"：当前模型置顶。
        var current = session.harness().getModel();
        if (current != null) {
            models.sort(java.util.Comparator.comparing(
                (ModelInfo m) -> m.id().equals(current) ? 0 : 1));
        }
        var accessors = session.services().settings().accessors();
        this.modelList = new SelectList<>(List.copyOf(models), m -> {
            var override = accessors.getModelThinkingLevel(
                m.id().provider(), m.id().modelName());
            // pi：description 显示现有 override。
            return m.id().provider() + "/" + m.id().modelName()
                + (override == null ? "" : "  (thinking: " + override + ")");
        });
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (step == Step.MODEL) {
            var consumed = modelList.onKeyEvent(event);
            if (modelList.confirmed()) {
                modelList.selected().ifPresent(this::enterLevelStep);
            } else if (modelList.cancelled()) {
                done = true;
            }
            return consumed;
        }
        if (event.isCancel() && levelList.filter().isEmpty()) {
            // Esc 回退到模型步（pi SteppedSubmenu 的 back）。
            step = Step.MODEL;
            modelList.reset();
            return true;
        }
        var consumed = levelList.onKeyEvent(event);
        if (levelList.confirmed()) {
            choice = levelList.selected().orElse(null);
            done = true;
        } else if (levelList.cancelled()) {
            done = true;
        }
        return consumed;
    }

    /** pi step "level"（:660-680）：reasoning ⇒ 支持档，否则只有 "off"；已有 override 追加 clear 项。 */
    private void enterLevelStep(ModelInfo model) {
        this.chosenModel = model;
        var levels = model.capabilities().contains(ModelCapability.THINKING)
            ? ModelThinkingLevels.supported(model)
            : List.of(ModelThinkingLevel.off());
        var choices = new ArrayList<LevelChoice>();
        for (var level : levels) {
            choices.add(new LevelChoice(level.label(), level.label()));
        }
        var accessors = session.services().settings().accessors();
        if (accessors.getModelThinkingLevel(
                model.id().provider(), model.id().modelName()) != null) {
            choices.add(new LevelChoice("(clear override)", null));
        }
        this.levelList = new SelectList<>(List.copyOf(choices), LevelChoice::label);
        this.step = Step.LEVEL;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    /**
     * pi 提交回调（:685-700＋interactive-mode.ts:5009-5019 对位）：clear ⇒
     * 删 override（当前模型回退全局默认 ?? "off"）；否则写 override（当前模型
     * 即时 {@code setThinkingLevel}）。写后统一 flush（SettingsScreen 惯例）。
     */
    @Override
    public void apply(AgentSession session, Consumer<AgentSession> switcher) {
        if (choice == null || chosenModel == null) {
            return;
        }
        var accessors = session.services().settings().accessors();
        var provider = chosenModel.id().provider();
        var modelId = chosenModel.id().modelName();
        if (choice.level() == null) {
            accessors.removeModelThinkingLevel(provider, modelId);
            if (isCurrentModel(provider, modelId)) {
                var globalDefault = accessors.getDefaultThinkingLevel();
                session.setThinkingLevel(
                    ThinkingLevels.parse(globalDefault == null ? "off" : globalDefault));
            }
        } else {
            accessors.setModelThinkingLevel(provider, modelId, choice.level());
            if (isCurrentModel(provider, modelId)) {
                session.setThinkingLevel(ThinkingLevels.parse(choice.level()));
            }
        }
        session.services().settings().flush();
    }

    private boolean isCurrentModel(String provider, String modelId) {
        var current = session.harness().getModel();
        return current != null
            && current.provider().equals(provider)
            && current.modelName().equals(modelId);
    }

    @Override
    public Element render() {
        return step == Step.MODEL ? modelList.render() : levelList.render();
    }
}
