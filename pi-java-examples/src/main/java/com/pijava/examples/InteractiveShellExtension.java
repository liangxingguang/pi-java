package com.pijava.examples;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.pijava.agent.tool.ShellResult;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;
import com.pijava.coding.agent.extension.event.ExtensionResults.UserBashEventResult;

/**
 * pi {@code examples/extensions/interactive-shell.ts:1-196} 移植（docs/54 §2.9）——
 * 只拦 user_bash（用户 `!` 命令），交互式命令交给注入式 {@link ShellRunner}（不真抢终端，
 * D238）；非交互命令返回 null 让宿主本地执行。
 */
public final class InteractiveShellExtension implements PiExtension {

    /** 注入式交互 shell 后端；返回 null ≙ 取消。 */
    @FunctionalInterface
    interface ShellRunner {
        Integer run(String command);
    }

    /** pi L27-98：59 条默认交互命令（逐字）。 */
    static final List<String> DEFAULT_COMMANDS = List.of(
        "vim", "nvim", "vi", "nano", "emacs", "pico", "micro", "helix", "hx", "kak",
        "less", "more", "most", "git commit", "git rebase", "git merge", "git cherry-pick",
        "git revert", "git add -p", "git add --patch", "git add -i", "git add --interactive",
        "git stash -p", "git stash --patch", "git reset -p", "git reset --patch",
        "git checkout -p", "git checkout --patch", "git difftool", "git mergetool",
        "htop", "top", "btop", "glances", "ranger", "nnn", "lf", "mc", "vifm", "tig",
        "lazygit", "gitui", "fzf", "sk", "ssh", "telnet", "mosh", "psql", "mysql",
        "sqlite3", "mongosh", "redis-cli", "kubectl edit", "kubectl exec -it",
        "docker exec -it", "docker run -it", "tmux", "screen", "ncdu");

    private ShellRunner runner;

    @Override
    public String name() {
        return "interactive-shell";
    }

    @Override
    public void register(ExtensionContext ctx) {
        this.runner = command -> null; // 默认不真执行；测试注入
        ctx.on(ExtensionEventSpec.USER_BASH, (event, c) -> handle(event.command(), c));
    }

    void runner(ShellRunner runner) {
        this.runner = runner;
    }

    /** 合并环境变量后的交互命令集合（pi getInteractiveCommands L100-107）。 */
    static Set<String> getInteractiveCommands() {
        var set = new LinkedHashSet<>(DEFAULT_COMMANDS);
        var extra = System.getenv("INTERACTIVE_COMMANDS");
        if (extra != null && !extra.isBlank()) {
            for (var cmd : extra.split(",")) {
                if (!cmd.isBlank()) {
                    set.add(cmd.trim());
                }
            }
        }
        var exclude = System.getenv("INTERACTIVE_EXCLUDE");
        if (exclude != null && !exclude.isBlank()) {
            var lower = new LinkedHashSet<String>();
            for (var cmd : set) {
                if (!cmd.toLowerCase(Locale.ROOT).equals(exclude.trim().toLowerCase(Locale.ROOT))) {
                    lower.add(cmd);
                }
            }
            return lower;
        }
        return set;
    }

    /** pi L109-129：前缀匹配 + 最后一个 `|` 之后匹配。 */
    static boolean isInteractiveCommand(String command) {
        var trimmed = command.trim();
        for (var cmd : getInteractiveCommands()) {
            if (trimmed.equals(cmd) || trimmed.startsWith(cmd + " ")) {
                return true;
            }
        }
        var pipe = trimmed.lastIndexOf('|');
        var tail = (pipe < 0 ? trimmed : trimmed.substring(pipe + 1)).trim();
        for (var cmd : getInteractiveCommands()) {
            if (tail.equals(cmd) || tail.startsWith(cmd + " ")) {
                return true;
            }
        }
        return false;
    }

    /** pi L138-141：`!i` 强制前缀。 */
    static String stripForcePrefix(String command) {
        if (command.startsWith("i ") || command.startsWith("i\t")) {
            return command.substring(2).trim();
        }
        return command;
    }

    /** user_bash 处理：非交互返回 null；交互按 runner 结果返回 Result 臂。 */
    UserBashEventResult handle(String rawCommand, ExtensionContext ctx) {
        var command = stripForcePrefix(rawCommand);
        if (!isInteractiveCommand(command)) {
            return null; // 交还宿主本地执行
        }
        if (!"tui".equals(ctx.mode())) {
            return new UserBashEventResult.Result(
                new ShellResult("(interactive commands require TUI)", 1, false, false, 0, 0));
        }
        Integer status = runner.run(command);
        var exitCode = status == null ? 1 : status;
        var output = status != null && status == 0
            ? "(interactive command completed successfully)"
            : "(interactive command exited with code " + exitCode + ")";
        return new UserBashEventResult.Result(
            new ShellResult(output, exitCode, false, false, 0, 0));
    }
}
