package com.pijava.coding.agent.core;

import java.util.List;
import java.util.Optional;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.ScopedModel;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/65（L7 W5，pi {@code interactive-mode.ts:1507-1521}）：{@code Model scope:} 行。
 */
class ModelScopeNoticeTest {

    private static ScopedModel scoped(String provider, String model, String level) {
        var info = ModelInfo.minimal(ModelId.of(provider, model));
        return level == null ? ScopedModel.of(info) : ScopedModel.of(info, level);
    }

    @Test
    void listsIdsAndExplicitThinkingLevels() {
        assertThat(ModelScopeNotice.text(
            List.of(scoped("anthropic", "claude-x", null),
                scoped("openai", "gpt-y", "high")),
            "Ctrl+P"))
            .isEqualTo("Model scope: claude-x, gpt-y:high (Ctrl+P to cycle)");
    }

    @Test
    void emptyScopeProducesNoLine() {
        assertThat(ModelScopeNotice.text(List.of(), "Ctrl+P")).isNull();
        assertThat(ModelScopeNotice.text(null, null)).isNull();
    }

    @Test
    void cycleHintIsOptional() {
        assertThat(ModelScopeNotice.text(List.of(scoped("p", "m", null)), null))
            .isEqualTo("Model scope: m");
        assertThat(ModelScopeNotice.text(
            List.of(new ScopedModel(ModelInfo.minimal(ModelId.of("p", "m")),
                Optional.of(" "))), "K"))
            .isEqualTo("Model scope: m (K to cycle)");
    }
}
