package com.pijava.coding.agent.core;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.ScopedModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedModelControllerTest {

    @Test
    void changesAreSessionOnlyUntilPersist() {
        var sink = ScopedModelControllerTestRig.sinkWithModels("a", "b");
        var controller = ScopedModelController.open(sink, sink.availableModels());

        controller.update(new EnabledModelSet.Explicit(List.of("p/a")));

        assertThat(sink.settings().accessors().getEnabledModelsOrNull()).isNull();
        assertThat(sink.sessionScope()).extracting(sm -> sm.model().id().modelName())
            .containsExactly("a");
        assertThat(controller.dirty()).isTrue();

        controller.persist();

        assertThat(sink.settings().accessors().getEnabledModelsOrNull()).containsExactly("p/a");
        assertThat(controller.dirty()).isFalse();
    }

    @Test
    void lateRefreshDoesNotReplaceUserSelection() {
        var sink = ScopedModelControllerTestRig.sinkWithModels("a");
        var controller = ScopedModelController.open(sink, sink.availableModels());
        controller.update(new EnabledModelSet.Explicit(List.of("p/a")));

        controller.refreshFinished(sink.availableModels("a", "b"), "Model catalogs refreshed.");

        assertThat(controller.enabled()).isEqualTo(new EnabledModelSet.Explicit(List.of("p/a")));
        assertThat(controller.availableModels()).hasSize(2);
        assertThat(controller.refreshStatus()).isEqualTo("Model catalogs refreshed.");
    }

    @Test
    void refreshWithoutUserChangesRecomputesConfiguredPatterns() {
        var sink = ScopedModelControllerTestRig.sinkWithModels("a");
        sink.settings().accessors().setEnabledModels(List.of("p/a"));
        var controller = ScopedModelController.open(sink, sink.availableModels());

        controller.refreshFinished(sink.availableModels("a", "b"), "Model catalogs refreshed.");

        assertThat(controller.enabled()).isEqualTo(new EnabledModelSet.Explicit(List.of("p/a")));
        assertThat(sink.sessionScope()).extracting(sm -> sm.model().id().modelName())
            .containsExactly("a");
    }

    @Test
    void configuredNoMatchPatternRemainsVisibleAsUnavailable() {
        var sink = ScopedModelControllerTestRig.sinkWithModels("a", "b");
        sink.settings().accessors().setEnabledModels(List.of("p/a", "old"));

        var controller = ScopedModelController.open(sink, sink.availableModels());

        assertThat(controller.enabled()).isEqualTo(
            new EnabledModelSet.Explicit(List.of("p/a", "old")));
        assertThat(sink.sessionScope()).extracting(sm -> sm.model().id().modelName())
            .containsExactly("a");
    }

    @Test
    void allAvailableEnabledWithUnavailableStillClearsSessionScope() {
        var sink = ScopedModelControllerTestRig.sinkWithModels("a", "b");
        var controller = ScopedModelController.open(sink, sink.availableModels());

        controller.update(new EnabledModelSet.Explicit(List.of("p/a", "p/b", "old")));

        assertThat(sink.sessionScope()).isEmpty();
        controller.persist();
        assertThat(sink.settings().accessors().getEnabledModelsOrNull())
            .containsExactly("p/a", "p/b", "old");
    }

    @Test
    void allClearsScopeAndPersistsAsUnset() {
        var sink = ScopedModelControllerTestRig.sinkWithModels("a", "b");
        sink.settings().accessors().setEnabledModels(List.of("p/a"));
        var controller = ScopedModelController.open(sink, sink.availableModels());

        controller.update(new EnabledModelSet.All());
        controller.persist();

        assertThat(sink.sessionScope()).isEmpty();
        assertThat(sink.settings().accessors().getEnabledModelsOrNull()).isNull();
    }

    @Test
    void existingSessionScopeKeepsExplicitThinkingLevelOnOpen() {
        var sink = ScopedModelControllerTestRig.sinkWithModels("a", "b");
        sink.scope.add(ScopedModel.of(sink.models.getFirst(), "high"));

        ScopedModelController.open(sink, sink.availableModels());

        assertThat(sink.sessionScope()).hasSize(1);
        assertThat(sink.sessionScope().getFirst().thinkingLevel()).contains("high");
    }

    /** 记录型 ScopeSink；不创建 AgentSession，也不读真实 home。 */
    private static final class ScopedModelControllerTestRig implements ScopedModelController.ScopeSink {
        private final SettingsManager settings = SettingsManager.withStorage(new InMemorySettingsStorage());
        private final List<ScopedModel> scope = new ArrayList<>();
        private final List<ModelInfo> models;

        private ScopedModelControllerTestRig(List<ModelInfo> models) {
            this.models = models;
        }

        static ScopedModelControllerTestRig sinkWithModels(String... ids) {
            return new ScopedModelControllerTestRig(List.of(ids).stream()
                .map(id -> ModelInfo.minimal(ModelId.of("p", id))).toList());
        }

        List<ModelInfo> availableModels(String... ids) {
            return List.of(ids).stream().map(id -> ModelInfo.minimal(ModelId.of("p", id))).toList();
        }

        List<ModelInfo> availableModels() {
            return models;
        }

        List<ScopedModel> sessionScope() {
            return scope;
        }

        @Override
        public List<ScopedModel> sessionScopedModels() {
            return List.copyOf(scope);
        }

        @Override
        public void setSessionScopedModels(List<ScopedModel> models) {
            scope.clear();
            scope.addAll(models);
        }

        @Override
        public SettingsManager settings() {
            return settings;
        }
    }
}
