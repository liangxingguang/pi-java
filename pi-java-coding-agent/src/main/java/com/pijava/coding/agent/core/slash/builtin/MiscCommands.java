package com.pijava.coding.agent.core.slash.builtin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.pijava.ai.auth.FileCredentialStore;
import com.pijava.coding.agent.core.KeybindingsManager;
import com.pijava.coding.agent.core.SessionShare;
import com.pijava.coding.agent.core.crash.CrashLog;
import com.pijava.coding.agent.core.crash.CrashRecord;
import com.pijava.coding.agent.core.slash.CommandRegistry;
import com.pijava.coding.agent.export.HtmlExporter;
import com.pijava.coding.agent.core.slash.SlashCommand;
import com.pijava.coding.agent.core.slash.SlashContext;
import static com.pijava.coding.agent.core.slash.builtin.CommandUtil.async;
import static com.pijava.coding.agent.core.slash.builtin.CommandUtil.simple;

/**
 * Miscellaneous slash commands (Phase 3 design §14.2
 * #4/#5/#6/#7/#10/#11/#16/#17/#19/#22).
 */
public final class MiscCommands {

    private MiscCommands() {}

    /** Register the remaining 10 commands. */
    public static void registerAll(CommandRegistry registry) {
        registry.register(simple("help", "Show slash commands and shortcuts", "",
            (args, ctx) -> ctx.keybindings() == null
                ? registry.helpText()
                : registry.helpText() + "\n\n" + hotkeys(ctx)));
        // 批 15（T10，pi handleExportCommand interactive-mode.ts:6520-6533 对位）：
        // 路径参经引号解析；.jsonl 尾 ⇒ JSONL；其余（含无参）⇒ HTML——无参时
        // session-<ISO 时间戳 :.→->.html 落 cwd。状态/失败文案逐字。
        registry.register(simple("export",
            "Export session (HTML default, or specify path: .html/.jsonl)", "",
            (args, ctx) -> {
                var path = pathCommandArgument(args);
                try {
                    if (path != null && path.endsWith(".jsonl")) {
                        var target = java.nio.file.Path.of(path);
                        ctx.session().exportJsonl(target);
                        return "Session exported to: " + target;   // pi :6527 逐字
                    }
                    var target = java.nio.file.Path.of(path != null ? path
                        : "session-" + java.time.Instant.now().toString()
                            .replace(":", "-").replace(".", "-") + ".html");
                    var tmp = java.nio.file.Files.createTempFile("pi-java-export", ".jsonl");
                    try {
                        ctx.session().exportJsonl(tmp);
                        var out = new HtmlExporter().export(tmp, target);
                        return "Session exported to: " + out;      // pi :6531 逐字
                    } finally {
                        java.nio.file.Files.deleteIfExists(tmp);
                    }
                } catch (Exception e) {
                    return "Failed to export session: " + e.getMessage();  // pi :6535 逐字
                }
            }));
        registry.register(simple("import", "Import session from JSONL", "<file>",
            (args, ctx) -> {
                if (args.isBlank()) {
                    return "Usage: /import <file>.jsonl";
                }
                try {
                    var imported = ctx.session().importJsonl(
                        java.nio.file.Path.of(args.trim()));
                    ctx.onSwitchSession().accept(imported);
                    return "Imported session from " + args.trim();
                } catch (Exception e) {
                    // pi :6602-6605：file-not-found 是非致命臂（showError 继续会话）。
                    if (hasCause(e, java.nio.file.NoSuchFileException.class)) {
                        return "Failed to import session: " + e.getMessage();
                    }
                    // 批 15（裁决⑤/B256）：pi 的 /import 失败＝fatal（interactive-mode.ts
                    // :6607 → handleFatalRuntimeError）——记 crash ⇒ TUI 打错误＋崩溃指引
                    // ⇒ exit 1。旧「Import failed 继续会话」臂与 pi 语义相悖。
                    CrashLog.record(CrashRecord.Kind.FATAL_ERROR, e,
                        ctx.session().sessionFile(), ctx.session().cwd(),
                        CrashLog.crashLogPath());
                    return CommandRegistry.UI_FATAL_EXIT_PREFIX
                        + "Failed to import session: " + e.getMessage();
                }
            }));
        registry.register(new SlashCommand() {
            @Override public String name() { return "share"; }
            @Override public String description() { return "Share session as GitHub gist"; }
            @Override public String argumentHint() { return ""; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                try {
                    var tmp = java.nio.file.Files.createTempFile("pi-java-share", ".jsonl");
                    try {
                        ctx.session().exportJsonl(tmp);
                        var jsonl = java.nio.file.Files.readString(tmp);
                        var url = new SessionShare().share(jsonl);
                        return CompletableFuture.completedFuture("Shared session: " + url);
                    } finally {
                        java.nio.file.Files.deleteIfExists(tmp);
                    }
                } catch (Exception e) {
                    return CompletableFuture.completedFuture("Share failed: " + e.getMessage());
                }
            }
        });
        registry.register(new SlashCommand() {
            @Override public String name() { return "copy"; }
            @Override public String description() {
                return "Copy last agent message";
            }
            @Override public String argumentHint() { return ""; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                var lastAssistant = ctx.session().lastAssistantText();
                if (lastAssistant == null) {
                    return CompletableFuture.completedFuture(
                        "No assistant message to copy yet.");
                }
                try {
                    var clipboard = java.awt.Toolkit.getDefaultToolkit()
                        .getSystemClipboard();
                    clipboard.setContents(
                        new java.awt.datatransfer.StringSelection(lastAssistant), null);
                    return CompletableFuture.completedFuture("Copied.");
                } catch (Exception headless) {
                    // Headless environments fall back to printing the text.
                    return CompletableFuture.completedFuture(
                        "Clipboard unavailable; last message:\n" + lastAssistant);
                }
            }
        });
        // 批 12 T7（docs/23 §3；pi :6774-6793 对位）：内置 CHANGELOG.md 全量
        // reverse（最新在前）拼接，**不回写** lastChangelogVersion（回写只在
        // 启动比较两臂，见 ChangelogSupport.forDisplay）。
        registry.register(simple("changelog", "Show changelog", "",
            (args, ctx) -> {
                var entries = com.pijava.coding.agent.core.ChangelogSupport.parse(
                    com.pijava.coding.agent.core.ChangelogSupport.loadBundledUnchecked());
                if (entries.isEmpty()) {
                    return "No changelog entries found.";
                }
                var reversed = new java.util.ArrayList<>(entries);
                java.util.Collections.reverse(reversed);
                return "What's New\n\n" + reversed.stream()
                    .map(com.pijava.coding.agent.core.ChangelogSupport.ChangelogEntry::content)
                    .collect(java.util.stream.Collectors.joining("\n\n"));
            }));
        registry.register(simple("hotkeys", "Show all keyboard shortcuts", "",
            (args, ctx) -> hotkeys(ctx)));
        // 批 15（T7，pi slash-commands.ts:28 逐字）：/bug 故障上报流——marker
        // 不携载荷（裁决⑦），hint 由 TUI 从原始输入行切（pi :3242-3245）。
        registry.register(simple("bug", "Report a bug to the Pi developers", "<description>",
            (args, ctx) -> CommandRegistry.UI_BUG_REPORT));
        // B176（原 docs/27）：压缩跑 worker 线程 —— pi handleCompactCommand 是
        // async 函数，渲染/输入线程不被整次压缩阻塞。
        registry.register(async("compact", "Compact context manually", "<text>",
            (args, ctx) -> {
                try {
                    // B174（docs/23，pi interactive-mode.ts:3264）：trailing 文本
                    // trim 后作为 customInstructions；无/空白 ⇒ null。
                    String custom = args.isBlank() ? null : args.trim();
                    // B223（原 docs/47）：预算由会话按当前模型现解析（pi compact(custom)）。
                    ctx.session().compact(custom);
                    return "Compacted.";
                } catch (Exception e) {
                    return "Compaction failed: " + e.getMessage();
                }
            }));
        registry.register(new SlashCommand() {
            @Override public String name() { return "login"; }
            @Override public String description() { return "Configure provider auth"; }
            @Override public String argumentHint() { return "<provider>"; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                if (args.isBlank()) {
                    return CompletableFuture.completedFuture(
                        "Usage: /login <provider> (anthropic|openai|google|deepseek|mistral)");
                }
                var console = System.console();
                if (console == null) {
                    return CompletableFuture.completedFuture(
                        "No console available. Set the environment variable "
                            + "or run: pi-java auth " + args.trim());
                }
                var key = new String(console.readPassword(
                    "API key for %s: ", args.trim()));
                if (key.isBlank()) {
                    return CompletableFuture.completedFuture("Aborted.");
                }
                new FileCredentialStore().storeApiKey(args.trim(), key);
                return CompletableFuture.completedFuture(
                    "API key saved for " + args.trim() + ".");
            }
        });
        registry.register(simple("logout", "Remove provider auth", "<provider>",
            (args, ctx) -> {
                if (args.isBlank()) {
                    return "Usage: /logout <provider>";
                }
                new FileCredentialStore().deleteApiKey(args.trim());
                return "API key removed for " + args.trim() + ".";
            }));
        registry.register(new SlashCommand() {
            @Override public String name() { return "quit"; }
            @Override public String description() { return "Exit"; }
            @Override public String argumentHint() { return ""; }
            @Override public CompletionStage<String> execute(String args, SlashContext ctx) {
                ctx.onQuit().run();
                return CompletableFuture.completedFuture("Bye.");
            }
        });
    }

