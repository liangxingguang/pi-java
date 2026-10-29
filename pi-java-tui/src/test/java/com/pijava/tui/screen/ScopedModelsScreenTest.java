package com.pijava.tui.screen;

import java.util.ArrayList;
import java.util.List;

import com.pijava.ai.catalog.ModelInfo;
import com.pijava.coding.agent.core.EnabledModelSet;
import com.pijava.coding.agent.core.InMemorySettingsStorage;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.ScopedModelController;
import com.pijava.coding.agent.core.SettingsManager;
import com.pijava.ai.model.ModelId;
import com.pijava.ai.model.ScopedModel;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopedModelsScreenTest {

    @Test
    void toggleIsSessionOnlyUntilCtrlS() {
        var rig = new Rig("p/a", "p/b");

        rig.screen.onKeyEvent(enter());
        assertThat(rig.controller.enabled()).isEqualTo(new EnabledModelSet.Explicit(List.of("p/b")));
        assertThat(rig.settings.accessors().getEnabledModelsOrNull()).isNull();
        assertThat(rig.screen.footerText()).contains("(unsaved)");

        rig.screen.onKeyEvent(ctrl('s'));

        assertThat(rig.settings.accessors().getEnabledModelsOrNull()).containsExactly("p/b");
        assertThat(rig.screen.footerText()).doesNotContain("(unsaved)");
        assertThat(rig.status).contains("Model selection saved to settings");
    }

    @Test
    void explicitScopeCanBeReorderedWithAltDown() {
        var rig = new Rig("p/a", "p/b", "p/c");
        rig.settings.accessors().setEnabledModels(List.of("p/a", "p/b"));
        rig.open();

        rig.screen.onKeyEvent(new KeyEvent(KeyCode.DOWN, KeyModifiers.ALT, 0));

        assertThat(rig.controller.enabled()).isEqualTo(
            new EnabledModelSet.Explicit(List.of("p/b", "p/a")));
        assertThat(rig.sink.scope).extracting(sm -> sm.model().id().modelName())
            .containsExactly("b", "a");
    }

    @Test
    void providerToggleUsesCurrentProviderAndUnavailableRowsStayVisible() {
        var rig = new Rig("p/a", "p/b");
        rig.settings.accessors().setEnabledModels(List.of("old"));
        rig.open();
        assertThat(rig.screen.filteredItems()).anyMatch(item -> item.fullId().equals("old")
            && item.model() == null && item.enabled());

        rig.screen.onKeyEvent(ctrl('p'));

        assertThat(rig.controller.enabled()).isEqualTo(new EnabledModelSet.Explicit(List.of("old")));
        assertThat(rig.sink.scope).isEmpty();
    }

    @Test
    void searchCtrlCFirstClearsQueryThenCancels() {
        var rig = new Rig("p/a", "p/b");
        rig.screen.onKeyEvent(KeyEvent.ofChar('b'));
        assertThat(rig.screen.filteredItems()).extracting(ScopedModelsScreen.ModelItem::fullId)
            .containsExactly("p/b");

        rig.screen.onKeyEvent(ctrl('c'));
        assertThat(rig.screen.isDone()).isFalse();
        assertThat(rig.screen.filteredItems()).hasSize(2);

        rig.screen.onKeyEvent(ctrl('c'));
        assertThat(rig.screen.isDone()).isTrue();
    }

    private static KeyEvent enter() {
        return KeyEvent.ofKey(KeyCode.ENTER);
    }

    private static KeyEvent ctrl(char c) {
        return KeyEvent.ofChar(c, KeyModifiers.CTRL);
    }

    private static final class Rig {
        private final SettingsManager settings = SettingsManager.withStorage(new InMemorySettingsStorage());
        private final Sink sink;
        private ScopedModelController controller;
        private ScopedModelsScreen screen;
        private final List<String> status = new ArrayList<>();

        private Rig(String... ids) {
            sink = new Sink(List.of(ids).stream()
                .map(id -> ModelInfo.minimal(ModelId.of(id.substring(0, id.indexOf('/')),
                    id.substring(id.indexOf('/') + 1)))).toList(), settings);
            open();
        }

        private void open() {
            controller = ScopedModelController.open(sink, sink.models);
            screen = new ScopedModelsScreen(controller, new KeybindingsManager(), status::add);
        }
    }

    private static final class Sink implements ScopedModelController.ScopeSink {
        private final List<ModelInfo> models;
        private final List<ScopedModel> scope = new ArrayList<>();
        private final SettingsManager settings;

        private Sink(List<ModelInfo> models, SettingsManager settings) {
            this.models = models;
            this.settings = settings;
        }

        @Override public List<ScopedModel> sessionScopedModels() { return List.copyOf(scope); }
        @Override public void setSessionScopedModels(List<ScopedModel> models) {
            scope.clear();
            scope.addAll(models);
        }
        @Override public SettingsManager settings() { return settings; }
    }
}
