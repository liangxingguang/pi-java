package com.pijava.examples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pijava.agent.entry.Entry;
import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.ExtensionFlagOptions;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.BeforeAgentStartEventResult;

/**
 * pi {@code examples/extensions/preset.ts:1-438} 移植（docs/52 §2.1）——
 * flag {@code --preset}、命令 {@code /preset}、快捷键 Ctrl+Shift+U；before_agent_start
 * 注入 instructions；状态持久化到 {@code preset-state}。
 * 改动点：列表 capturesViewportInput 属性对齐（D99）；主题色降级纯文本（D235）。
 */
public final class PresetExtension implements PiExtension {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** pi Preset 形状。 */
    record Preset(String provider, String model, String thinkingLevel,
                  List<String> tools, String instructions) {}

    /** 首次应用前快照的状态。 */
    static final class OriginalState {
        java.util.Optional<com.pijava.coding.agent.extension.ExtensionModelInfo> model;
        Object thinkingLevel;
        List<String> tools;
    }

    private Map<String, Preset> presets = Map.of();
    private String activePresetName;
    private Preset activePreset;
    private OriginalState originalState;

    @Override
    public String name() {
        return "preset";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.registerFlag("preset", ExtensionFlagOptions.ofString(
            "Preset configuration to use", null));
        ctx.slashCommands().register(ExampleCommands.command("preset",
            "Switch preset configuration", args -> cycleOrSelect(ctx, args)));
        ctx.registerShortcut("ctrl+shift+u", "Cycle presets", c -> cycle(ctx));

        ctx.on(ExtensionEventSpec.BEFORE_AGENT_START, (event, c) -> {
            var injected = injectedPrompt(event.systemPrompt());
            return injected == null ? null : new BeforeAgentStartEventResult(null, injected);
        });
        ctx.on(ExtensionEventSpec.SESSION_START, (event, c) -> {
            onSessionStart(c);
            return null;
        });
        ctx.on(ExtensionEventSpec.TURN_START, (event, c) -> {
            if (activePresetName != null) {
                c.appendEntry("preset-state", Map.of("name", activePresetName));
            }
            return null;
        });
    }

    /** pi L397-430：加载配置＋flag/恢复。 */
    void onSessionStart(ExtensionContext ctx) {
        var cwd = ctx.cwd();
        presets = loadPresets(agentPresetsPath(), Path.of(cwd, ".pi", "presets.json"));
        var flag = ctx.getFlag("preset");
        if (flag instanceof String name && !name.isEmpty()) {
            var preset = presets.get(name);
            if (preset != null) {
                applyPreset(name, preset, ctx);
                ctx.ui().notify("Preset \"" + name + "\" activated", NotifyType.INFO);
            } else {
                ctx.ui().notify("Unknown preset \"" + name + "\". Available: "
                    + available(), NotifyType.WARNING);
            }
        }
        var restored = restoreName(ctx.sessionManager().getEntries());
        if (restored != null && !(flag instanceof String)) {
            var preset = presets.get(restored);
            if (preset != null) {
                activePresetName = restored;
                activePreset = preset;
            }
        }
    }

    /** pi L122-170：应用预设（模型/思考/tools + 快照）。 */
    void applyPreset(String name, Preset preset, ExtensionContext ctx) {
        if (activePresetName == null) {
            originalState = new OriginalState();
            originalState.model = ctx.getModel();
            originalState.thinkingLevel = ctx.getThinkingLevel();
            originalState.tools = new ArrayList<>(ctx.getActiveTools());
        }
        if (preset.provider() != null && preset.model() != null) {
            var model = ctx.modelRegistry().find(preset.provider(), preset.model());
            if (model.isPresent()) {
                var ok = ctx.setModel(model.get().id());
                if (!ok) {
                    ctx.ui().notify("Preset \"" + name + "\": No API key for "
                        + preset.provider() + "/" + preset.model(), NotifyType.WARNING);
                }
            } else {
                ctx.ui().notify("Preset \"" + name + "\": Model "
                    + preset.provider() + "/" + preset.model() + " not found", NotifyType.WARNING);
            }
        }
        if (preset.thinkingLevel() != null) {
            com.pijava.ai.thinking.ModelThinkingLevel.parse(preset.thinkingLevel())
                .ifPresent(level -> ctx.setThinkingLevel(level));
        }
        if (preset.tools() != null && !preset.tools().isEmpty()) {
            var allNames = new ArrayList<String>();
            for (var t : ctx.getAllTools()) {
                allNames.add(t.name());
            }
            var valid = new ArrayList<String>();
            var invalid = new ArrayList<String>();
            for (var t : preset.tools()) {
                (allNames.contains(t) ? valid : invalid).add(t);
            }
            if (!invalid.isEmpty()) {
                ctx.ui().notify("Preset \"" + name + "\": Unknown tools: "
                    + String.join(", ", invalid), NotifyType.WARNING);
            }
            if (!valid.isEmpty()) {
                ctx.setActiveTools(valid);
            }
        }
        activePresetName = name;
        activePreset = preset;
    }