    /**
     * pi {@code getPathCommandArgument}（interactive-mode.ts:6537-6562）逐字：
     * trimStart 后空 ⇒ 缺省；首字符 {@code '}/{@code "} ⇒ 取到配对引号为止
     * （无配对 ⇒ 缺省）；否则取到首个空白。
     */
    static String pathCommandArgument(String args) {
        var argsString = args == null ? "" : args.stripLeading();
        if (argsString.isEmpty()) {
            return null;
        }
        var firstChar = argsString.charAt(0);
        if (firstChar == '"' || firstChar == '\'') {
            int closing = argsString.indexOf(firstChar, 1);
            if (closing < 0) {
                return null;
            }
            return argsString.substring(1, closing);
        }
        int whitespace = -1;
        for (int i = 0; i < argsString.length(); i++) {
            if (Character.isWhitespace(argsString.charAt(i))) {
                whitespace = i;
                break;
            }
        }
        return whitespace < 0 ? argsString : argsString.substring(0, whitespace);
    }

    /** 原因链查找（/import 的 file-not-found 非致命臂判据）。 */
    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (var cursor = error; cursor != null; cursor = cursor.getCause()) {
            if (type.isInstance(cursor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 批 25（docs/36 T8，裁决⑤）：输出改 markdown 源（pi /hotkeys 同形——
     * 粗体标题＋管道表格＋codespan 键名，interactive-mode.ts:6853-6913），
     * TUI 侧经 Markdown 渲染。java 动作集动态枚举（pi 是手工固定行，
     * S24 判定不翻＝增强）；空绑定动作（批 14：app.session.new 等）键格留空。
     */
    private static String hotkeys(SlashContext ctx) {
        var keys = ctx.keybindings();
        var builder = new StringBuilder("**Keybindings**\n\n| Action | Key |\n|------|-----|\n");
        for (var actionId : keys.actionIds()) {
            var keyText = describe(keys.strokeFor(actionId));
            builder.append("| `").append(actionId.replaceFirst("^app\\.", "")).append("` | ");
            if (!keyText.isEmpty()) {
                builder.append('`').append(keyText).append('`');
            }
            builder.append(" |\n");
        }
        // 批 26（docs/37 T9）：pi interactive-mode.ts:6901-6915——扩展快捷键段
        //（描述缺省回落 extensionPath，pi `shortcut.description ?? shortcut.extensionPath`）。
        var session = ctx.session();
        var extensionShortcuts = session == null
            ? java.util.List.<com.pijava.coding.agent.extension.ExtensionShortcuts
                .ResolvedShortcut>of()
            : session.extensionShortcuts(keys).shortcuts();
        if (!extensionShortcuts.isEmpty()) {
            builder.append("\n\n**Extensions**\n\n| Key | Action |\n|-----|--------|\n");
            for (var shortcut : extensionShortcuts) {
                builder.append("| `").append(describe(shortcut.stroke())).append("` | ")
                    .append(shortcut.description() != null
                        ? shortcut.description() : shortcut.extensionPath())
                    .append(" |\n");
            }
        }
        return builder.toString().stripTrailing();
    }

    private static String describe(KeybindingsManager.KeyStroke stroke) {
        // 批 14（docs/25 T3）：pi formatKeys([]) ⇒ ""——空绑定动作
        //（app.session.new/tree/fork/resume，pi defaultKeys: []）的展示形。
        if (stroke == null) {
            return "";
        }
        var builder = new StringBuilder();
        if (stroke.ctrl()) builder.append("Ctrl+");
        if (stroke.alt()) builder.append("Alt+");
        if (stroke.shift()) builder.append("Shift+");
        builder.append(stroke.key().toUpperCase());
        return builder.toString();
    }

}
