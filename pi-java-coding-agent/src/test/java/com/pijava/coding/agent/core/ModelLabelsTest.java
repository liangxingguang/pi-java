package com.pijava.coding.agent.core;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/62（L7 W2）：pi {@code getDistinctModelName}
 * （{@code modes/interactive/model-search.ts:19-34}）逐字。
 */
class ModelLabelsTest {

    @Test
    void idAlreadyCoversTheName() {
        assertThat(ModelLabels.distinctName("claude-sonnet-4-5", "Claude Sonnet 4.5")).isNull();
        assertThat(ModelLabels.distinctName(
            "eu.anthropic.claude-sonnet-4-5-20250929-v1:0", "Claude Sonnet 4.5 (EU)")).isNull();
    }

    @Test
    void nameAddsInformation() {
        assertThat(ModelLabels.distinctName("mistral-small-2603", "Mistral Small 4"))
            .isEqualTo("Mistral Small 4");
        assertThat(ModelLabels.distinctName("claude-3-5-sonnet", "Claude 3.5 Sonnet"))
            .as("单字符词 3/5 必须在 id 词表里整词命中")
            .isNull();
    }

    @Test
    void unicodeWordsSplitLikePi() {
        assertThat(ModelLabels.distinctName("qwen3-max", "Qwen3 Max")).isNull();
        assertThat(ModelLabels.distinctName("qwen3-max", "通义千问 Max")).isEqualTo("通义千问 Max");
        assertThat(ModelLabels.distinctName("müllers-modell", "Müllers Modell"))
            .as("重音字母算词字符")
            .isNull();
    }

    @Test
    void missingNameIsNotShown() {
        assertThat(ModelLabels.distinctName("p/m", null)).isNull();
        assertThat(ModelLabels.distinctName("p/m", "   ")).isNull();
    }
}
