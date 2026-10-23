package com.pijava.coding.agent.core.slash.builtin;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;

/**
 * Settings and trust slash commands (Phase 3 design §14.2
 * #1/#15/#21).
 */
public final class SettingsCommands {

    private SettingsCommands() {}

    /** Register {@code /settings /trust /reload}. */
    public static void registerAll(CommandRegistry registry) {
        registry.register(new SlashCommand() {
            @Override public String name() { return "settings"; }
            @Override public String description() { return "Open settings"; }
            @Override public String argumentHint() { return ""; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                return CompletableFuture.completedFuture(CommandRegistry.UI_SETTINGS);
            }
        });
        registry.register(new SlashCommand() {
            @Override public String name() { return "trust"; }
            @Override public String description() { return "Save project trust decision"; }
            @Override public String argumentHint() { return "always|never|ask"; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                var value = args.trim().toLowerCase();
                if (!value.equals("always") && !value.equals("never")
                        && !value.equals("ask")) {
                    return CompletableFuture.completedFuture(
                        "Usage: /trust always|never|ask");
                }
                var trust = ctx.session().services().trust();
                trust.setDefaultTrust(value);
                trust.trust(Path.of(System.getProperty("user.dir")),
                    value.equals("always"));
                var accessors = ctx.session().services().settings().accessors();
                accessors.setDefaultProjectTrust(value);
                ctx.session().services().settings().flush();
                return CompletableFuture.completedFuture(
                    "Project trust set to \"" + value
                        + "\" (persistence in Phase 4)");
            }
        });
        registry.register(new SlashCommand() {
            @Override public String name() { return "reload"; }
            @Override public String description() {
                // 批 16（T5c）：pi 逐字（slash-commands.ts:42）。
                return "Reload keybindings, extensions, skills, prompts, themes, and context files";
            }
            @Override public String argumentHint() { return ""; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                // 批 16（T5c）：pi handleReloadCommand 双门文案逐字
                //（interactive-mode.ts:6430-6440）；java slash 返回 String 状态文案——
                // 警告与结果同通道（既有样式）。pi 的 isStreaming ≙ java isRunning（A5）。
                var session = ctx.session();
                if (session.isRunning()) {
                    return CompletableFuture.completedFuture(
                        "Wait for the current response to finish before reloading.");
                }
                if (session.isCompacting()) {
                    return CompletableFuture.completedFuture(
                        "Wait for compaction to finish before reloading.");
                }
                try {
                    session.reload();
                    // pi 成功文案逐字（interactive-mode.ts:6506-6510）。themes 实际
                    // 重扫随批 20（裁决⑨）；Reloading 边框盒/beforeSessionStart 重建
                    // chat 属 pi TUI 组件层，java 无对等体系（窄差 D93）。
                    return CompletableFuture.completedFuture(
                        "Reloaded keybindings, extensions, skills, prompts, themes, and context files");
                } catch (Exception e) {
                    return CompletableFuture.completedFuture(
                        "Reload failed: " + e.getMessage());
                }
            }
        });
    }
}
