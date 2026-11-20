package com.pijava.coding.agent.core;

import java.util.List;
import java.util.stream.Collectors;

import com.pijava.ai.model.ScopedModel;

/**
 * docs/65（L7 W5，pi {@code interactive-mode.ts:1507-1521} {@code getModelScopeNotice}）：
 * 模型 scope 行——pi 以前 {@code console.log} 到 stdout（非全屏臂），现在渲染到聊天
 * header（Java 的对位＝welcome 卡之后的 dim 系统文本）。
 *
 * <p>行形逐字：{@code Model scope: <id[:level], ...>} ＋ 可选
 * {@code  (<Key> to cycle)}。scope 为空（＝不限模型）时**不出行**。</p>
 */
public final class ModelScopeNotice {

    private ModelScopeNotice() {}

    /**
     * @param models      会话 scope（{@code AgentSession.scopedModels()}）
     * @param cycleKeyText 轮转键的展示文本（如 {@code Ctrl+P}），null/空 ⇒ 省略提示
     * @return 整行文本；scope 为空 ⇒ {@code null}
     */
    public static String text(List<ScopedModel> models, String cycleKeyText) {
        if (models == null || models.isEmpty()) {
            return null;
        }
        var body = models.stream()
            .map(ModelScopeNotice::entry)
            .collect(Collectors.joining(", "));
        var line = "Model scope: " + body;
        if (cycleKeyText != null && !cycleKeyText.isBlank()) {
            line += " (" + cycleKeyText + " to cycle)";
        }
        return line;
    }

    /** pi {@code <id[:level]>}：括号里的思考档是模式显式给出的那一个。 */
    private static String entry(ScopedModel scoped) {
        var id = scoped.model().id().modelName();
        return scoped.thinkingLevel()
            .filter(level -> !level.isBlank())
            .map(level -> id + ":" + level)
            .orElse(id);
    }
}
