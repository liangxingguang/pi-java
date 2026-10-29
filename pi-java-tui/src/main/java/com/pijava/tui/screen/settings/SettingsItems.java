package com.pijava.tui.screen.settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.pijava.ai.catalog.ModelCatalog;
import com.pijava.ai.model.ModelId;
import com.pijava.coding.agent.core.SettingsManager;

/** Builds the complete pi settings-selector.ts item table. */
public final class SettingsItems {

    private SettingsItems() {}

    /** Build rows in pi order, including conditional image rows. */
    public static List<SettingsItem> build(
            SettingsManager settings, ModelCatalog availableModels, ModelId<?> currentModel,
            String currentTheme, List<String> availableThemes, boolean supportsImages,
            String followUpKey, String cycleThinkingKey) {
        var accessors = settings.accessors();
        var compaction = settings.compaction();
        var network = settings.network();
        var ui = settings.uiPrefs();
        var install = settings.install();
        var items = new ArrayList<SettingsItem>();

        items.add(cycle("autocompact", "Auto-compact",
            "Automatically compact context when it gets too large",
            bool(compaction.getCompactionEnabled()), "true", "false"));
        if (supportsImages) {
            items.add(cycle("show-images", "Show images", "Render images inline in terminal",
                bool(ui.getShowImages()), "true", "false"));
            items.add(cycle("image-width-cells", "Image width",
                "Preferred inline image width in terminal cells",
                String.valueOf(ui.getImageWidthCells()), "60", "80", "120"));
        }
        items.add(cycle("auto-resize-images", "Auto-resize images",
            "Resize large images to 2000x2000 max for better model compatibility",
            bool(compaction.getImageAutoResize()), "true", "false"));
        items.add(cycle("block-images", "Block images", "Prevent images from being sent to LLM providers",
            bool(compaction.getBlockImages()), "true", "false"));
        items.add(cycle("skill-commands", "Skill commands", "Register skills as /skill:name commands",
            bool(accessors.getEnableSkillCommands()), "true", "false"));
        items.add(cycle("show-hardware-cursor", "Show hardware cursor",
            "Use the terminal cursor instead of Pi's drawn cursor",
            bool(ui.getShowHardwareCursor()), "true", "false"));
        items.add(cycle("editor-padding", "Editor padding", "Horizontal padding for input editor (0-3)",
            String.valueOf(ui.getEditorPaddingX()), "0", "1", "2", "3"));
        items.add(cycle("output-padding", "Output padding",
            "Horizontal padding for messages, tool output, and command output",
            String.valueOf(ui.getOutputPad()), "0", "1"));
        items.add(cycle("autocomplete-max-visible", "Autocomplete max items",
            "Max visible items in autocomplete dropdown (3-20)",
            String.valueOf(ui.getAutocompleteMaxVisible()), "3", "5", "7", "10", "15", "20"));
        items.add(cycle("clear-on-shrink", "Clear on shrink",
            "Clear empty rows when content shrinks (may cause flicker)",
            bool(ui.getClearOnShrink()), "true", "false"));
        items.add(cycle("terminal-progress", "Terminal progress",
            "Show OSC 9;4 progress indicators in the terminal tab bar",
            bool(ui.getShowTerminalProgress()), "true", "false"));
        items.add(cycle("steering-mode", "Steering mode",
            "Enter while streaming queues steering messages. 'one-at-a-time': deliver one, "
                + "wait for response. 'all': deliver all at once.",
            value(accessors.getSteeringMode(), "one-at-a-time"), "one-at-a-time", "all"));
        items.add(cycle("follow-up-mode", "Follow-up mode",
            followUpKey + " queues follow-up messages until agent stops. 'one-at-a-time': deliver one, "
                + "wait for response. 'all': deliver all at once.",
            value(accessors.getFollowUpMode(), "one-at-a-time"), "one-at-a-time", "all"));
        items.add(cycle("transport", "Transport",
            "Preferred transport for providers that support multiple transports",
            network.getTransport(), "sse", "websocket", "websocket-cached", "auto"));
        items.add(cycle("http-idle-timeout", "HTTP idle timeout",
            "Maximum idle gap while waiting for HTTP headers or body chunks. Disable for local models "
                + "that pause longer than five minutes.",
            timeoutLabel(network.getHttpIdleTimeoutMs()), "30 sec", "1 min", "2 min", "5 min", "disabled"));
        items.add(cycle("cache-warming-mode", "Cache warming",
            "off; streaming while the agent runs; idle also between runs while continuation stays profitable",
            network.getCacheWarmingMode(), "off", "streaming", "idle"));
        items.add(cycle("hide-thinking", "Hide thinking", "Hide thinking blocks in assistant responses",
            bool(Boolean.TRUE.equals(accessors.getHideThinkingBlock())), "true", "false"));
        items.add(cycle("mermaid-rendering", "Mermaid diagrams",
            "Render Mermaid code blocks as Unicode diagrams",
            compaction.getMarkdownMermaid(), "off", "final", "streaming"));
        items.add(cycle("cache-miss-notices", "Cache miss notices",
            "Show transcript notices for cache costs and provider recovery diagnostics",
            bool(ui.getShowCacheMissNotices()), "true", "false"));
        items.add(cycle("collapse-changelog", "Collapse changelog", "Show condensed changelog after updates",
            bool(install.getCollapseChangelog()), "true", "false"));
        items.add(cycle("quiet-startup", "Quiet startup",
            "Disable verbose printing at startup (header: keep only the startup header)",
            accessors.getQuietStartupMode(), "true", "header", "false"));
        items.add(cycle("install-telemetry", "Install telemetry",
            "Send an anonymous version/update ping after changelog-detected updates",
            bool(install.getEnableInstallTelemetry()), "true", "false"));
        items.add(cycle("default-project-trust", "Default project trust",
            "Fallback behavior when no extension or saved trust decision decides project trust",
            trustLabel(accessors.getDefaultProjectTrust()), "Ask", "Always trust", "Never trust"));
        items.add(cycle("double-escape-action", "Double-escape action",
            "Action when pressing Escape twice with empty editor",
            accessors.getDoubleEscapeAction(), "tree", "fork", "none"));
        items.add(cycle("tree-filter-mode", "Tree filter mode", "Default filter when opening /tree",
            value(accessors.getTreeFilterMode(), "default"),
            "default", "no-tools", "user-only", "labeled-only", "all"));
        items.add(SettingsItem.submenu("warnings", "Warnings", "Enable or disable individual warnings",
            "configure", SettingsItem.Submenu.WARNINGS));
        items.add(SettingsItem.submenu("model-thinking", "Default thinking level per model",
            "Override the default thinking level for specific models. " + cycleThinkingKey + " cycles in-session.",
            thinkingSummary(accessors.getAllModelThinkingLevels().size()), SettingsItem.Submenu.MODEL_THINKING));
        items.add(cycle("tui-mode", "TUI mode",
            "Interface layout; regular mode uses the terminal's normal scrollback",
            value(accessors.getTuiMode(), "fullscreen"), "regular", "fullscreen"));
        items.add(cycle("fullscreen-exit-output", "Fullscreen exit output",
            "Print the transcript or only a session resume hint when exiting fullscreen mode",
            ui.getFullscreenExitOutput(), "transcript", "resume-hint"));
        items.add(cycle("fullscreen-scrollbar", "Fullscreen scrollbar",
            "Scrollbar behavior in fullscreen mode; has no effect in regular mode",
            ui.getFullscreenScrollbar(), "auto", "always", "hidden"));
        items.add(cycle("fullscreen-copy-on-select", "Fullscreen copy on select",
            "Automatically copy selected text in fullscreen mode; disable to copy selections with Ctrl+X",
            bool(ui.getFullscreenCopyOnSelect()), "true", "false"));
        items.add(cycle("fullscreen-wheel-scroll-lines", "Fullscreen wheel scrolling",
            "Lines per mouse-wheel event in fullscreen mode; 'auto' speeds up fast wheel spins where the "
                + "terminal does not",
            String.valueOf(ui.getFullscreenWheelScrollLines()), wheelValues(ui.getFullscreenWheelScrollLines())));
        items.add(SettingsItem.submenu("theme", "Theme", "Color theme for the interface",
            currentTheme, SettingsItem.Submenu.THEME));
        return List.copyOf(items);
    }

