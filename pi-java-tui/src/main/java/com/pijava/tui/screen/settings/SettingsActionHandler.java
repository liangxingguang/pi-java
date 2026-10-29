package com.pijava.tui.screen.settings;

import com.pijava.agent.harness.QueueMode;
import com.pijava.coding.agent.core.AgentSession;

/** Applies settings-list values to the real session and settings store. */
public final class SettingsActionHandler {

    /** Host effects that cannot live in the settings store. */
    public interface Host {
        /** Rebuild slash autocomplete after enableSkillCommands changes. */
        default void rebuildAutocomplete() {}
        /** Rebuild transcript after cache-notice visibility changes. */
        default void rebuildTranscript() {}
    }

    private final AgentSession session;
    private final Host host;

    /** Create a writer bound to one session and its host effects. */
    public SettingsActionHandler(AgentSession session, Host host) {
        this.session = session;
        this.host = host;
    }

    /** Write one value and flush once; unknown ids fail loudly. */
    public void apply(String id, String value) {
        var settings = session.services().settings();
        var accessors = settings.accessors();
        var compaction = settings.compaction();
        var ui = settings.uiPrefs();
        switch (id) {
            case "autocompact" -> compaction.setCompactionEnabled(Boolean.parseBoolean(value));
            case "show-images" -> ui.setShowImages(Boolean.parseBoolean(value));
            case "image-width-cells" -> ui.setImageWidthCells(Integer.parseInt(value));
            case "auto-resize-images" -> compaction.setImageAutoResize(Boolean.parseBoolean(value));
            case "block-images" -> compaction.setBlockImages(Boolean.parseBoolean(value));
            case "skill-commands" -> {
                accessors.setEnableSkillCommands(Boolean.parseBoolean(value));
                host.rebuildAutocomplete();
            }
            case "show-hardware-cursor" -> ui.setShowHardwareCursor(Boolean.parseBoolean(value));
            case "editor-padding" -> ui.setEditorPaddingX(Integer.parseInt(value));
            case "output-padding" -> ui.setOutputPad(Integer.parseInt(value));
            case "autocomplete-max-visible" -> ui.setAutocompleteMaxVisible(Integer.parseInt(value));
            case "clear-on-shrink" -> ui.setClearOnShrink(Boolean.parseBoolean(value));
            case "terminal-progress" -> ui.setShowTerminalProgress(Boolean.parseBoolean(value));
            case "steering-mode" -> {
                accessors.setSteeringMode(value);
                updateQueueModes();
            }
            case "follow-up-mode" -> {
                accessors.setFollowUpMode(value);
                updateQueueModes();
            }
            case "transport" -> settings.network().setTransport(value);
            case "http-idle-timeout" -> settings.network().setHttpIdleTimeoutMs(timeoutMs(value));
            case "cache-warming-mode" -> session.setCacheWarmingMode(value);
            case "hide-thinking" -> accessors.setHideThinkingBlock(Boolean.parseBoolean(value));
            case "mermaid-rendering" -> compaction.setMarkdownMermaid(value);
            case "cache-miss-notices" -> {
                ui.setShowCacheMissNotices(Boolean.parseBoolean(value));
                host.rebuildTranscript();
            }
            case "collapse-changelog" -> settings.install().setCollapseChangelog(Boolean.parseBoolean(value));
            case "quiet-startup" -> accessors.setQuietStartupMode(value);
            case "install-telemetry" -> settings.install().setEnableInstallTelemetry(Boolean.parseBoolean(value));
            case "default-project-trust" -> accessors.setDefaultProjectTrust(trustValue(value));
            case "double-escape-action" -> accessors.setDoubleEscapeAction(value);
            case "tree-filter-mode" -> accessors.setTreeFilterMode(value);
            case "tui-mode" -> accessors.setTuiMode(value);
            case "fullscreen-exit-output" -> ui.setFullscreenExitOutput(value);
            case "fullscreen-scrollbar" -> ui.setFullscreenScrollbar(value);
            case "fullscreen-copy-on-select" -> ui.setFullscreenCopyOnSelect(Boolean.parseBoolean(value));
            case "fullscreen-wheel-scroll-lines" -> ui.setFullscreenWheelScrollLines(
                "auto".equals(value) ? "auto" : Integer.valueOf(value));
            default -> throw new IllegalArgumentException("Unknown setting: " + id);
        }
        settings.flush();
    }

    private void updateQueueModes() {
        var accessors = session.services().settings().accessors();
        session.harness().setQueueModes(
            queueMode(accessors.getSteeringMode()), queueMode(accessors.getFollowUpMode()));
    }

    private static QueueMode queueMode(String value) {
        return "all".equals(value) ? new QueueMode.All() : new QueueMode.OneAtATime();
    }

    private static long timeoutMs(String label) {
        return switch (label) {
            case "30 sec" -> 30_000;
            case "1 min" -> 60_000;
            case "2 min" -> 120_000;
            case "5 min" -> 300_000;
            case "disabled" -> 0;
            default -> throw new IllegalArgumentException("Unknown HTTP idle timeout: " + label);
        };
    }

    private static String trustValue(String label) {
        return switch (label) {
            case "Always trust" -> "always";
            case "Never trust" -> "never";
            default -> "ask";
        };
    }
}
