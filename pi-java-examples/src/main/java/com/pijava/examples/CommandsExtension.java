package com.pijava.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.pijava.coding.agent.core.slash.SlashCommandInfo;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;

/**
 * pi {@code examples/extensions/commands.ts:1-72} 移植（docs/27 W6）——
 * {@code /commands [extension|prompt|skill]} 用 {@code ctx.getCommands()} 列出当前
 * 会话可调用的斜杠命令（按来源分组进选择器），选中后可查看来源路径。
 *
 * <p>逐字对位：来源过滤、三组表头 {@code "--- Extensions ---"} 等、条目形
 * {@code "/name - description"}、选中后剥前导 {@code /} 再按名字回查、
 * 有 {@code sourceInfo.path} 才问 {@code "View source path?\n<path>"}。</p>
 *
 * <p>窄差（**D253**）：{@link com.pijava.coding.agent.core.slash.SlashCommand}
 * 无 {@code getArgumentCompletions} 对位（命令参数补全是 pi 侧独立通道，Java 只有
 * 扩展级 autocomplete provider 空接口），故补全以降级为纯函数
 * {@link #sourceCompletions(String)} 供断言，不注册到键盘面。</p>
 */
public final class CommandsExtension implements PiExtension {

    /** pi L19：可补全的来源字面量（逐字）。 */
    static final List<String> SOURCES = List.of("extension", "prompt", "skill");

    /** pi L43-45：分组（来源键 ⇒ 小节标题，逐字）。 */
    record SourceGroup(String key, String label) {}

    /** 三组固定顺序（pi L42-46）。 */
    static final List<SourceGroup> SOURCE_GROUPS = List.of(
        new SourceGroup("extension", "Extensions"),
        new SourceGroup("prompt", "Prompts"),
        new SourceGroup("skill", "Skills"));

    @Override
    public String name() {
        return "commands";
    }

    @Override
    public void register(ExtensionContext ctx) {
        ctx.slashCommands().register(ExampleCommands.command("commands",
            "List available slash commands", args -> run(ctx, args)));
    }

    /** pi L18-22：参数补全（{@code null} ≙ pi 的 {@code null}）。 */
    static List<String> sourceCompletions(String prefix) {
        var head = prefix == null ? "" : prefix;
        var filtered = SOURCES.stream().filter(s -> s.startsWith(head)).toList();
        return filtered.isEmpty() ? null : filtered;
    }

    /** pi L36-39：{@code `/${cmd.name}${desc}`}。 */
    static String formatCommand(SlashCommandInfo cmd) {
        var desc = cmd.description() == null || cmd.description().isEmpty()
            ? "" : " - " + cmd.description();
        return "/" + cmd.name() + desc;
    }

    /** pi L41-54：按三组顺序拼条目（组内有命令才出表头）。 */
    static List<String> buildItems(List<SlashCommandInfo> filtered) {
        var items = new ArrayList<String>();
        for (var group : SOURCE_GROUPS) {
            var cmds = filtered.stream().filter(c -> group.key().equals(c.source())).toList();
            if (!cmds.isEmpty()) {
                items.add("--- " + group.label() + " ---");
                cmds.forEach(c -> items.add(formatCommand(c)));
            }
        }
        return List.copyOf(items);
    }

    /** pi L61：{@code selected.split(" - ")[0].slice(1)}。 */
    static String commandNameOf(String selected) {
        var head = selected.split(" - ", -1)[0];
        return head.startsWith("/") ? head.substring(1) : head;
    }

    /** pi L23-70：命令体。 */
    void run(ExtensionContext ctx, String args) {
        var commands = ctx.getCommands();
        var sourceFilter = args == null ? "" : args.trim();
        var filtered = sourceFilter.isEmpty() ? commands
            : commands.stream()
                .filter(c -> sourceFilter.toLowerCase(Locale.ROOT)
                    .equals(c.source() == null ? null : c.source().toLowerCase(Locale.ROOT)))
                .toList();

        if (filtered.isEmpty()) {
            ctx.ui().notify(sourceFilter.isEmpty() ? "No commands found"
                : "No " + sourceFilter + " commands found", NotifyType.INFO);
            return;
        }

        var selected = ctx.ui().select("Available Commands", buildItems(filtered), null);
        if (selected == null || selected.startsWith("---")) {
            return;
        }
        var cmdName = commandNameOf(selected);
        var cmd = commands.stream().filter(c -> c.name().equals(cmdName)).findFirst()
            .orElse(null);
        if (cmd == null || cmd.sourceInfo() == null || cmd.sourceInfo().path() == null) {
            return;
        }
        if (ctx.ui().confirm(cmd.name(), "View source path?\n" + cmd.sourceInfo().path(),
                null)) {
            ctx.ui().notify(cmd.sourceInfo().path(), NotifyType.INFO);
        }
    }
}
