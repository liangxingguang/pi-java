package com.pijava.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.pijava.agent.entry.Entry;
import com.pijava.agent.harness.Context;
import com.pijava.ai.message.Message;
import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.extension.ExtensionCompleteOptions;
import com.pijava.coding.agent.extension.ExtensionModelInfo;
import com.pijava.coding.agent.extension.ExtensionModelRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** docs/52 §2.1：preset 验收——配置合并、描述/顺序纯函数、applyPreset、状态恢复、注入。 */
class PresetExtensionTest {

    @TempDir Path tmp;

    private static ExtensionModelInfo model(String provider, String id) {
        return new ExtensionModelInfo(ModelId.of(provider, id), "M", 128000, 8192);
    }

    @Test
    void loadPresetsProjectOverridesGlobal() throws Exception {
        var global = tmp.resolve("global.json");
        var project = tmp.resolve("project.json");
        Files.writeString(global, """
            {"plan":{"provider":"openai","model":"gpt-5.2","instructions":"GLOBAL"},
             "only-global":{"model":"x","instructions":"OG"}}
            """);
        Files.writeString(project, """
            {"plan":{"model":"claude","instructions":"PROJECT"},"only-project":{"model":"y"}}
            """);

        var presets = PresetExtension.loadPresets(global, project);

        assertThat(presets).containsKeys("plan", "only-global", "only-project");
        assertThat(presets.get("plan").instructions()).isEqualTo("PROJECT");
        assertThat(presets.get("plan").model()).isEqualTo("claude");
        assertThat(presets.get("only-global").instructions()).isEqualTo("OG");
    }

    @Test
    void buildPresetDescriptionVerbatim() {
        var instructions = "You are in planning mode and must explore thoroughly now";
        var preset = new PresetExtension.Preset("google", "gemini", "high",
            List.of("read", "bash"), instructions);
        assertThat(PresetExtension.buildPresetDescription(preset))
            .isEqualTo("google/gemini | thinking:high | tools:read,bash | \""
                + instructions.substring(0, 27) + "...\"");
    }

    @Test
    void presetOrderSorted() {
        var presets = new java.util.LinkedHashMap<String, PresetExtension.Preset>();
        presets.put("implement", null);
        presets.put("plan", null);
        presets.put("alpha", null);
        assertThat(PresetExtension.getPresetOrder(presets))
            .containsExactly("alpha", "implement", "plan");
    }

    @Test
    void restoreNamePicksLastPresetState() {
        var entries = List.<Entry>of(
            new Entry.Custom("c1", 0, null, Instant.ofEpochMilli(1), "preset-state",
                Map.of("name", "old")),
            new Entry.Custom("c2", 1, "c1", Instant.ofEpochMilli(2), "other", Map.of()),
            new Entry.Custom("c3", 2, "c2", Instant.ofEpochMilli(3), "preset-state",
                Map.of("name", "recent")));
        assertThat(PresetExtension.restoreName(entries)).isEqualTo("recent");
        assertThat(PresetExtension.restoreName(List.of())).isNull();
    }

    @Test
    void applyPresetInjectsInstructionsAndWarnsUnknownTools() {
        var w = new W3Context();
        w.modelRegistry = registry();
        w.allTools = List.of(new com.pijava.coding.agent.extension.ExtensionToolInfo(
            "read", "read", List.of()));
        var ext = new PresetExtension();

        ext.applyPreset("plan", new PresetExtension.Preset("openai", "gpt-5.2", null,
            List.of("read", "ghost"), "PLAN INSTRUCTIONS"), w.ctx);

        assertThat(ext.activePresetName()).isEqualTo("plan");
        assertThat(w.activeTools).containsExactly("read");
        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("Preset \"plan\": Unknown tools: ghost");
        assertThat(ext.injectedPrompt("BASE")).isEqualTo("BASE\n\nPLAN INSTRUCTIONS");
    }

    @Test
    void applyPresetNoApiKeyWarnsVerbatim() {
        var w = new W3Context();
        w.setModelResult = false;
        w.modelRegistry = registry();
        var ext = new PresetExtension();

        ext.applyPreset("p", new PresetExtension.Preset("openai", "gpt-5.2", null,
            List.of(), null), w.ctx);

        assertThat(w.ui.notices()).extracting(ExampleFixtures.ScriptedUi.Notice::message)
            .contains("Preset \"p\": No API key for openai/gpt-5.2");
    }

    @Test
    void noInjectionWithoutInstructions() {
        var w = new W3Context();
        var ext = new PresetExtension();
        ext.applyPreset("p", new PresetExtension.Preset(null, null, null, List.of(), null), w.ctx);
        assertThat(ext.injectedPrompt("BASE")).isNull();
    }

    private static ExtensionModelRegistry registry() {
        return new ExtensionModelRegistry() {
            public Optional<ExtensionModelInfo> find(String p, String m) {
                return Optional.of(model(p, m));
            }
            public boolean hasConfiguredAuth(ExtensionModelInfo m) { return true; }
            public Message.AssistantMessage complete(ExtensionModelInfo m, Context c,
                                                     ExtensionCompleteOptions o) {
                throw new AssertionError();
            }
        };
    }
}