    private static SettingsItem cycle(String id, String label, String description,
                                      String current, String... values) {
        return SettingsItem.cycle(id, label, description, current, List.of(values));
    }

    private static SettingsItem cycle(String id, String label, String description,
                                      String current, List<String> values) {
        return SettingsItem.cycle(id, label, description, current, values);
    }

    private static List<String> wheelValues(Object current) {
        var numbers = new LinkedHashSet<Integer>();
        for (int value : new int[] {1, 2, 3, 5, 10}) {
            numbers.add(value);
        }
        if (current instanceof Number number) {
            numbers.add(number.intValue());
        }
        var values = new ArrayList<String>();
        values.add("auto");
        numbers.stream().sorted().map(String::valueOf).forEach(values::add);
        return List.copyOf(values);
    }

    private static String timeoutLabel(long timeoutMs) {
        return switch ((int) timeoutMs) {
            case 30_000 -> "30 sec";
            case 60_000 -> "1 min";
            case 120_000 -> "2 min";
            case 300_000 -> "5 min";
            case 0 -> "disabled";
            default -> (timeoutMs / 1000) + " sec";
        };
    }

    private static String trustLabel(String value) {
        return switch (value == null ? "ask" : value) {
            case "always" -> "Always trust";
            case "never" -> "Never trust";
            default -> "Ask";
        };
    }

    private static String thinkingSummary(int count) {
        return count == 0 ? "(none)" : count + " override" + (count > 1 ? "s" : "");
    }

    private static String bool(boolean value) {
        return Boolean.toString(value);
    }

    private static String value(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
