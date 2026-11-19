package com.pijava.examples;

import java.util.List;

import com.pijava.agent.entry.Entry;
import com.pijava.ai.message.Message;
import com.pijava.coding.agent.extension.ExtensionContext;
import com.pijava.coding.agent.extension.NotifyType;
import com.pijava.coding.agent.extension.PiExtension;
import com.pijava.coding.agent.extension.event.ExtensionEventSpec;

/**
 * pi {@code examples/extensions/auto-commit-on-exit.ts:1-49} 移植（docs/52 §2.8）——
 * session_shutdown 时若有未提交改动，用最后一条 assistant 消息生成 commit message
 * 并自动 git add/commit。执行经 {@link GitExecutor} 接缝，测试注入 fake。
 */
public final class AutoCommitOnExitExtension implements PiExtension {

    private GitExecutor git;

    @Override
    public String name() {
        return "auto-commit-on-exit";
    }

    @Override
    public void register(ExtensionContext ctx) {
        this.git = (cmd, args) -> ctx.exec(cmd, args, null);
        ctx.on(ExtensionEventSpec.SESSION_SHUTDOWN, (event, c) -> {
            onShutdown(c);
            return null;
        });
    }

    /** 仅供测试注入 fake；生产由 register 绑定。 */
    void git(GitExecutor git) {
        this.git = git;
    }

    /** pi L11-48：退出检查与提交。 */
    void onShutdown(ExtensionContext ctx) {
        var status = git.exec("git", List.of("status", "--porcelain"));
        if (status.code() != 0 || status.stdout().isBlank()) {
            return;
        }
        var lastText = lastAssistantText(ctx.sessionManager().getEntries());
        var commitMessage = commitMessage(lastText);

        git.exec("git", List.of("add", "-A"));
        var committed = git.exec("git", List.of("commit", "-m", commitMessage));
        if (committed.code() == 0 && ctx.hasUi()) {
            ctx.ui().notify("Auto-committed: " + commitMessage, NotifyType.INFO);
        }
    }

    /** 倒序取最后一条 assistant 消息的全文（数组 text parts 换行拼接）。 */
    static String lastAssistantText(List<Entry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i);
            if (!(entry instanceof Entry.Message m)) {
                continue;
            }
            var msg = m.message();
            if ("assistant".equals(msg.role())) {
                return ExampleTexts.textOf(msg.content());
            }
        }
        return null;
    }

    /** pi L38-39：commit message 生成（50 字符截断+"..."、前缀、回退）。 */
    static String commitMessage(String lastAssistantText) {
        var firstLine = (lastAssistantText == null ? "" : lastAssistantText.split("\n", -1)[0]);
        if (firstLine.isEmpty()) {
            firstLine = "Work in progress";
        }
        var truncated = firstLine.length() > 50 ? firstLine.substring(0, 50) + "..." : firstLine;
        return "[pi] " + truncated;
    }
}