    /** pi L175-194：纯描述构造。 */
    static String buildPresetDescription(Preset p) {
        var parts = new ArrayList<String>();
        if (p.provider() != null && p.model() != null) {
            parts.add(p.provider() + "/" + p.model());
        }
        if (p.thinkingLevel() != null) {
            parts.add("thinking:" + p.thinkingLevel());
        }
        if (p.tools() != null && !p.tools().isEmpty()) {
            parts.add("tools:" + String.join(",", p.tools()));
        }
        if (p.instructions() != null && !p.instructions().isEmpty()) {
            var src = p.instructions();
            var truncated = src.substring(0, Math.min(27, src.length()))
                + (src.length() > 27 ? "..." : "");
            parts.add("\"" + truncated + "\"");
        }
        return String.join(" | ", parts);
    }

    /** pi 的 Object.keys(presets).sort()。 */
    static List<String> getPresetOrder(Map<String, Preset> presets) {
        var names = new ArrayList<>(presets.keySet());
        names.sort(String::compareTo);
        return names;
    }

    /** 从 entries 倒序找最后 preset-state 的 name（无则 null）。 */
    static String restoreName(List<Entry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i);
            if (entry instanceof Entry.Custom c && "preset-state".equals(c.customType())
                    && c.data() != null && c.data().get("name") instanceof String name) {
                return name;
            }
        }
        return null;
    }

    /** 加载配置（项目级覆盖全局）。 */
    static Map<String, Preset> loadPresets(Path globalPath, Path projectPath) {
        var merged = new LinkedHashMap<String, Preset>();
        merged.putAll(loadOne(globalPath, "global"));
        merged.putAll(loadOne(projectPath, "project"));
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Preset> loadOne(Path path, String scope) {
        if (!Files.exists(path)) {
            return Map.of();
        }
        try {
            var tree = MAPPER.readTree(Files.readString(path));
            var out = new LinkedHashMap<String, Preset>();
            tree.fields().forEachRemaining(entry -> {
                var node = entry.getValue();
                var tools = new ArrayList<String>();
                if (node.has("tools") && node.get("tools").isArray()) {
                    node.get("tools").forEach(t -> tools.add(t.asText()));
                }
                out.put(entry.getKey(), new Preset(
                    text(node, "provider"), text(node, "model"), text(node, "thinkingLevel"),
                    tools, text(node, "instructions")));
            });
            return out;
        } catch (Exception e) {
            System.err.println("Failed to load " + scope + " presets from " + path + ": " + e);
            return Map.of();
        }
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }

    private Path agentPresetsPath() {
        var agentDir = System.getProperty("pi.java.agentDir");
        return Path.of(agentDir == null
            ? Path.of(System.getProperty("user.home"), ".pi-java", "agent").toString()
            : agentDir, "presets.json");
    }

    private String available() {
        var names = getPresetOrder(presets);
        return names.isEmpty() ? "(none defined)" : String.join(", ", names);
    }

    private void cycleOrSelect(ExtensionContext ctx, String args) {
        if (!args.isBlank()) {
            var preset = presets.get(args.trim());
            if (preset != null) {
                applyPreset(args.trim(), preset, ctx);
                ctx.ui().notify("Preset \"" + args.trim() + "\" activated", NotifyType.INFO);
            } else {
                ctx.ui().notify("Unknown preset \"" + args.trim() + "\". Available: "
                    + available(), NotifyType.WARNING);
            }
            return;
        }
        cycle(ctx);
    }

    private void cycle(ExtensionContext ctx) {
        var names = getPresetOrder(presets);
        if (names.isEmpty()) {
            ctx.ui().notify("No presets defined", NotifyType.INFO);
            return;
        }
        var current = names.indexOf(activePresetName);
        var next = names.get((current + 1) % names.size());
        applyPreset(next, presets.get(next), ctx);
        ctx.ui().notify("Preset \"" + next + "\" activated", NotifyType.INFO);
    }

    String activePresetName() {
        return activePresetName;
    }

    /** before_agent_start 注入文本（无注入返回 null）。 */
    String injectedPrompt(String base) {
        if (activePreset != null && activePreset.instructions() != null
                && !activePreset.instructions().isEmpty()) {
            return base + "\n\n" + activePreset.instructions();
        }
        return null;
    }
}
