package com.pijava.ai.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.pijava.ai.AbortSignal;
import com.pijava.ai.AbortedException;
import com.pijava.ai.model.ModelCapability;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.PricingInfo;

/**
 * pi {@code models-store.ts:28-46} —— 内存实现的 operation signal（docs/11 #31）。
 */
class InMemoryModelsStoreTest {

    private static final ModelInfo MODEL = new ModelInfo(
        ModelId.of("anthropic", "claude-fable-5"), "Claude Fable 5",
        Set.of(ModelCapability.TEXT), 200_000, 64_000, false,
        new PricingInfo(5.0, 15.0));

    @Test
    void abortedSignalFailsEveryOperation() {
        var store = new InMemoryModelsStore();
        store.write("p", ModelsStoreEntry.of(List.of(MODEL)));

        var signal = AbortSignal.create();
        signal.abort();
        var options = new ModelsStoreOperationOptions(signal);

        assertThatThrownBy(() -> store.read("p", options))
            .isInstanceOf(AbortedException.class);
        assertThatThrownBy(() -> store.write("q", ModelsStoreEntry.of(List.of(MODEL)), options))
            .isInstanceOf(AbortedException.class);
        assertThatThrownBy(() -> store.delete("p", options))
            .isInstanceOf(AbortedException.class);

        assertThat(store.read("p")).isPresent();
        assertThat(store.read("q")).isEmpty();
    }
}
